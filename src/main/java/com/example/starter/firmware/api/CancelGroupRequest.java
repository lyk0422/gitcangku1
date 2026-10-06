package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 组取消请求：仅激活前（PREPARING）可取消并释放组占用；激活后不允许取消。
 */
public record CancelGroupRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @Min(1) int expectedVersion) {
}
