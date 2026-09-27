package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建活动请求。活动按 UTC 自然日配置总确认额度与 24 个小时预算。
 *
 * @param requestId      写操作全局唯一幂等键
 * @param activityId     活动编号，全局唯一
 * @param dailyTotalCap  每 UTC 自然日总确认额度，单位次，非负整数，等于 24 个小时预算合计
 * @param hourlyBudgets  固定 24 项，依次为 UTC 00～23 时预算，单位次，均为非负整数
 */
public record CreateActivityRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String activityId,
        @NotNull @Min(0) @Max(100_000) Integer dailyTotalCap,
        @NotNull @Size(min = 24, max = 24) List<@NotNull @Min(0) @Max(100_000) Integer> hourlyBudgets
) {
}
