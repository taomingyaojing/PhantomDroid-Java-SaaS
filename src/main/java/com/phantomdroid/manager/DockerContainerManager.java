package com.phantomdroid.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.okhttp.OkHttpDockerCmdExecFactory;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import com.phantomdroid.config.PhantomDroidProperties;
import com.phantomdroid.dto.DeviceDTO;
import com.phantomdroid.util.FingerprintGenerator;
import com.phantomdroid.util.ScrcpyStreamUtil;
import java.io.InputStream;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Manages Redroid container lifecycle via docker-java SDK + Virtual Thread ADB operations.
 * All public methods return CompletableFuture for non-blocking orchestration.
 */
@Component
public class DockerContainerManager {

    private static final Logger log = LoggerFactory.getLogger(DockerContainerManager.class);

    private static final String REDROID_IMAGE = "redroid/redroid:11.0.0-latest";

    private final PhantomDroidProperties props;
    private final ObjectMapper objectMapper;
    private final Map<Integer, DeviceDTO> deviceRegistry = new ConcurrentHashMap<>();
    private final ScheduledExecutorService idleScheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private DockerClient dockerClient;
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();

    public DockerContainerManager(PhantomDroidProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        var cfg = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(props.getDocker().getHost())
                .build();
        var httpClient = new OkDockerHttpClient.Builder()
                .dockerHost(java.net.URI.create(props.getDocker().getHost()))
                .build();
        this.dockerClient = DockerClientImpl.getInstance(cfg, httpClient);

        if (props.getContainer().getIdleTtlMinutes() > 0) {
            idleScheduler.scheduleAtFixedRate(this::reapIdleContainers,
                    5, 5, TimeUnit.MINUTES);
        }

        log.info("DockerContainerManager initialized, docker={}", props.getDocker().getHost());
    }

    @PreDestroy
    public void shutdown() {
        idleScheduler.shutdown();
        try {
            if (dockerClient != null) dockerClient.close();
        } catch (IOException e) {
            log.warn("Error closing DockerClient", e);
        }
    }

    // ============================================================
    //  PUBLIC METHODS
    // ============================================================

    /**
     * Backward-compatible overload that creates a container without proxy settings.
     * Delegates to the full signature with proxyIp=null.
     */
    public CompletableFuture<DeviceDTO> createPhoneContainer(String deviceId, String brand, int adbPort) {
        return createPhoneContainer(deviceId, brand, adbPort, null, null, null);
    }

    /**
     * Create a phone container with optional per-container proxy configuration.
     * When proxyIp/proxyPort are non-null the container env is injected with
     * http_proxy / https_proxy env vars and ANDROID_PROXY_* settings.
     *
     * @param deviceId  unique device identifier
     * @param brand     device brand label
     * @param adbPort   preferred ADB host port (auto-allocated if busy)
     * @param proxyIp   proxy server IP/hostname, or null to skip
     * @param proxyPort proxy server port, or null to skip
     * @param proxyType proxy protocol type ("http" or "socks5"), or null to skip
     * @return CompletableFuture yielding the DeviceDTO with runtime state
     */
    public CompletableFuture<DeviceDTO> createPhoneContainer(String deviceId, String brand, int adbPort,
                                                              String proxyIp, Integer proxyPort, String proxyType) {
        return CompletableFuture.supplyAsync(() -> {
            int finalPort = allocatePort(adbPort);
            // Register immediately so WS broadcasts show this device as CREATING
            DeviceDTO placeholder = new DeviceDTO(deviceId, finalPort, null, brand);
            placeholder.setStatus("CREATING");
            deviceRegistry.put(finalPort, placeholder);
            String containerName = "phantom-" + deviceId;

            Ports portBindings = new Ports();
            portBindings.bind(ExposedPort.tcp(5555), Ports.Binding.bindPort(finalPort));

            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withPortBindings(portBindings)
                    .withBinds(new Bind("/dev/binderfs", new Volume("/dev/binderfs")))
                    .withCpuCount((long) props.getContainer().getCpuCount())
                    .withMemory(props.getContainer().getMemoryMb() * 1024 * 1024L)
                    .withMemorySwap(0L)
                    .withPrivileged(true)
                    .withPidsLimit(1024L);

            List<String> env = new ArrayList<>(Arrays.asList(
                    "ANDROID_ADB_KEY=enabled",
                    "redroid.gpu.mode=guest",
                    "ro.droid.ime=1",
                    "ro.droid.ssh=0"
            ));

            // Inject per-container proxy environment variables
            if (proxyIp != null && !proxyIp.isBlank() && proxyPort != null && proxyPort > 0) {
                String finalType = (proxyType != null && !proxyType.isBlank()) ? proxyType.toLowerCase() : "http";
                if ("socks5".equals(finalType)) {
                    env.add("http_proxy=socks5://" + proxyIp + ":" + proxyPort);
                    env.add("https_proxy=socks5://" + proxyIp + ":" + proxyPort);
                } else {
                    env.add("http_proxy=http://" + proxyIp + ":" + proxyPort);
                    env.add("https_proxy=http://" + proxyIp + ":" + proxyPort);
                }
                env.add("ANDROID_PROXY_IP=" + proxyIp);
                env.add("ANDROID_PROXY_PORT=" + proxyPort);
                env.add("ANDROID_PROXY_TYPE=" + finalType);
                log.info("Container {} will use proxy {}://{}:{}", containerName, finalType, proxyIp, proxyPort);
            }

            try {
                CreateContainerResponse response = dockerClient.createContainerCmd(REDROID_IMAGE)
                        .withName(containerName)
                        .withHostConfig(hostConfig)
                        .withEnv(env)
                        .withTty(true)
                        .exec();

                String containerId = response.getId();
                dockerClient.startContainerCmd(containerId).exec();
                log.info("Container {} started on ADB port {}", containerId, finalPort);

                waitForAdb(finalPort);

                DeviceDTO dto = new DeviceDTO(deviceId, finalPort, containerId, brand);
                dto.setStatus("RUNNING");
                deviceRegistry.put(finalPort, dto);
                return dto;

            } catch (Exception e) {
                log.error("Failed to create container for device {}: {}", deviceId, e.getMessage());
                DeviceDTO dto = new DeviceDTO(deviceId, finalPort, null, brand);
                dto.setStatus("ERROR");
                return dto;
            }
        }, virtualExecutor);
    }

    public CompletableFuture<Void> setPhoneLocation(int adbPort, double lat, double lng) {
        return CompletableFuture.runAsync(() -> {
            DeviceDTO dto = deviceRegistry.get(adbPort);
            if (dto == null) throw new RuntimeException("Device not found on port " + adbPort);

            try {
                execAdb(adbPort, "shell", "settings", "put", "global", "mock_location", "1");
                execAdb(adbPort, "shell", "appops", "set", "com.android.systemui", "android:mock_location", "allow");
                execAdb(adbPort, "shell", "geo", "fix", String.valueOf(lat), String.valueOf(lng));
                execAdb(adbPort, "shell", "am", "broadcast",
                        "-a", "android.location.PROVIDERS_CHANGED",
                        "-n", "com.android.settings/.widget.SettingsAppWidgetProvider");
                execAdb(adbPort, "shell", "content", "insert",
                        "--uri", "content://settings/global",
                        "--bind", "name:s:mock_location",
                        "--bind", "value:s:1");

                dto.setLatitude(lat);
                dto.setLongitude(lng);
                log.debug("Location set on port {}: {},{}", adbPort, lat, lng);
            } catch (Exception e) {
                throw new RuntimeException("Failed to set location on port " + adbPort + ": " + e.getMessage(), e);
            }
        }, virtualExecutor);
    }

    public CompletableFuture<Void> spoofDeviceFingerprint(int adbPort) {
        return CompletableFuture.runAsync(() -> {
            DeviceDTO dto = deviceRegistry.get(adbPort);
            if (dto == null) throw new RuntimeException("Device not found on port " + adbPort);

            try {
                FingerprintGenerator.Fingerprint fp = FingerprintGenerator.generate();
                dto.setBrand(fp.getBrand());
                dto.setModel(fp.getModel());
                dto.setAndroidId(fp.getAndroidId());
                dto.setImei(fp.getImei());

                execAdb(adbPort, "shell", "setprop", "ro.product.brand", fp.getBrand());
                execAdb(adbPort, "shell", "setprop", "ro.product.manufacturer", fp.getManufacturer());
                execAdb(adbPort, "shell", "setprop", "ro.product.model", fp.getModel());
                execAdb(adbPort, "shell", "setprop", "ro.product.name", fp.getProduct());
                execAdb(adbPort, "shell", "setprop", "ro.product.device", fp.getDevice());
                execAdb(adbPort, "shell", "setprop", "ro.build.fingerprint", fp.getBuildFingerprint());
                execAdb(adbPort, "shell", "setprop", "ro.serialno", fp.getSerial());
                execAdb(adbPort, "shell", "settings", "put", "secure", "android_id", fp.getAndroidId());
                execAdb(adbPort, "shell", "setprop", "gsm.sim.operator.iso-country", fp.getSimCountry());
                execAdb(adbPort, "shell", "setprop", "gsm.sim.operator.numeric", fp.getSimOperator());
                execAdb(adbPort, "shell", "setprop", "ro.boot.wifimac", fp.getWifiMac());
                execAdb(adbPort, "shell", "setprop", "wifi.interface", "wlan0");
                execAdb(adbPort, "shell", "dumpsys", "battery", "set", "level", String.valueOf(fp.getBatteryLevel()));
                execAdb(adbPort, "shell", "dumpsys", "battery", "set", "status", "2");

                log.info("Fingerprint spoofed on port {}: {} {} / {}", adbPort, fp.getBrand(), fp.getModel(), fp.getImei());
            } catch (Exception e) {
                throw new RuntimeException("Failed to spoof fingerprint on port " + adbPort + ": " + e.getMessage(), e);
            }
        }, virtualExecutor);
    }

    public CompletableFuture<Void> destroyContainer(int adbPort) {
        return CompletableFuture.runAsync(() -> {
            DeviceDTO dto = deviceRegistry.remove(adbPort);
            if (dto != null && dto.getContainerId() != null) {
                try {
                    dockerClient.killContainerCmd(dto.getContainerId()).exec();
                    dockerClient.removeContainerCmd(dto.getContainerId()).exec();
                    releasePort(adbPort);
                    log.info("Container destroyed: port {}", adbPort);
                } catch (Exception e) {
                    log.warn("Failed to destroy container on port {}: {}", adbPort, e.getMessage());
                }
            }
        }, virtualExecutor);
    }

    public CompletableFuture<Void> destroyAll() {
        List<Integer> ports = new ArrayList<>(deviceRegistry.keySet());
        List<CompletableFuture<Void>> futures = ports.stream()
                .map(this::destroyContainer)
                .collect(Collectors.toList());
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    public List<DeviceDTO> getAllDevices() {
        return deviceRegistry.values().stream()
                .peek(d -> {
                    if (d.getCreatedAt() != null) {
                        d.setUptimeSeconds(
                                Duration.between(d.getCreatedAt(), Instant.now()).getSeconds());
                    }
                })
                .collect(Collectors.toList());
    }

    /**
     * Download APK from URL and install silently on target device.
     * APK is saved to /tmp/ on the host then pushed via adb install -r.
     */
    public CompletableFuture<Void> installApp(int adbPort, String apkUrl) {
        return CompletableFuture.runAsync(() -> {
            DeviceDTO dto = deviceRegistry.get(adbPort);
            if (dto == null) throw new RuntimeException("Device not found on port " + adbPort);

            try {
                // Download APK to temp file
                String tmpPath = "/tmp/apk_" + adbPort + "_" + System.currentTimeMillis() + ".apk";
                ProcessBuilder downloadPb = new ProcessBuilder(
                        "curl", "-sL", "-o", tmpPath, "--connect-timeout", "30", apkUrl);
                downloadPb.redirectErrorStream(true);
                Process downloadP = downloadPb.start();
                if (!downloadP.waitFor(60, TimeUnit.SECONDS)) {
                    downloadP.destroyForcibly();
                    throw new RuntimeException("APK download timed out");
                }
                if (downloadP.exitValue() != 0) {
                    throw new RuntimeException("APK download failed, exit code " + downloadP.exitValue());
                }

                // Install via adb
                String result = execAdb(adbPort, "install", "-r", "-t", "--no-incremental", tmpPath);
                log.info("App installed on port {}: {}", adbPort, apkUrl);
                log.debug("Install result: {}", result);

                // Clean up
                new java.io.File(tmpPath).delete();

            } catch (Exception e) {
                throw new RuntimeException("Failed to install app on port " + adbPort + ": " + e.getMessage(), e);
            }
        }, virtualExecutor);
    }

    /**
     * Start scrcpy video stream for a device.
     */
    public CompletableFuture<InputStream> startScrcpyProxy(int adbPort) {
        return ScrcpyStreamUtil.startStream(adbPort);
    }

    /**
     * Stop scrcpy video stream for a device.
     */
    public void stopScrcpyProxy(int adbPort) {
        ScrcpyStreamUtil.stopStream(adbPort);
    }

    /**
     * Register a device in the registry (used by Controller for immediate CREATING status).
     */
    public void registerDevice(DeviceDTO dto) {
        deviceRegistry.put(dto.getAdbPort(), dto);
    }

    public int getDeviceCount() { return deviceRegistry.size(); }

    public CompletableFuture<Boolean> checkAdbHealth(int adbPort) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String out = execAdb(adbPort, "shell", "echo", "pong");
                return out != null && out.contains("pong");
            } catch (Exception e) {
                return false;
            }
        }, virtualExecutor);
    }

    // ============================================================
    //  INTERNAL HELPERS
    // ============================================================

    private void waitForAdb(int port) {
        // First-time Redroid boot can take 60+ seconds
        long deadline = System.currentTimeMillis() + Math.max(120000, props.getContainer().getAdbTimeoutMs());
        int retries = 0;

        // Step 1: verify port is reachable (container ADB may still be starting)
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2000);
                s.close();
                break;
            } catch (Exception ignored) {
                retries++;
                try { Thread.sleep(props.getContainer().getAdbPollIntervalMs()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }

        // Step 2: establish ADB connection
        try {
            new ProcessBuilder("adb", "connect", "127.0.0.1:" + port)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {}

        // Step 3: run shell command to confirm readiness
        while (System.currentTimeMillis() < deadline) {
            try {
                String out = execAdb(port, "shell", "echo", "ready");
                if (out != null && out.contains("ready")) {
                    log.debug("ADB ready on port {} after {} retries", port, retries);
                    return;
                }
            } catch (Exception ignored) {}
            retries++;
            try {
                Thread.sleep(props.getContainer().getAdbPollIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("ADB wait interrupted on port " + port);
            }
        }
        throw new RuntimeException("ADB not ready on port " + port + " after " +
                (props.getContainer().getAdbTimeoutMs() / 1000) + "s");
    }

    private String execAdb(int port, String... args) {
        // Ensure adb server is running (first call may start it)
        try {
            new ProcessBuilder("adb", "start-server")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {}

        List<String> cmd = new ArrayList<>();
        cmd.add("adb");
        cmd.add("-s");
        cmd.add("127.0.0.1:" + port);
        cmd.addAll(Arrays.asList(args));

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(props.getContainer().getAdbTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new RuntimeException("ADB command timed out: " + String.join(" ", cmd));
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            throw new RuntimeException("ADB execution failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("ADB interrupted", e);
        }
    }

    private synchronized int allocatePort(int preferred) {
        int port = preferred;
        while (port <= props.getContainer().getAdbPortEnd() && usedPorts.contains(port)) {
            port++;
        }
        if (port > props.getContainer().getAdbPortEnd()) {
            throw new RuntimeException("No available ADB ports in range " +
                    props.getContainer().getAdbPortStart() + "-" + props.getContainer().getAdbPortEnd());
        }
        usedPorts.add(port);
        return port;
    }

    private synchronized void releasePort(int port) {
        usedPorts.remove(port);
    }

    private void reapIdleContainers() {
        for (DeviceDTO dto : List.copyOf(deviceRegistry.values())) {
            if (dto.getCreatedAt() != null && "RUNNING".equals(dto.getStatus())) {
                long ageMinutes = Duration.between(dto.getCreatedAt(), Instant.now()).toMinutes();
                if (ageMinutes >= props.getContainer().getIdleTtlMinutes()) {
                    log.info("Reaping idle container on port {} (age={}m)", dto.getAdbPort(), ageMinutes);
                    destroyContainer(dto.getAdbPort());
                }
            }
        }
    }
}
