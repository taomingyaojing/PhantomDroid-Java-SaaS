package com.phantomdroid.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Request DTO for batch APK installation.
 */
public class AppInstallDTO {

    @NotEmpty(message = "At least one ADB port required")
    private List<Integer> adbPorts;

    @NotEmpty(message = "APK download URL required")
    private String apkUrl;

    private String packageName;

    public List<Integer> getAdbPorts() { return adbPorts; }
    public void setAdbPorts(List<Integer> adbPorts) { this.adbPorts = adbPorts; }

    public String getApkUrl() { return apkUrl; }
    public void setApkUrl(String apkUrl) { this.apkUrl = apkUrl; }

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }
}
