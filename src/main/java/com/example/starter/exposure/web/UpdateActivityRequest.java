package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 修改活动配置请求。携带 expectedVersion 乐观锁；已开始（UTC 当日当前及更早）的小时预算不可下调；
 * 24 个小时预算均为非负整数且合计必须等于新的日总确认额度（服务层校验，不满足返回 400）。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param expectedVersion 期望的当前配置版本号；不匹配返回 409
 * @param dailyTotalCap   新的每 UTC 日总确认额度，单位次
 * @param hourlyBudgets   恰好 24 个新小时预算，按下标对应 UTC 小时 0~23
 */
public record UpdateActivityRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Min(1) Integer expectedVersion,
        @NotNull @Min(0) @Max(100_000) Integer dailyTotalCap,
        @NotNull @Size(min = 24, max = 24) List<@NotNull @Min(0) @Max(100_000) Integer> hourlyBudgets
) {
}
