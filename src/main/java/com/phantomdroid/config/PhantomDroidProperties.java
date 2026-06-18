package com.phantomdroid.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "phantomdroid")
public class PhantomDroidProperties {

    private Docker docker = new Docker();
    private Container container = new Container();
    private Websocket websocket = new Websocket();
    private Scrcpy scrcpy = new Scrcpy();

    public static class Docker {
        private String host = "unix:///var/run/docker.sock";
        private String apiVersion = "1.44";

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public String getApiVersion() { return apiVersion; }
        public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }
    }

    public static class Container {
        private long cpuCount = 1;
        private int memoryMb = 1536;
        private int adbTimeoutMs = 120000;
        private int adbPollIntervalMs = 5000;
        private int adbMaxRetries = 15;
        private int idleTtlMinutes = 60;
        private int adbPortStart = 5555;
        private int adbPortEnd = 65535;

        public long getCpuCount() { return cpuCount; }
        public void setCpuCount(long cpuCount) { this.cpuCount = cpuCount; }
        public int getMemoryMb() { return memoryMb; }
        public void setMemoryMb(int memoryMb) { this.memoryMb = memoryMb; }
        public int getAdbTimeoutMs() { return adbTimeoutMs; }
        public void setAdbTimeoutMs(int adbTimeoutMs) { this.adbTimeoutMs = adbTimeoutMs; }
        public int getAdbPollIntervalMs() { return adbPollIntervalMs; }
        public void setAdbPollIntervalMs(int adbPollIntervalMs) { this.adbPollIntervalMs = adbPollIntervalMs; }
        public int getAdbMaxRetries() { return adbMaxRetries; }
        public void setAdbMaxRetries(int adbMaxRetries) { this.adbMaxRetries = adbMaxRetries; }
        public int getIdleTtlMinutes() { return idleTtlMinutes; }
        public void setIdleTtlMinutes(int idleTtlMinutes) { this.idleTtlMinutes = idleTtlMinutes; }
        public int getAdbPortStart() { return adbPortStart; }
        public void setAdbPortStart(int adbPortStart) { this.adbPortStart = adbPortStart; }
        public int getAdbPortEnd() { return adbPortEnd; }
        public void setAdbPortEnd(int adbPortEnd) { this.adbPortEnd = adbPortEnd; }
    }

    public static class Websocket {
        private int heartbeatMs = 5000;

        public int getHeartbeatMs() { return heartbeatMs; }
        public void setHeartbeatMs(int heartbeatMs) { this.heartbeatMs = heartbeatMs; }
    }

    public Docker getDocker() { return docker; }
    public void setDocker(Docker docker) { this.docker = docker; }
    public Container getContainer() { return container; }
    public void setContainer(Container container) { this.container = container; }
    public Websocket getWebsocket() { return websocket; }
    public void setWebsocket(Websocket websocket) { this.websocket = websocket; }

    public Scrcpy getScrcpy() { return scrcpy; }
    public void setScrcpy(Scrcpy scrcpy) { this.scrcpy = scrcpy; }

    public static class Scrcpy {
        private String serverPath = "/tmp/scrcpy-server";
        private int defaultBitrate = 8000000;
        private int maxFps = 15;
        private int maxWidth = 720;
        private long streamTimeoutMs = 300000;

        public String getServerPath() { return serverPath; }
        public void setServerPath(String serverPath) { this.serverPath = serverPath; }
        public int getDefaultBitrate() { return defaultBitrate; }
        public void setDefaultBitrate(int defaultBitrate) { this.defaultBitrate = defaultBitrate; }
        public int getMaxFps() { return maxFps; }
        public void setMaxFps(int maxFps) { this.maxFps = maxFps; }
        public int getMaxWidth() { return maxWidth; }
        public void setMaxWidth(int maxWidth) { this.maxWidth = maxWidth; }
        public long getStreamTimeoutMs() { return streamTimeoutMs; }
        public void setStreamTimeoutMs(long streamTimeoutMs) { this.streamTimeoutMs = streamTimeoutMs; }
    }
}
