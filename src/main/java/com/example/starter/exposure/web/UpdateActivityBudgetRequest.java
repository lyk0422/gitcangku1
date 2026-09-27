package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * 配置活动某 UTC 日预算请求。hourBudgets 必须恰好 24 个非负整数且合计等于 dailyTotal；
 * expectedVersion 为乐观锁版本，必须等于活动当前版本。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param utcDate         配置所属 UTC 日，格式 yyyy-MM-dd
 * @param dailyTotal      当日总确认额度，单位次，必须等于 hourBudgets 合计
 * @param hourBudgets     24 个小时（UTC，0-23）预算，单位次，均为非负整数
 * @param expectedVersion 期望的活动当前配置版本号
 */
public record UpdateActivityBudgetRequest(
        @jakarta.validation.constraints.NotBlank @Size(max = 64) String requestId,
        @NotNull LocalDate utcDate,
        @NotNull @Min(0) @Max(2_400_000) Integer dailyTotal,
        @NotNull @Size(min = 24, max = 24) List<@NotNull @Min(0) @Max(100_000) Integer> hourBudgets,
        @NotNull @Min(0) Integer expectedVersion
) {
}
