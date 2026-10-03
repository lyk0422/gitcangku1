package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

/** 测色上报请求：requestId 幂等，同参重放、异参 409；measuredAt 为 UTC 时刻。 */
public record MeasurementRequest(
        @NotBlank String requestId,
        @NotBlank @Pattern(regexp = "ORIGINAL|RECHECK") String kind,
        @NotNull Double l,
        @NotNull Double a,
        @NotNull Double b,
        @NotNull Instant measuredAt) {
}