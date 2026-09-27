package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 登记包装计划请求。
 *
 * <p>plannedQuantity 为计划包装数量（正整数，单位：件/盒等包装内数量，无小数）；
 * labelRangeStart/labelRangeEnd 为连续标签号段，区间两端均含，起点不得大于终点。
 * 号段容量无需等于计划数量：一个标签对应一个封箱，每箱数量为正整数，
 * 放行时要求封箱数量之和恰为计划数量，且号段内已用标签数等于封箱数。
 */
public record RegisterPackingRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @Positive(message = "plannedQuantity 必须为正整数") long plannedQuantity,
        @Positive(message = "labelRangeStart 必须为正整数") long labelRangeStart,
        @Positive(message = "labelRangeEnd 必须为正整数") long labelRangeEnd
) {
}
