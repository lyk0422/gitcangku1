package com.example.starter.blind.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 创建试验中心请求体。
 *
 * @param targetCap 目标入组上限（人），0~1000000；激活时校验必须大于零，
 *                  累计分配达到上限后新分配返回 422，退组不回收容量
 */
public record CreateSiteRequest(
        @NotNull(message = "targetCap 不能为空")
        @Min(value = 0, message = "targetCap 不能为负数")
        @Max(value = 1_000_000, message = "targetCap 不能超过 1000000")
        Integer targetCap
) {
}
