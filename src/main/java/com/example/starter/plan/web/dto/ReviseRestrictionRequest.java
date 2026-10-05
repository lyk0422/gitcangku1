package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 限速令修订请求：对既有限速令产生新版本（版本加一），旧版本置为 SUPERSEDED。
 * requestKey 为幂等键；operator 参与幂等指纹。
 */
public record ReviseRestrictionRequest(
        @NotBlank String requestKey,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        @NotNull Integer maxSpeedKmh,
        @NotBlank String operator) {
}
