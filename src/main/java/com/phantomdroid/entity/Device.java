package com.phantomdroid.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Cloud phone device entity, persisted to SQLite.
 * Each device is owned by exactly one user (@ManyToOne).
 * Cross-user access is blocked at the JwtFilter + Controller level.
 */
@Entity
@Table(name = "devices")
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String deviceId;

    @Column(nullable = false, unique = true)
    private int adbPort;

    @Column(length = 32)
    private String status = "BOOTING"; // CREATING / BOOTING / RUNNING / ERROR / DESTROYED

    @Column(length = 64)
    private String brand;

    @Column(length = 64)
    private String model;

    private double latitude;
    private double longitude;

    @Column(length = 64)
    private String androidId;

    @Column(length = 64)
    private String imei;

    @Column(length = 128)
    private String containerId;

    private long uptimeSeconds;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private boolean streaming;

    @Column(length = 64)
    private String proxyIp;

    @Column
    private Integer proxyPort;

    @Column(length = 16)
    private String proxyType; // http or socks5

    // ===== Multi-tenant ownership =====
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User owner;

    public Device() {}

    // --- Getters / Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

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

    public String getProxyIp() { return proxyIp; }
    public void setProxyIp(String proxyIp) { this.proxyIp = proxyIp; }

    public Integer getProxyPort() { return proxyPort; }
    public void setProxyPort(Integer proxyPort) { this.proxyPort = proxyPort; }

    public String getProxyType() { return proxyType; }
    public void setProxyType(String proxyType) { this.proxyType = proxyType; }

    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }
}
