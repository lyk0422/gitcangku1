package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建区域配额请求。
 */
public record CreateRegionRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Min(1) long releaseId,
        @NotBlank @Size(max = 64) String regionCode,
        @Min(1) int quota) {
}
