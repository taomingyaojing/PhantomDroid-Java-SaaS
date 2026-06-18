package com.phantomdroid.util;

import com.phantomdroid.constant.ScrcpyConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Manages scrcpy-server lifecycle per device via ADB.
 * Handles: push server jar, start/stop stream, read H.264 frames from ADB forward.
 * Pure CPU no-GPU -- uses software encoding on device side.
 */
public class ScrcpyStreamUtil {

    private static final Logger log = LoggerFactory.getLogger(ScrcpyStreamUtil.class);

    private static final AdbSocketUtil adbSocket = new AdbSocketUtil();

    /** Per-device stream state: ADB port -> ScrcpyProcessHandle */
    private static final Map<Integer, ScrcpyProcessHandle> activeStreams = new ConcurrentHashMap<>();

    private static final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private ScrcpyStreamUtil() {}

    /**
     * Start scrcpy stream for a device by its ADB port.
     * Returns an InputStream of raw H.264 data read from ADB forward tunnel.
     */
    public static CompletableFuture<InputStream> startStream(int adbPort) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Kill any existing stream on this port
                stopStream(adbPort);

                int scrcpyPort = ScrcpyConstants.SCRCPY_PORT_START + (adbPort % 1000);

                // Step 1: Push scrcpy-server to device
                ProcessBuilder pushPb = new ProcessBuilder(
                        "adb", "-s", "127.0.0.1:" + adbPort,
                        "push", ScrcpyConstants.SCRCPY_SERVER_HOST_PATH,
                        ScrcpyConstants.SCRCPY_SERVER_DEVICE_PATH);
                pushPb.redirectErrorStream(true);
                Process pushP = pushPb.start();
                if (!pushP.waitFor(15, TimeUnit.SECONDS)) {
                    pushP.destroyForcibly();
                    throw new RuntimeException("ADB push scrcpy-server timed out");
                }

                // Step 2: Setup ADB reverse tunnel for scrcpy
                ProcessBuilder reversePb = new ProcessBuilder(
                        "adb", "-s", "127.0.0.1:" + adbPort,
                        "reverse", "localabstract:scrcpy",
                        "tcp:" + scrcpyPort);
                reversePb.redirectErrorStream(true);
                reversePb.start().waitFor(5, TimeUnit.SECONDS);

                // Step 3: Launch scrcpy-server on device
                String serverCmd = String.format(
                        "CLASSPATH=%s app_process / com.genymobile.scrcpy.Server 3.3.4 " +
                        "max_size=%d bit_rate=%d max_fps=%d i_frame_interval=%d " +
                        "forward=true",
                        ScrcpyConstants.SCRCPY_SERVER_DEVICE_PATH,
                        ScrcpyConstants.MAX_WIDTH,
                        ScrcpyConstants.MAX_BITRATE_BPS,
                        ScrcpyConstants.TARGET_FPS,
                        ScrcpyConstants.I_FRAME_INTERVAL);

                ProcessBuilder serverPb = new ProcessBuilder(
                        "adb", "-s", "127.0.0.1:" + adbPort,
                        "shell", serverCmd);
                serverPb.redirectErrorStream(false);
                Process serverProcess = serverPb.start();

                // Step 4: Setup local ADB forward to receive stream
                ProcessBuilder forwardPb = new ProcessBuilder(
                        "adb", "-s", "127.0.0.1:" + adbPort,
                        "forward", "tcp:" + scrcpyPort,
                        "localabstract:scrcpy");
                forwardPb.redirectErrorStream(true);
                forwardPb.start().waitFor(5, TimeUnit.SECONDS);

                // Step 5: Connect to local forward port for raw H.264 data
                // Use ADB forward instead: connect via socket to the forwarded port
                java.net.Socket socket = new java.net.Socket("127.0.0.1", scrcpyPort);
                socket.setSoTimeout((int) ScrcpyConstants.STREAM_IDLE_TIMEOUT_MS);
                InputStream streamInput = socket.getInputStream();

                // Register handle for lifecycle management
                ScrcpyProcessHandle handle = new ScrcpyProcessHandle(
                        adbPort, scrcpyPort, serverProcess, socket, streamInput);
                activeStreams.put(adbPort, handle);

                log.info("Scrcpy stream started on ADB port {} -> localhost:{}", adbPort, scrcpyPort);
                return streamInput;

            } catch (Exception e) {
                throw new RuntimeException("Failed to start scrcpy stream on port " + adbPort + ": " + e.getMessage(), e);
            }
        }, streamExecutor);
    }

    /**
     * Stop scrcpy stream for a device.
     */
    public static void stopStream(int adbPort) {
        ScrcpyProcessHandle handle = activeStreams.remove(adbPort);
        if (handle == null) return;

        try {
            // Kill scrcpy-server process
            if (handle.serverProcess.isAlive()) {
                handle.serverProcess.destroyForcibly();
                handle.serverProcess.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.warn("Error killing scrcpy-server on port {}: {}", adbPort, e.getMessage());
        }

        try { handle.socket.close(); } catch (Exception ignored) {}
        try { handle.inputStream.close(); } catch (Exception ignored) {}

        // Clean ADB forward
        try {
            new ProcessBuilder("adb", "-s", "127.0.0.1:" + adbPort,
                    "forward", "--remove", "tcp:" + handle.scrcpyPort)
                    .start().waitFor(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {}

        log.info("Scrcpy stream stopped on ADB port {}", adbPort);
    }

    /**
     * Stop all active scrcpy streams.
     */
    public static void stopAll() {
        for (int port : activeStreams.keySet()) {
            stopStream(port);
        }
    }

    /**
     * Check if a stream is active for the given port.
     */
    public static boolean isStreamActive(int adbPort) {
        ScrcpyProcessHandle h = activeStreams.get(adbPort);
        return h != null && h.serverProcess.isAlive() && h.socket.isConnected();
    }

    public static int getActiveStreamCount() { return activeStreams.size(); }

    /**
     * Send a touch event to the device via ADB socket.
     * Uses AdbSocketUtil instead of ProcessBuilder subprocess.
     */
    public static void sendTouchEvent(int adbPort, int action, float x, float y, float pressure, int width, int height) {
        try {
            int xi = (int) x;
            int yi = (int) y;
            switch (action) {
                case ScrcpyConstants.TOUCH_ACTION_DOWN -> {
                    // Simulate tap at position
                    adbSocket.sendTap(adbPort, xi, yi);
                }
                case ScrcpyConstants.TOUCH_ACTION_MOVE -> {
                    // Use swipe with short distance for drag
                    adbSocket.sendSwipe(adbPort, xi, yi, xi, yi, 50);
                }
                case ScrcpyConstants.TOUCH_ACTION_UP -> {
                    // Dummy, release is automatic; no action needed
                }
                default -> { /* unknown action */ }
            }
        } catch (Exception e) {
            log.warn("Touch event failed on port {}: {}", adbPort, e.getMessage());
        }
    }

    /**
     * Send a key event to the device via ADB socket.
     * Uses AdbSocketUtil instead of ProcessBuilder subprocess.
     */
    public static void sendKeyEvent(int adbPort, int keycode) {
        try {
            adbSocket.sendKeyEvent(adbPort, keycode);
        } catch (Exception e) {
            log.warn("Key event failed on port {}: {}", adbPort, e.getMessage());
        }
    }

    /**
     * Internal handle for a scrcpy stream.
     */
    private static class ScrcpyProcessHandle {
        final int adbPort;
        final int scrcpyPort;
        final Process serverProcess;
        final java.net.Socket socket;
        final InputStream inputStream;

        ScrcpyProcessHandle(int adbPort, int scrcpyPort, Process serverProcess,
                            java.net.Socket socket, InputStream inputStream) {
            this.adbPort = adbPort;
            this.scrcpyPort = scrcpyPort;
            this.serverProcess = serverProcess;
            this.socket = socket;
            this.inputStream = inputStream;
        }
    }
}
