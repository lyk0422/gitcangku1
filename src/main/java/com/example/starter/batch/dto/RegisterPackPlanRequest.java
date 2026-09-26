package com.example.starter.batch.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 登记包装计划请求。标签号段为左闭右开区间 [labelStart, labelEnd)，
 * 号段容量（labelEnd - labelStart）不得小于 plannedQuantity。
 */
public record RegisterPackPlanRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "plannedQuantity 不能为空")
        @Min(value = 1, message = "plannedQuantity 必须为正整数") Integer plannedQuantity,
        @NotNull(message = "labelStart 不能为空") Long labelStart,
        @NotNull(message = "labelEnd 不能为空") Long labelEnd
) {
}
