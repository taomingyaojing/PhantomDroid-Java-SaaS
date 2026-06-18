package com.phantomdroid.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for batch launching cloud phone containers.
 */
public class BatchLaunchDTO {

    @Min(value = 1, message = "At least 1 device required")
    @Max(value = 50, message = "Max 50 devices per batch launch")
    private int count;

    @NotBlank(message = "Brand cannot be empty")
    private String brand;

    @Min(value = 1024, message = "ADB port must be >= 1024")
    private int startAdbPort;

    // --- Getters / Setters ---

    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public int getStartAdbPort() { return startAdbPort; }
    public void setStartAdbPort(int startAdbPort) { this.startAdbPort = startAdbPort; }
}
