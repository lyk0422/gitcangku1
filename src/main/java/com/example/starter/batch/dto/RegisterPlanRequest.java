package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 登记包装计划请求。plannedQuantity 为正整数；[labelStart, labelEnd] 为连续标签号段（两端含）。
 */
public record RegisterPlanRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "plannedQuantity 不能为空")
        @Positive(message = "plannedQuantity 必须为正整数") Integer plannedQuantity,
        @NotNull(message = "labelStart 不能为空")
        @PositiveOrZero(message = "labelStart 不能为负数") Long labelStart,
        @NotNull(message = "labelEnd 不能为空")
        @PositiveOrZero(message = "labelEnd 不能为负数") Long labelEnd
) {
}
