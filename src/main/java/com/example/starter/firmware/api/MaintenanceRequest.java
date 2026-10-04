package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 设备本地维护更新请求：更新当前版本与镜像摘要并使镜像代次加一，型号与灰度桶不可修改。
 * expectedImageGeneration 必须等于设备当前镜像代次。
 */
public record MaintenanceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String newVersion,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$", message = "新镜像摘要须为64位小写十六进制")
        String newDigest,
        @Min(1) int expectedImageGeneration) {
}