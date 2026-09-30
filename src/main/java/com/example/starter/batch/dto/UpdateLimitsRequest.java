package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 修改仓储段温度限值请求，单位℃；仅影响修改之后登记的新读数，
 * 历史偏差保留触发时的限值快照，不回写。
 */
public record UpdateLimitsRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "upperLimit 不能为空，单位℃") BigDecimal upperLimit,
        @NotNull(message = "lowerLimit 不能为空，单位℃") BigDecimal lowerLimit
) {
}
