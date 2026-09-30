package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 配置/修改活动某 UTC 日匀速投放请求。24 个小时预算必须均为非负整数且合计等于日总确认额度；
 * 修改已存在的配置需携带当前版本号，已开始小时不可下调。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param expectedVersion 期望的当前配置版本；该日尚无配置时必须为 0
 * @param dailyTotalCap   该日总确认额度，单位次，非负
 * @param hourlyBudgets   24 个小时预算（下标即小时 0..23），单位次，非负且合计等于 dailyTotalCap
 */
public record UpsertActivityDayConfigRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull @PositiveOrZero Integer dailyTotalCap,
        @NotNull @Size(min = 24, max = 24) List<@NotNull @PositiveOrZero Integer> hourlyBudgets
) {
}
