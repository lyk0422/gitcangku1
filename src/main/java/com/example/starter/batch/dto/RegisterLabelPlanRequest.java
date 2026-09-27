package com.example.starter.batch.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 登记包装标签计划请求。plannedQuantity 为计划包装数量（件）；
 * 标签号段按左闭右开 [labelStart, labelEnd) 解释，labelEnd 必须大于 labelStart。
 */
public record RegisterLabelPlanRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "plannedQuantity 不能为空")
        @Min(value = 1, message = "plannedQuantity 必须为正整数") Integer plannedQuantity,
        @NotNull(message = "labelStart 不能为空") Long labelStart,
        @NotNull(message = "labelEnd 不能为空") Long labelEnd
) {
}
