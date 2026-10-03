package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 修订气象限速令请求：调整时段与最高速度，版本加一；区段与业务键不可变。
 * expectedVersion 必须与当前版本一致；已撤销的限速令不可修订。
 */
public record RestrictionReviseRequest(
        @NotBlank String requestKey,
        @NotNull Integer expectedVersion,
        Instant startUtc,
        Instant endUtc,
        Integer maxSpeedKmh,
        @NotBlank String operator) {
}