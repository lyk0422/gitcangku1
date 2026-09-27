package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 登记包装标签计划请求：plannedQuantity 为计划包装数量（正整数，单位由产品包装约定），
 * labelRangeStart/labelRangeEnd 为连续标签号段，端点均含（闭区间）。
 */
public record RegisterLabelPlanRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "plannedQuantity 不能为空")
        @Positive(message = "plannedQuantity 必须为正整数") Integer plannedQuantity,
        @NotNull(message = "labelRangeStart 不能为空")
        @Positive(message = "labelRangeStart 必须为正整数") Long labelRangeStart,
        @NotNull(message = "labelRangeEnd 不能为空")
        @Positive(message = "labelRangeEnd 必须为正整数") Long labelRangeEnd
) {
}
