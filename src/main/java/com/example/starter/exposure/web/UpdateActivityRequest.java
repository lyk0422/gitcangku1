package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 修改活动配置请求。24 个小时预算合计必须等于 dailyConfirmCap；
 * 当前 UTC 日已开始的小时（0~当前小时）预算不可下调；历史预占与确认记录不迁移。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param dailyConfirmCap 每 UTC 日总确认额度，单位次，取值 1~100000
 * @param hourlyBudgets   24 个小时预算（0~23 时，单位次，非负整数）
 * @param expectedVersion 期望的当前配置版本；不一致返回 409
 */
public record UpdateActivityRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Min(1) @Max(100_000) Integer dailyConfirmCap,
        @NotNull @Size(min = 24, max = 24) List<@NotNull @Min(0) @Max(100_000) Integer> hourlyBudgets,
        @NotNull @Min(1) Long expectedVersion
) {
}
