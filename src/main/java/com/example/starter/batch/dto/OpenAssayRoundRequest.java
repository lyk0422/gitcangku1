package com.example.starter.batch.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 开启平行测定轮次请求。均值区间与极差为十进制字符串，最多 4 位小数；
 * expectedVersion 为该检验项当前已有轮次数（首轮为 0），不符返回 409。
 */
public record OpenAssayRoundRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "testItem 不能为空") String testItem,
        @NotBlank(message = "meanMin 不能为空")
        @Pattern(regexp = "^-?\\d{1,18}(\\.\\d{1,4})?$", message = "meanMin 必须为最多 4 位小数的十进制字符串")
        String meanMin,
        @NotBlank(message = "meanMax 不能为空")
        @Pattern(regexp = "^-?\\d{1,18}(\\.\\d{1,4})?$", message = "meanMax 必须为最多 4 位小数的十进制字符串")
        String meanMax,
        @NotBlank(message = "maxRange 不能为空")
        @Pattern(regexp = "^-?\\d{1,18}(\\.\\d{1,4})?$", message = "maxRange 必须为最多 4 位小数的十进制字符串")
        String maxRange,
        @NotNull(message = "expectedVersion 不能为空")
        @Min(value = 0, message = "expectedVersion 不能为负数") Integer expectedVersion
) {
}
