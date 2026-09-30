package com.example.starter.salinity.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 样本提交请求。
 *
 * @param voyageCode     航次编号
 * @param station        站位编号
 * @param depthM         深度（米），0-500
 * @param salinityPsu    盐度（PSU），0-45
 * @param collectedAtUtc 采集时刻（UTC）
 * @param sampleNo       采样编号（全局唯一）
 * @param requestId      请求幂等键（全局唯一）
 */
public record SubmitSampleRequest(
        @NotBlank @Size(max = 64) String voyageCode,
        @NotBlank @Size(max = 64) String station,
        @NotNull @Min(0) @Max(500) Integer depthM,
        @NotNull @DecimalMin("0.000") @DecimalMax("45.000")
        @Digits(integer = 2, fraction = 3) BigDecimal salinityPsu,
        @NotNull Instant collectedAtUtc,
        @NotBlank @Size(max = 96) String sampleNo,
        @NotBlank @Size(max = 96) String requestId) {
}
