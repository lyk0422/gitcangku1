package com.example.starter.blind.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 创建（激活）研究中心请求体。
 *
 * @param targetCap 目标入组上限（人），正整数，创建后不可改
 */
public record CreateCenterRequest(
        @NotNull(message = "targetCap 不能为空")
        @Min(value = 1, message = "目标入组上限必须为正整数")
        @Max(value = 1_000_000, message = "目标入组上限超出允许范围")
        Integer targetCap
) {
}
