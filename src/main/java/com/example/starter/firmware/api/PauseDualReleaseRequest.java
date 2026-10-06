package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 暂停双分区发布单请求：仅 ACTIVE 可暂停；expectedVersion 为期望的发布单版本号。
 */
public record PauseDualReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotNull @Min(1) Integer expectedVersion) {
}
