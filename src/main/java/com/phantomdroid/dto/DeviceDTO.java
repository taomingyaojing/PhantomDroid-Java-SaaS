package com.phantomdroid.dto;

import java.time.Instant;

/**
 * Represents the runtime state of a single Redroid container / cloud phone.
 */
public class DeviceDTO {

    private String deviceId;
    private int adbPort;
    private String status;          // RUNNING / BOOTING / ERROR / DESTROYED
    private String brand;
    private String model;
    private double latitude;
    private double longitude;
    private String androidId;
    private String imei;
    private String containerId;
    private long uptimeSeconds;
    private Instant createdAt;
    private boolean streaming; // scrcpy stream active or not

    public DeviceDTO() {}

    public DeviceDTO(String deviceId, int adbPort, String containerId, String brand) {
        this.deviceId = deviceId;
        this.adbPort = adbPort;
        this.containerId = containerId;
        this.brand = brand;
        this.status = "BOOTING";
        this.createdAt = Instant.now();
    }

    // --- Getters / Setters ---

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public int getAdbPort() { return adbPort; }
    public void setAdbPort(int adbPort) { this.adbPort = adbPort; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public String getAndroidId() { return androidId; }
    public void setAndroidId(String androidId) { this.androidId = androidId; }

    public String getImei() { return imei; }
    public void setImei(String imei) { this.imei = imei; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public long getUptimeSeconds() { return uptimeSeconds; }
    public void setUptimeSeconds(long uptimeSeconds) { this.uptimeSeconds = uptimeSeconds; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public boolean isStreaming() { return streaming; }
    public void setStreaming(boolean streaming) { this.streaming = streaming; }
}
