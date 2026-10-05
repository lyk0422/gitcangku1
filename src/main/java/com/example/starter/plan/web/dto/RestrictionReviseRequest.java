package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 修订限速令请求：调整时段与最高速度，版本加一；区段不可变更。
 * 已生成的重排记录保持修订前快照不改写。
 */
public record RestrictionReviseRequest(
        @NotBlank String requestKey,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        @NotNull Integer maxSpeedKmh,
        String operator) {
}
