package com.example.starter.blind.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 中心激活请求体。
 *
 * @param targetCap 中心目标入组上限（人），正整数；生效时据此减去累计已分配数得到剩余容量
 */
public record CreateCenterRequest(
        @NotNull(message = "targetCap 不能为空")
        @Min(value = 1, message = "中心目标入组上限必须为正整数")
        @Max(value = 100000, message = "中心目标入组上限不能超过 100000")
        Integer targetCap
) {
}
