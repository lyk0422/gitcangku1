package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 登记仓储段请求。lowerLimit/upperLimit 为初始温度限值，单位℃，UTC 口径由读数采集时刻保证。
 */
public record RegisterSegmentRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "segmentKey 不能为空") String segmentKey,
        @NotNull(message = "lowerLimit 不能为空") BigDecimal lowerLimit,
        @NotNull(message = "upperLimit 不能为空") BigDecimal upperLimit
) {
}
