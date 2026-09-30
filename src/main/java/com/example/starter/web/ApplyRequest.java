package com.example.starter.web;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 靠泊申请请求；吃水、安全余量单位为米，时间为上海本地时间。
 */
public record ApplyRequest(
        @NotBlank String requestId,
        @NotBlank String vesselName,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal draft,
        @NotNull @DecimalMin("0.0") BigDecimal safetyMargin,
        @NotBlank String berthCode,
        @NotNull LocalDateTime startTime,
        @NotNull LocalDateTime endTime) {
}
