package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 设备维护请求：提交 expectedDeviceVersion 乐观校验设备档案版本，校验通过后将启动计数
 * 维护到 bootCount（不可递减）并推进档案版本。bootCount 为非负 long，超出 long 范围按 400 处理。
 */
public record MaintenanceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @Min(1) int expectedDeviceVersion,
        @NotNull @Min(0) Long bootCount) {
}
