package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 创建列车制动档案请求。requestId 为幂等键；t、a 均为正十进制、最多 3 位小数。
 */
public record TrainProfileRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotBlank String trainNo,
        @NotNull @Positive @Digits(integer = 15, fraction = 3) BigDecimal reactionTimeT,
        @NotNull @Positive @Digits(integer = 15, fraction = 3) BigDecimal baseDecelA) {
}
