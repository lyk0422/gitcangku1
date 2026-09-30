package com.example.starter.salinity.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 勘误追加请求：引用原封存条目的站位与深度，仅追加更正说明，不改动冻结剖面。
 *
 * @param station               原封存条目站位
 * @param depthM                原封存条目深度（米）
 * @param correctedSalinityPsu  更正后盐度（PSU，0-45）；可选，为空表示纯文字勘误
 * @param note                  更正说明正文
 * @param requestId             勘误请求幂等键（全局唯一）
 */
public record CorrectionRequest(
        @NotBlank @Size(max = 64) String station,
        @NotNull Integer depthM,
        @DecimalMin("0.000") @DecimalMax("45.000")
        @Digits(integer = 2, fraction = 3) BigDecimal correctedSalinityPsu,
        @NotBlank @Size(max = 500) String note,
        @NotBlank @Size(max = 96) String requestId) {
}
