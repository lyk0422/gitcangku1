package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 修改活动配置请求。携带 expectedVersion 做乐观锁：版本不匹配返回 409。
 * 已开始（含当前进行中）的 UTC 小时预算不可下调，否则返回 409；历史预占与确认记录不迁移。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param expectedVersion 调用方持有的配置版本，必须等于当前版本
 * @param dailyTotalCap   新的每 UTC 日总确认额度，单位次，非负整数，等于 24 个小时预算合计
 * @param hourlyBudgets   固定 24 项，依次为 UTC 00～23 时新预算，单位次，均为非负整数
 */
public record UpdateActivityRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Min(1) Integer expectedVersion,
        @NotNull @Min(0) @Max(100_000) Integer dailyTotalCap,
        @NotNull @Size(min = 24, max = 24) List<@NotNull @Min(0) @Max(100_000) Integer> hourlyBudgets
) {
}
