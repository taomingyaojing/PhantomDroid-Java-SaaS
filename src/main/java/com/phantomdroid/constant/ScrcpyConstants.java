package com.phantomdroid.constant;

/**
 * Constants for scrcpy streaming configuration.
 */
public final class ScrcpyConstants {

    private ScrcpyConstants() {}

    /** Max video bitrate per stream (bps). 8Mbps for 1080p@15fps on CPU-only server. */
    public static final long MAX_BITRATE_BPS = 8_000_000L;

    /** Target frame rate for stream. */
    public static final int TARGET_FPS = 15;

    /** Max video resolution (width). 720p for CPU-only encoding. */
    public static final int MAX_WIDTH = 720;

    /** I-frame interval (seconds). */
    public static final int I_FRAME_INTERVAL = 1;

    /** ADB forward local port offset: scrcpy stream starts at 47000 + deviceIndex. */
    public static final int SCRCPY_PORT_START = 47000;

    /** Scrcpy server JAR path on host (pushed to device on first run). */
    public static final String SCRCPY_SERVER_HOST_PATH = "/tmp/scrcpy-server";

    /** Scrcpy server JAR path inside Android device. */
    public static final String SCRCPY_SERVER_DEVICE_PATH = "/data/local/tmp/scrcpy-server";

    /** WebSocket binary message magic byte for scrcpy stream: first byte indicates type. */
    public static final byte WS_MSG_VIDEO = 0x01;
    public static final byte WS_MSG_TOUCH = 0x02;
    public static final byte WS_MSG_KEY = 0x03;
    public static final byte WS_MSG_SHELL = 0x04;

    /** Stream idle timeout (ms) — destroy stream if no activity. */
    public static final long STREAM_IDLE_TIMEOUT_MS = 300_000L;

    /** Touch event control message constants (scrcpy v3 protocol). */
    public static final int TOUCH_ACTION_DOWN = 0;
    public static final int TOUCH_ACTION_UP = 1;
    public static final int TOUCH_ACTION_MOVE = 2;

    /** Scrcpy server process exit code for forced kill. */
    public static final int SCRCPY_FORCE_KILL_CODE = 143;
}
