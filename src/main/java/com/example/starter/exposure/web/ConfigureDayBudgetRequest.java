package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 配置活动某 UTC 日匀速投放预算请求。24 个小时预算必须均为非负整数且合计等于日总额。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param expectedVersion 期望的当前配置版本；未配置过为 0，不一致返回 409
 * @param dailyTotalCap   该 UTC 日总确认额度，单位次，取值 0~100000
 * @param hourlyBudgets   24 个小时预算，下标即小时（0~23，UTC），单位次
 */
public record ConfigureDayBudgetRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Min(0) Integer expectedVersion,
        @NotNull @Min(0) @Max(100_000) Integer dailyTotalCap,
        @NotNull @Size(min = 24, max = 24) List<@NotNull @Min(0) @Max(100_000) Integer> hourlyBudgets
) {
}