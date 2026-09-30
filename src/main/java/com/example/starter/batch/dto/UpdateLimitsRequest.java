package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 仓储段限值改版请求。仅新版本生效；历史偏差不回写。commandKey 走命令幂等。
 */
public record UpdateLimitsRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "lowerLimit 不能为空") BigDecimal lowerLimit,
        @NotNull(message = "upperLimit 不能为空") BigDecimal upperLimit
) {
}
