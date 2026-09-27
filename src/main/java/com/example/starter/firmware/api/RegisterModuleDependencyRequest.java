package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登记模块依赖边请求：moduleName 依赖 dependsOn，随模块一并纳入固件完整依赖集合。
 * minVersion 可空，缺省为 0（无版本要求）；点分数字非法返回 422；登记后态成环返回 422。
 */
public record RegisterModuleDependencyRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String moduleName,
        @NotBlank @Size(max = 64) String dependsOn,
        @Size(max = 64) String minVersion) {

    public String effectiveMinVersion() {
        return minVersion == null || minVersion.isBlank() ? "0" : minVersion;
    }
}
