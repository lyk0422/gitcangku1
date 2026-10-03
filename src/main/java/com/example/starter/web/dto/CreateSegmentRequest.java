package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 航段申请请求，时间均为 UTC 的 ISO-8601 时刻。
 */
public record CreateSegmentRequest(@NotBlank String vesselCode,
                                   @NotNull Integer requiredLevel,
                                   @NotNull Instant estStartUtc,
                                   @NotNull Instant estEndUtc,
                                   @NotBlank String boardingPoint) {
}