package com.phantomdroid.util;

import org.springframework.stereotype.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ADB binary protocol client over raw Socket connection to local ADB server (127.0.0.1:5037).
 * <p>
 * Eliminates all ProcessBuilder("adb shell ...") subprocess overhead by speaking
 * the ADB protocol directly on port 5037. A connection pool maps each device
 * (identified by its ADB port) to a persistent multiplexed socket. The pool is
 * thread-safe, auto-reconnects on failure, and supports graceful shutdown via
 * {@link Closeable}.
 * <p>
 * Protocol reference (all length prefixes are 4 hex lowercase ASCII chars):
 * <pre>
 *   CLIENT → SERVER:  [4-hex-len][payload]
 *   SERVER → CLIENT:  "OKAY" or "FAIL"
 *   SERVER → DATA:    [4-hex-len][payload]  (for shell: commands)
 *   SERVER → END:     "CLSE"
 * </pre>
 */
@Component
public class AdbSocketUtil implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(AdbSocketUtil.class);

    /** Default ADB server host (always local). */
    private static final String ADB_SERVER_HOST = "127.0.0.1";

    /** ADB server default port. */
    private static final int ADB_SERVER_PORT = 5037;

    /** Socket connect / read timeout in milliseconds. */
    private static final int SOCKET_TIMEOUT_MS = 30_000;

    /** Maximum bytes to read for any single ADB shell response. */
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    /** Pool of persistent ADB connections keyed by device port. */
    private static final ConcurrentHashMap<Integer, AdbConnection> connectionPool = new ConcurrentHashMap<>();

    /** Shared virtual-thread executor for CompletableFuture async methods. */
    private static final ExecutorService asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Wraps a Socket plus its streams for one device's ADB session.
     */
    private static class AdbConnection {
        final Socket socket;
        final InputStream input;
        final OutputStream output;
        final int devicePort;

        AdbConnection(int devicePort) throws IOException {
            this.devicePort = devicePort;
            this.socket = new Socket();
            this.socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            this.socket.setTcpNoDelay(true);
            this.socket.connect(new InetSocketAddress(ADB_SERVER_HOST, ADB_SERVER_PORT), SOCKET_TIMEOUT_MS);
            this.input = socket.getInputStream();
            this.output = socket.getOutputStream();
            // Bind to the target device via host:transport
            sendAndCheck("host:transport:127.0.0.1:" + devicePort);
            log.debug("ADB connection established for device 127.0.0.1:{}", devicePort);
        }

        /** Returns true if the underlying socket is healthy. */
        boolean isAlive() {
            return socket.isConnected() && !socket.isClosed() && !socket.isInputShutdown();
        }

        /** Gracefully close the socket. */
        void close() {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // ---------------------------------------------------------------
    //  Connection pool management
    // ---------------------------------------------------------------

    /**
     * Obtain (or reuse) a persistent ADB connection for the given device port.
     */
    private static AdbConnection getConnection(int adbPort) {
        AdbConnection conn = connectionPool.get(adbPort);
        if (conn != null && conn.isAlive()) {
            return conn;
        }
        // Stale connection – remove and rebuild
        if (conn != null) {
            connectionPool.remove(adbPort);
            conn.close();
        }
        try {
            AdbConnection newConn = new AdbConnection(adbPort);
            connectionPool.put(adbPort, newConn);
            return newConn;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create ADB connection for device " + adbPort + ": " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------
    //  ADB protocol primitives
    // ---------------------------------------------------------------

    /**
     * Encode payload length as a 4-character lowercase hex string as required by ADB protocol.
     */
    private static byte[] encodeLength(int length) {
        return String.format("%04x", length).getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Send a raw ADB command and expect "OKAY" response.
     *
     * @param conn   the ADB connection
     * @param payload the command payload (e.g. "host:transport:127.0.0.1:5555")
     */
    private static void sendAndCheck(AdbConnection conn, String payload) throws IOException {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        conn.output.write(encodeLength(payloadBytes.length));
        conn.output.write(payloadBytes);
        conn.output.flush();
        // Read 4-byte response ("OKAY" or "FAIL")
        byte[] resp = readExactly(conn.input, 4);
        String status = new String(resp, StandardCharsets.US_ASCII);
        if (!"OKAY".equals(status)) {
            throw new IOException("ADB command rejected [" + status + "] for: " + payload);
        }
    }

    /**
     * Convenience overload for the static-style code path where we already have
     * the connection object.
     */
    private static void sendAndCheck(String payload) {
        // Not used directly; each method gets its own conn via getConnection.
        throw new UnsupportedOperationException("Use sendAndCheck(AdbConnection, String)");
    }

    /**
     * Send a command to the device's shell (via host:transport first, then shell:).
     *
     * @param conn   the ADB connection
     * @param command the shell command, e.g. "input tap 100 200"
     * @return stdout of the shell command
     */
    private static String shellSync(AdbConnection conn, String command) throws IOException {
        String shellPayload = "shell:" + command;
        sendAndCheck(conn, shellPayload);
        // Read chunked data: each chunk is 4-hex-len + data, terminated by "CLSE"
        StringBuilder sb = new StringBuilder();
        while (true) {
            byte[] header = readExactly(conn.input, 4);
            String tag = new String(header, StandardCharsets.US_ASCII);
            if ("CLSE".equals(tag)) {
                break; // End of response
            }
            if ("OKAY".equals(tag)) {
                // ADB protocol: after shell: init, we get "OKAY" then data chunks
                continue;
            }
            if ("FAIL".equals(tag)) {
                byte[] failLenBytes = readExactly(conn.input, 4);
                int failLen = Integer.parseInt(new String(failLenBytes, StandardCharsets.US_ASCII), 16);
                byte[] failMsg = readExactly(conn.input, Math.min(failLen, MAX_RESPONSE_BYTES));
                throw new IOException("ADB shell FAIL: " + new String(failMsg, StandardCharsets.UTF_8));
            }
            // It's a data length prefix (hex string)
            try {
                int chunkLen = Integer.parseInt(tag, 16);
                if (chunkLen <= 0) continue;
                int toRead = Math.min(chunkLen, MAX_RESPONSE_BYTES - sb.length());
                if (toRead <= 0) break;
                byte[] chunk = readExactly(conn.input, toRead);
                sb.append(new String(chunk, StandardCharsets.UTF_8));
                // Skip any remaining bytes we decided not to read
                if (toRead < chunkLen) {
                    skipExactly(conn.input, chunkLen - toRead);
                }
            } catch (NumberFormatException e) {
                throw new IOException("Unexpected ADB protocol tag: " + tag);
            }
        }
        return sb.toString();
    }

    /**
     * Read exactly {@code len} bytes from the input stream, blocking until full or error.
     */
    private static byte[] readExactly(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int read = in.read(buf, off, len - off);
            if (read < 0) {
                throw new IOException("ADB connection closed unexpectedly (read " + off + "/" + len + " bytes)");
            }
            off += read;
        }
        return buf;
    }

    /**
     * Skip exactly {@code len} bytes from the input stream.
     */
    private static void skipExactly(InputStream in, long len) throws IOException {
        long remaining = len;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                // skip() may return 0; try reading one byte to advance
                if (in.read() < 0) {
                    throw new IOException("Stream ended while skipping " + remaining + " bytes");
                }
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    // ---------------------------------------------------------------
    //  Public API — convenience methods for common ADB shell commands
    // ---------------------------------------------------------------

    /**
     * Send a tap (touch down + up) at the given screen coordinates.
     */
    public static void sendTap(int adbPort, int x, int y) {
        executeShell(adbPort, "input tap " + x + " " + y);
    }

    /**
     * Send a swipe gesture (down → move → up) across screen coordinates.
     *
     * @param adbPort    device ADB port
     * @param x1         start x
     * @param y1         start y
     * @param x2         end x
     * @param y2         end y
     * @param durationMs gesture duration in milliseconds
     */
    public static void sendSwipe(int adbPort, int x1, int y1, int x2, int y2, long durationMs) {
        executeShell(adbPort, "input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + durationMs);
    }

    /**
     * Inject text via ADB shell input text.
     * Special characters in the text are escaped for shell safety.
     */
    public static void sendText(int adbPort, String text) {
        // Escape single quotes for shell safety; ADB shell uses single quotes for text
        String escaped = text.replace("'", "'\"'\"'");
        executeShell(adbPort, "input text '" + escaped + "'");
    }

    /**
     * Send a key event by Android keycode.
     *
     * @param adbPort device ADB port
     * @param keycode Android keycode constant (e.g. 4 for KEYCODE_BACK, 3 for HOME)
     */
    public static void sendKeyEvent(int adbPort, int keycode) {
        executeShell(adbPort, "input keyevent " + keycode);
    }

    /**
     * Execute an arbitrary shell command on the device and return stdout.
     *
     * @param adbPort device ADB port
     * @param command the shell command (e.g. "dumpsys battery")
     * @return stdout string, or empty string on failure
     */
    public static String shell(int adbPort, String command) {
        return executeShell(adbPort, command);
    }

    /**
     * Synchronous shell execution — acquires connection, sends command, returns stdout.
     */
    private static String executeShell(int adbPort, String command) {
        AdbConnection conn = getConnection(adbPort);
        try {
            return shellSync(conn, command);
        } catch (IOException e) {
            // Connection may be dead — invalidate and retry once
            connectionPool.remove(adbPort);
            conn.close();
            log.warn("ADB connection lost for device {}, reconnecting...", adbPort);
            try {
                AdbConnection newConn = getConnection(adbPort);
                return shellSync(newConn, command);
            } catch (IOException e2) {
                throw new RuntimeException("ADB shell failed on device " + adbPort
                        + " command=[" + command + "]: " + e2.getMessage(), e2);
            }
        }
    }

    // ---------------------------------------------------------------
    //  Async API — CompletableFuture wrappers for virtual-thread usage
    // ---------------------------------------------------------------

    /**
     * Async tap.
     */
    public static CompletableFuture<Void> sendTapAsync(int adbPort, int x, int y) {
        return CompletableFuture.runAsync(() -> sendTap(adbPort, x, y), asyncExecutor);
    }

    /**
     * Async swipe.
     */
    public static CompletableFuture<Void> sendSwipeAsync(int adbPort, int x1, int y1, int x2, int y2, long durationMs) {
        return CompletableFuture.runAsync(() -> sendSwipe(adbPort, x1, y1, x2, y2, durationMs), asyncExecutor);
    }

    /**
     * Async text input.
     */
    public static CompletableFuture<Void> sendTextAsync(int adbPort, String text) {
        return CompletableFuture.runAsync(() -> sendText(adbPort, text), asyncExecutor);
    }

    /**
     * Async key event.
     */
    public static CompletableFuture<Void> sendKeyEventAsync(int adbPort, int keycode) {
        return CompletableFuture.runAsync(() -> sendKeyEvent(adbPort, keycode), asyncExecutor);
    }

    /**
     * Async shell command, returns stdout string.
     */
    public static CompletableFuture<String> shellAsync(int adbPort, String command) {
        return CompletableFuture.supplyAsync(() -> shell(adbPort, command), asyncExecutor);
    }

    // ---------------------------------------------------------------
    //  Lifecycle
    // ---------------------------------------------------------------

    /**
     * Close all connections in the pool.
     */
    @Override
    public void close() {
        log.info("Closing all ADB socket connections (pool size: {})", connectionPool.size());
        for (AdbConnection conn : connectionPool.values()) {
            conn.close();
        }
        connectionPool.clear();
    }

    /**
     * Close connections for a specific device port and remove from pool.
     */
    public static void closeConnection(int adbPort) {
        AdbConnection conn = connectionPool.remove(adbPort);
        if (conn != null) {
            conn.close();
            log.debug("Closed ADB connection for device 127.0.0.1:{}", adbPort);
        }
    }

    /**
     * Number of active connections in the pool.
     */
    public static int activeConnectionCount() {
        return connectionPool.size();
    }
}
