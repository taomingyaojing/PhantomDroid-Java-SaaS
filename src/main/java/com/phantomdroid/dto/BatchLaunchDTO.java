package com.phantomdroid.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for batch launching cloud phone containers.
 * Supports optional per-container proxy configuration.
 */
public class BatchLaunchDTO {

    @Min(value = 1, message = "At least 1 device required")
    @Max(value = 50, message = "Max 50 devices per batch launch")
    private int count;

    @NotBlank(message = "Brand cannot be empty")
    private String brand;

    @Min(value = 1024, message = "ADB port must be >= 1024")
    private int startAdbPort;

    // Optional per-container proxy settings
    private String proxyIp;
    private Integer proxyPort;
    private String proxyType; // "http" or "socks5"

    // --- Getters / Setters ---

    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public int getStartAdbPort() { return startAdbPort; }
    public void setStartAdbPort(int startAdbPort) { this.startAdbPort = startAdbPort; }

    public String getProxyIp() { return proxyIp; }
    public void setProxyIp(String proxyIp) { this.proxyIp = proxyIp; }

    public Integer getProxyPort() { return proxyPort; }
    public void setProxyPort(Integer proxyPort) { this.proxyPort = proxyPort; }

    public String getProxyType() { return proxyType; }
    public void setProxyType(String proxyType) { this.proxyType = proxyType; }
}
