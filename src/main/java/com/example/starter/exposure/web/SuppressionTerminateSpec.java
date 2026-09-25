package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 批量更新中对已存在区间的处置规格。
 *
 * @param intervalId 目标区间编号
 * @param action     处置方式：DELETE 未开始区间立即失效；END_EARLY 已开始区间提前结束
 * @param newEndAtUtc 提前结束的新结束时刻，epoch 毫秒，UTC，右开；
 *                    action=END_EARLY 时必填且不得早于当前时刻，action=DELETE 时必须为空
 */
public record SuppressionTerminateSpec(
        @NotBlank @Size(max = 64) String intervalId,
        @NotNull SuppressionTerminateAction action,
        Long newEndAtUtc
) {
}
