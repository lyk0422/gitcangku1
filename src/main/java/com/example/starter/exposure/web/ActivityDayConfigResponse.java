package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.ActivityDayConfig;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动某 UTC 日配置视图。
 *
 * @param activityId    活动编号
 * @param utcDate       配置所属 UTC 日，格式 yyyy-MM-dd
 * @param dailyTotalCap 日总确认额度，单位次
 * @param hourlyBudgets 24 个小时预算，下标即小时，单位次
 * @param version       当前配置版本
 * @param updatedAtUtc  最近一次修改时刻，epoch 毫秒，UTC
 */
public record ActivityDayConfigResponse(
        String activityId,
        LocalDate utcDate,
        int dailyTotalCap,
        List<Integer> hourlyBudgets,
        int version,
        long updatedAtUtc
) {
    public static ActivityDayConfigResponse from(ActivityDayConfig config) {
        return new ActivityDayConfigResponse(config.activityId(), config.utcDate(),
                config.dailyTotalCap(), config.hourlyBudgets(), config.version(),
                config.updatedAtUtc());
    }
}