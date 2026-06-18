package com.phantomdroid.controller;

import com.phantomdroid.dto.*;
import com.phantomdroid.entity.Device;
import com.phantomdroid.entity.User;
import com.phantomdroid.manager.DockerContainerManager;
import com.phantomdroid.repository.DeviceRepository;
import com.phantomdroid.repository.UserRepository;
import com.phantomdroid.util.UserContext;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * RESTful endpoints for cloud phone device lifecycle management.
 * All endpoints enforce multi-tenant ownership via UserContext (populated by JwtFilter).
 */
@RestController
@RequestMapping("/api/device")
public class DeviceController {

    private static final Logger log = LoggerFactory.getLogger(DeviceController.class);

    private final DockerContainerManager containerManager;
    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final AtomicInteger deviceIdCounter = new AtomicInteger(1);

    public DeviceController(DockerContainerManager containerManager,
                            DeviceRepository deviceRepository,
                            UserRepository userRepository) {
        this.containerManager = containerManager;
        this.deviceRepository = deviceRepository;
        this.userRepository = userRepository;
    }

    /**
     * Batch launch cloud phone containers.
     * POST /api/device/launch
     */
    @PostMapping("/launch")
    public ApiResponse<List<DeviceDTO>> batchLaunch(@Valid @RequestBody BatchLaunchDTO dto) {
        Long userId = UserContext.getCurrentUserId();
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new SecurityException("Authenticated user not found in database"));

        log.info("Batch launch by user {}: count={}, brand={}, startPort={}",
                userId, dto.getCount(), dto.getBrand(), dto.getStartAdbPort());

        List<DeviceDTO> result = new ArrayList<>();
        for (int i = 0; i < dto.getCount(); i++) {
            String deviceId = "device-" + dto.getBrand().toLowerCase().replaceAll("\\s+", "-")
                    + "-" + deviceIdCounter.getAndIncrement();
            int port = dto.getStartAdbPort() + i;

            // Persist device record immediately (CREATING status)
            Device device = new Device();
            device.setDeviceId(deviceId);
            device.setAdbPort(port);
            device.setStatus("CREATING");
            device.setBrand(dto.getBrand());
            device.setOwner(owner);
            device = deviceRepository.save(device);

            // Register in runtime registry
            DeviceDTO d = new DeviceDTO(deviceId, port, null, dto.getBrand());
            d.setStatus("CREATING");
            containerManager.registerDevice(d);

            result.add(d);

            // Async container creation
            final Device finalDevice = device;
            containerManager.createPhoneContainer(deviceId, dto.getBrand(), port)
                    .thenAccept(dev -> {
                        log.debug("Container {} creation finished: {}", deviceId, dev.getStatus());
                        // Update DB status
                        finalDevice.setStatus(dev.getStatus());
                        finalDevice.setContainerId(dev.getContainerId());
                        deviceRepository.save(finalDevice);
                    });
        }

        return ApiResponse.success(result);
    }

    /**
     * Batch modify device parameters (location &amp; fingerprint).
     * POST /api/device/modify
     */
    @PostMapping("/modify")
    public CompletableFuture<ApiResponse<Map<String, Object>>> batchModify(@Valid @RequestBody DeviceModifyDTO dto) {
        Long userId = UserContext.getCurrentUserId();
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new SecurityException("Authenticated user not found"));

        log.info("Batch modify by user {}: ports={}, loc={}/{}, fp={}",
                userId, dto.getAdbPorts(), dto.getLatitude(), dto.getLongitude(), dto.isEnableFingerprintOverride());

        List<CompletableFuture<Void>> tasks = new ArrayList<>();

        for (int port : dto.getAdbPorts()) {
            // Ownership verification
            if (!deviceRepository.existsByAdbPortAndOwner(port, owner)) {
                throw new SecurityException("Device on port " + port + " does not belong to current user");
            }

            if (dto.isEnableLocationOverride() && dto.getLatitude() != null && dto.getLongitude() != null) {
                tasks.add(containerManager.setPhoneLocation(port, dto.getLatitude(), dto.getLongitude()));
            }
            if (dto.isEnableFingerprintOverride()) {
                tasks.add(containerManager.spoofDeviceFingerprint(port));
            }
        }

        return CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("affectedPorts", dto.getAdbPorts());
                    result.put("locationApplied", dto.isEnableLocationOverride());
                    result.put("fingerprintApplied", dto.isEnableFingerprintOverride());
                    result.put("taskCount", tasks.size());
                    return ApiResponse.success(result);
                });
    }

    /**
     * Get current user's devices.
     * GET /api/device/list
     * Scoped to the authenticated user — no other user's devices are visible.
     */
    @GetMapping("/list")
    public ApiResponse<List<DeviceDTO>> listDevices() {
        Long userId = UserContext.getCurrentUserId();
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new SecurityException("Authenticated user not found"));

        List<Device> devices = deviceRepository.findByOwner(owner);

        List<DeviceDTO> dtos = devices.stream()
                .map(d -> {
                    DeviceDTO dto = new DeviceDTO(d.getDeviceId(), d.getAdbPort(), d.getContainerId(), d.getBrand());
                    dto.setStatus(d.getStatus());
                    dto.setModel(d.getModel());
                    dto.setLatitude(d.getLatitude());
                    dto.setLongitude(d.getLongitude());
                    dto.setAndroidId(d.getAndroidId());
                    dto.setImei(d.getImei());
                    dto.setStreaming(d.isStreaming());
                    if (d.getCreatedAt() != null) {
                        dto.setCreatedAt(d.getCreatedAt());
                        dto.setUptimeSeconds(Duration.between(d.getCreatedAt(), Instant.now()).getSeconds());
                    }
                    return dto;
                })
                .collect(Collectors.toList());

        return ApiResponse.success(dtos);
    }

    /**
     * Get server status summary (multi-tenant safe).
     * GET /api/device/status
     */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> serverStatus() {
        Long userId = UserContext.getCurrentUserId();
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new SecurityException("Authenticated user not found"));

        List<Device> userDevices = deviceRepository.findByOwner(owner);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("totalContainers", userDevices.size());
        status.put("maxCapacityHint", "8c16G server: ~120 containers (1c/1.5G each)");
        long runningCount = userDevices.stream()
                .filter(d -> "RUNNING".equals(d.getStatus()))
                .count();
        status.put("runningCount", runningCount);
        return ApiResponse.success(status);
    }

    /**
     * Install APK on target devices.
     * POST /api/device/install-app
     */
    @PostMapping("/install-app")
    public CompletableFuture<ApiResponse<Map<String, Object>>> installApp(@Valid @RequestBody AppInstallDTO dto) {
        Long userId = UserContext.getCurrentUserId();
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new SecurityException("Authenticated user not found"));

        log.info("App install by user {}: ports={}, apk={}", userId, dto.getAdbPorts(), dto.getApkUrl());

        // Verify ownership for ALL ports before proceeding
        for (int port : dto.getAdbPorts()) {
            if (!deviceRepository.existsByAdbPortAndOwner(port, owner)) {
                throw new SecurityException("Device on port " + port + " does not belong to current user");
            }
        }

        List<CompletableFuture<Void>> tasks = dto.getAdbPorts().stream()
                .map(port -> containerManager.installApp(port, dto.getApkUrl()))
                .collect(Collectors.toList());

        return CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("affectedPorts", dto.getAdbPorts());
                    result.put("apkUrl", dto.getApkUrl());
                    result.put("installed", tasks.size());
                    return ApiResponse.success(result);
                });
    }

    /**
     * Start scrcpy stream for a device.
     * POST /api/device/start-stream/{adbPort}
     */
    @PostMapping("/start-stream/{adbPort}")
    public ApiResponse<Map<String, Object>> startStream(@PathVariable int adbPort) {
        verifyOwnership(adbPort);
        containerManager.startScrcpyProxy(adbPort);
        Map<String, Object> res = new HashMap<>();
        res.put("adbPort", adbPort);
        res.put("streaming", true);
        return ApiResponse.success(res);
    }

    /**
     * Stop scrcpy stream for a device.
     * POST /api/device/stop-stream/{adbPort}
     */
    @PostMapping("/stop-stream/{adbPort}")
    public ApiResponse<Map<String, Object>> stopStream(@PathVariable int adbPort) {
        verifyOwnership(adbPort);
        containerManager.stopScrcpyProxy(adbPort);
        Map<String, Object> res = new HashMap<>();
        res.put("adbPort", adbPort);
        res.put("streaming", false);
        return ApiResponse.success(res);
    }

    /**
     * Destroy a single container by ADB port.
     * DELETE /api/device/{adbPort}
     */
    @DeleteMapping("/{adbPort}")
    public CompletableFuture<ApiResponse<String>> destroyDevice(@PathVariable int adbPort) {
        verifyOwnership(adbPort);
        return containerManager.destroyContainer(adbPort)
                .thenApply(v -> {
                    // Remove from DB
                    deviceRepository.findByAdbPort(adbPort)
                            .ifPresent(deviceRepository::delete);
                    return ApiResponse.success("Container on port " + adbPort + " destroyed");
                });
    }

    /**
     * Destroy all containers owned by current user.
     * DELETE /api/device/destroy-all
     */
    @DeleteMapping("/destroy-all")
    public CompletableFuture<ApiResponse<String>> destroyAll() {
        Long userId = UserContext.getCurrentUserId();
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new SecurityException("Authenticated user not found"));

        List<Device> ownedDevices = deviceRepository.findByOwner(owner);
        List<CompletableFuture<Void>> futures = ownedDevices.stream()
                .map(d -> containerManager.destroyContainer(d.getAdbPort()))
                .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    deviceRepository.deleteByOwner(owner);
                    return ApiResponse.success("All containers destroyed");
                });
    }

    // ============================================================
    //  INTERNAL
    // ============================================================

    /**
     * Verify that the ADB port device belongs to the authenticated user.
     * Throws SecurityException (caught by GlobalAsyncExceptionHandler -> 403).
     */
    private void verifyOwnership(int adbPort) {
        Long userId = UserContext.getCurrentUserId();
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new SecurityException("Authenticated user not found"));

        if (!deviceRepository.existsByAdbPortAndOwner(adbPort, owner)) {
            throw new SecurityException("Forbidden: device on port " + adbPort
                    + " does not belong to current user");
        }
    }
}
