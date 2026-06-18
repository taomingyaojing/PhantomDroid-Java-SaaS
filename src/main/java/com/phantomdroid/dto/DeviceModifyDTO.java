package com.phantomdroid.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Request DTO for batch-modifying device location &amp; fingerprint.
 */
public class DeviceModifyDTO {

    @NotEmpty(message = "At least one ADB port required")
    private List<Integer> adbPorts;

    private boolean enableLocationOverride;
    private Double latitude;
    private Double longitude;

    private boolean enableFingerprintOverride;

    // --- Getters / Setters ---

    public List<Integer> getAdbPorts() { return adbPorts; }
    public void setAdbPorts(List<Integer> adbPorts) { this.adbPorts = adbPorts; }

    public boolean isEnableLocationOverride() { return enableLocationOverride; }
    public void setEnableLocationOverride(boolean enableLocationOverride) { this.enableLocationOverride = enableLocationOverride; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }

    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    public boolean isEnableFingerprintOverride() { return enableFingerprintOverride; }
    public void setEnableFingerprintOverride(boolean enableFingerprintOverride) { this.enableFingerprintOverride = enableFingerprintOverride; }
}
