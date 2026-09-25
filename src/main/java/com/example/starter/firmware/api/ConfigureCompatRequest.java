package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 配置固件硬件兼容矩阵请求。expectedVersion 为期望的当前矩阵版本（首次配置为 0）；
 * allowedModels 为空集合表示兼容全部型号，集合换序视为同参；型号重复或未知返回 422。
 */
public record ConfigureCompatRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Min(0) int expectedVersion,
        @NotNull List<@NotBlank @Size(max = 64) String> allowedModels) {
}
