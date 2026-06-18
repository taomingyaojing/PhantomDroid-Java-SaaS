package com.phantomdroid.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phantomdroid.config.PhantomDroidProperties;
import com.phantomdroid.dto.DeviceDTO;
import com.phantomdroid.entity.User;
import com.phantomdroid.manager.DockerContainerManager;
import com.phantomdroid.repository.DeviceRepository;
import com.phantomdroid.repository.UserRepository;
import com.phantomdroid.util.AdbSocketUtil;
import com.phantomdroid.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/**
 * WebSocket dual-channel handler with JWT-based authentication.
 *
 * Authentication flow:
 * 1. Client connects with ?token=JWT in the URL
 * 2. On connection, the token is validated and the owning user is resolved
 * 3. All subsequent stream/touch operations verify device ownership against the authenticated user
 *
 * Cross-user access attempts result in the connection being closed with a 4001 policy violation.
 */
@Component
public class DeviceWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DeviceWebSocketHandler.class);

    private static final int SCREENCAP_FPS = 2;
    private static final long SCREENCAP_INTERVAL_MS = 500;

    private final DockerContainerManager containerManager;
    private final PhantomDroidProperties props;
    private final ObjectMapper objectMapper;
    private final JwtUtil jwtUtil;
    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final AdbSocketUtil adbSocket;

    /** Authenticated user per session: sessionId -> userId */
    private final Map<String, Long> sessionUsers = new ConcurrentHashMap<>();

    /** Pre-resolved User entities per session (cached to avoid repeated DB lookups) */
    private final Map<String, User> sessionUserEntities = new ConcurrentHashMap<>();

    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledExecutorService scheduler;

    /** Per-session streaming state: sessionId -> Set<adbPort> */
    private final Map<String, Set<Integer>> sessionStreams = new ConcurrentHashMap<>();

    /** Global screencap polling tasks: adbPort -> future */
    private final Map<Integer, ScheduledFuture<?>> screencapTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService screencapExecutor = Executors.newScheduledThreadPool(4,
            r -> Thread.ofVirtual().name("screencap-poll").unstarted(r));

    public DeviceWebSocketHandler(DockerContainerManager containerManager,
                                  PhantomDroidProperties props,
                                  ObjectMapper objectMapper,
                                  JwtUtil jwtUtil,
                                  DeviceRepository deviceRepository,
                                  UserRepository userRepository,
                                  AdbSocketUtil adbSocket) {
        this.containerManager = containerManager;
        this.props = props;
        this.objectMapper = objectMapper;
        this.jwtUtil = jwtUtil;
        this.deviceRepository = deviceRepository;
        this.userRepository = userRepository;
        this.adbSocket = adbSocket;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Extract JWT token from query parameter
        URI uri = session.getUri();
        String token = null;
        if (uri != null) {
            String query = uri.getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length == 2 && "token".equals(kv[0])) {
                        token = kv[1];
                        break;
                    }
                }
            }
        }

        if (token == null || token.isEmpty()) {
            rejectSession(session, "Missing authentication token");
            return;
        }

        Claims claims = jwtUtil.validateToken(token);
        if (claims == null) {
            rejectSession(session, "Invalid or expired token");
            return;
        }

        Long userId = jwtUtil.getUserIdFromClaims(claims);
        Optional<User> optUser = userRepository.findById(userId);
        if (optUser.isEmpty()) {
            rejectSession(session, "Authenticated user not found in database");
            return;
        }

        User user = optUser.get();
        sessionUsers.put(session.getId(), userId);
        sessionUserEntities.put(session.getId(), user);
        sessions.add(session);
        sessionStreams.put(session.getId(), ConcurrentHashMap.newKeySet());

        log.info("WS client authenticated: session={}, user={}", session.getId(), user.getUsername());
        startHeartbeat();

        // Send auth success confirmation
        sendJson(session, "{\"cmd\":\"auth_ok\",\"userId\":" + userId + ",\"username\":\"" + user.getUsername() + "\"}");
    }

    private void rejectSession(WebSocketSession session, String reason) {
        log.warn("WS auth rejected: session={}, reason={}", session.getId(), reason);
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException e) {
            log.warn("Failed to close rejected WS session", e);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long userId = sessionUsers.get(session.getId());
        if (userId == null) {
            closeWithError(session, "Unauthenticated");
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = objectMapper.readValue(message.getPayload(), Map.class);
            String cmd = (String) msg.getOrDefault("cmd", "");
            int adbPort = msg.containsKey("adbPort") ? ((Number) msg.get("adbPort")).intValue() : -1;

            switch (cmd) {
                case "ping" -> sendJson(session, "{\"pong\":true}");
                case "health" -> {
                    if (adbPort > 0) {
                        // Verify ownership before health check
                        if (!verifyWsOwnership(session, adbPort)) return;
                        int p = adbPort;
                        containerManager.checkAdbHealth(p).thenAccept(ok ->
                                sendJson(session, "{\"cmd\":\"health\",\"adbPort\":" + p + ",\"alive\":" + ok + "}"));
                    }
                }
                case "start_stream" -> {
                    if (adbPort > 0) {
                        if (!verifyWsOwnership(session, adbPort)) return;
                        startScreencap(session, adbPort);
                    }
                }
                case "stop_stream" -> {
                    if (adbPort > 0) stopScreencap(session, adbPort);
                }
                default -> log.debug("Unknown WS cmd: {}", cmd);
            }
        } catch (Exception e) {
            log.warn("Invalid WS text msg: {}", e.getMessage());
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        Long userId = sessionUsers.get(session.getId());
        if (userId == null) {
            closeWithError(session, "Unauthenticated");
            return;
        }

        ByteBuffer buf = message.getPayload();
        if (buf.remaining() < 5) return;
        byte type = buf.get();
        int adbPort = buf.getInt();

        // Verify device ownership before processing any input
        if (!verifyWsOwnership(session, adbPort)) return;

        // Touch/key events from client
        if (type == 0x02 && buf.remaining() >= 16) {
            int action = buf.getInt();
            float x = buf.getFloat();
            float y = buf.getFloat();
            int scaledX = (int) x;
            int scaledY = (int) y;
            try {
                // Tap when action is 0 (down+up), swipe when action is otherwise (move/drag)
                if (action == 0) {
                    adbSocket.sendTap(adbPort, scaledX, scaledY);
                } else {
                    adbSocket.sendSwipe(adbPort, scaledX, scaledY, scaledX, scaledY, 50);
                }
            } catch (Exception ignored) {}
        } else if (type == 0x03 && buf.remaining() >= 1) {
            int keycode = buf.get() & 0xFF;
            try {
                adbSocket.sendKeyEvent(adbPort, keycode);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Set<Integer> ports = sessionStreams.remove(session.getId());
        if (ports != null) ports.forEach(p -> stopScreencapTask(p));
        sessions.remove(session);
        sessionUsers.remove(session.getId());
        sessionUserEntities.remove(session.getId());
        log.info("WS client disconnected: {}", session.getId());
        if (sessions.isEmpty() && heartbeatTask != null) heartbeatTask.cancel(false);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) {
        log.warn("WS transport error {}: {}", session.getId(), ex.getMessage());
        afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
    }

    // ============================================================
    //  OWNERSHIP VERIFICATION
    // ============================================================

    /**
     * Verify that the device on adbPort belongs to the authenticated WS user.
     * Sends error response and returns false if unauthorized.
     */
    private boolean verifyWsOwnership(WebSocketSession session, int adbPort) {
        User owner = sessionUserEntities.get(session.getId());
        if (owner == null) {
            sendJson(session, "{\"cmd\":\"error\",\"message\":\"Unauthenticated\"}");
            return false;
        }

        if (!deviceRepository.existsByAdbPortAndOwner(adbPort, owner)) {
            log.warn("WS cross-user access denied: user {} tried device port {}",
                    owner.getId(), adbPort);
            sendJson(session, "{\"cmd\":\"error\",\"message\":\"Forbidden: device does not belong to you\"}");
            return false;
        }
        return true;
    }

    private void closeWithError(WebSocketSession session, String reason) {
        log.warn("Closing WS session {}: {}", session.getId(), reason);
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException ignored) {}
    }

    // ============================================================
    //  SCREENCAP POLLING
    // ============================================================

    private void startScreencap(WebSocketSession session, int adbPort) {
        Set<Integer> ports = sessionStreams.get(session.getId());
        if (ports != null) ports.add(adbPort);

        if (!screencapTasks.containsKey(adbPort)) {
            ScheduledFuture<?> task = screencapExecutor.scheduleAtFixedRate(
                    () -> captureAndSend(adbPort),
                    0, SCREENCAP_INTERVAL_MS, TimeUnit.MILLISECONDS);
            screencapTasks.put(adbPort, task);
            log.info("Screencap polling started for port {}", adbPort);
        }

        sendJson(session, "{\"cmd\":\"stream_started\",\"adbPort\":" + adbPort + "}");
    }

    private void stopScreencap(WebSocketSession session, int adbPort) {
        Set<Integer> ports = sessionStreams.get(session.getId());
        if (ports != null) ports.remove(adbPort);
        stopScreencapTask(adbPort);
    }

    private synchronized void stopScreencapTask(int adbPort) {
        boolean stillWanted = sessionStreams.values().stream().anyMatch(s -> s.contains(adbPort));
        if (!stillWanted) {
            ScheduledFuture<?> task = screencapTasks.remove(adbPort);
            if (task != null) {
                task.cancel(false);
                log.info("Screencap polling stopped for port {}", adbPort);
            }
        }
    }

    private void captureAndSend(int adbPort) {
        try {
            Process p = new ProcessBuilder(
                    "adb", "-s", "127.0.0.1:" + adbPort,
                    "exec-out", "screencap", "-p")
                    .redirectErrorStream(true)
                    .start();

            ByteArrayOutputStream baos = new ByteArrayOutputStream(65536);
            byte[] buf = new byte[8192];
            int len;
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && (len = p.getInputStream().read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
            p.destroy();
            byte[] pngData = baos.toByteArray();
            if (pngData.length < 100) return;

            String b64 = Base64.getEncoder().encodeToString(pngData);
            String payload = "{\"cmd\":\"screencap\",\"adbPort\":" + adbPort + ",\"data\":\"" + b64 + "\"}";

            for (WebSocketSession s : sessions) {
                Set<Integer> ports = sessionStreams.get(s.getId());
                if (ports != null && ports.contains(adbPort) && s.isOpen()) {
                    try { s.sendMessage(new TextMessage(payload)); }
                    catch (IOException ignored) {}
                }
            }
        } catch (Exception e) {
            log.debug("Screencap failed on port {}: {}", adbPort, e.getMessage());
        }
    }

    // ============================================================
    //  HEARTBEAT / STATUS BROADCAST
    // ============================================================

    private void startHeartbeat() {
        if (heartbeatTask != null && !heartbeatTask.isCancelled()) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofVirtual().name("ws-heartbeat").unstarted(r));
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            if (sessions.isEmpty()) return;
            List<DeviceDTO> devices = containerManager.getAllDevices();
            try {
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("type", "device_status");
                envelope.put("timestamp", System.currentTimeMillis());
                envelope.put("devices", devices);
                envelope.put("totalCount", devices.size());
                String payload = objectMapper.writeValueAsString(envelope);
                for (WebSocketSession s : sessions) {
                    if (s.isOpen()) try { s.sendMessage(new TextMessage(payload)); } catch (IOException ignored) {}
                }
            } catch (Exception e) {
                log.error("Failed to broadcast status", e);
            }
        }, 0, props.getWebsocket().getHeartbeatMs(), TimeUnit.MILLISECONDS);
    }

    private void sendJson(WebSocketSession session, String json) {
        if (session.isOpen()) {
            try { session.sendMessage(new TextMessage(json)); }
            catch (IOException e) { log.warn("Failed to send WS msg", e); }
        }
    }
}
