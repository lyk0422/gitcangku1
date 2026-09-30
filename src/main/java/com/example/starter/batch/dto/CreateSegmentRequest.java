package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 创建仓储段请求。温度限值单位为摄氏度(℃)，lowerLimit 必须严格小于 upperLimit。
 */
public record CreateSegmentRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "segmentKey 不能为空") String segmentKey,
        @NotNull(message = "upperLimit 不能为空，单位℃") BigDecimal upperLimit,
        @NotNull(message = "lowerLimit 不能为空，单位℃") BigDecimal lowerLimit
) {
}
