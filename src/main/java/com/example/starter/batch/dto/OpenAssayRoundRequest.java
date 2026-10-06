package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 开平行测定轮次请求。均值区间为闭区间，与最大极差均为十进制字符串、最多 4 位小数；
 * expectedVersion 为该检验项当前已有轮次数（首轮为 0），不符返回 409。
 */
public record OpenAssayRoundRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "testItem 不能为空") String testItem,
        @NotBlank(message = "operator 不能为空") String operator,
        @NotNull(message = "expectedVersion 不能为空")
        @PositiveOrZero(message = "expectedVersion 不能为负") Integer expectedVersion,
        @NotBlank(message = "meanMin 不能为空") String meanMin,
        @NotBlank(message = "meanMax 不能为空") String meanMax,
        @NotBlank(message = "maxRange 不能为空") String maxRange
) {
}
