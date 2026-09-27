package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 登记包装计划请求。plannedQuantity 为计划包装数量（正整数，单位：件）；
 * labelStart/labelEnd 为连续标签号段（均为正整数，闭区间，labelStart <= labelEnd）。
 * 每个批次至多登记一份计划，登记后不可修改。
 */
public record RegisterPlanRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "plannedQuantity 不能为空")
        @Positive(message = "plannedQuantity 必须为正整数") Integer plannedQuantity,
        @NotNull(message = "labelStart 不能为空")
        @Positive(message = "labelStart 必须为正整数") Long labelStart,
        @NotNull(message = "labelEnd 不能为空")
        @Positive(message = "labelEnd 必须为正整数") Long labelEnd
) {
}
