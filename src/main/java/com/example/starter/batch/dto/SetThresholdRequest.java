package com.example.starter.batch.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 设置供应商准入门槛请求。requestId 为幂等键：同键同参重放首次结果，异参 409，失败不占键。
 * threshold 取值 -100～100；供应商当前滑动评分低于门槛时，其新批次创建返回 422。
 */
public record SetThresholdRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotNull(message = "threshold 不能为空")
        @Min(value = -100, message = "threshold 不能小于 -100")
        @Max(value = 100, message = "threshold 不能大于 100")
        Integer threshold
) {
}
