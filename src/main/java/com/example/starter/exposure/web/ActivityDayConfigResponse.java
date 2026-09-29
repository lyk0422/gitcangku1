package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.ActivityDayConfig;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动日投放配置视图。24 个小时预算下标即 UTC 小时（0～23），左闭右开。
 *
 * @param activityId    活动编号
 * @param utcDate       配置所属 UTC 日，格式 yyyy-MM-dd
 * @param dailyTotalCap 当日总确认额度，单位次
 * @param hourlyBudgets 长度 24 的小时预算列表，单位次
 * @param version       当前配置版本（首次为 1，每次修改 +1）
 * @param updatedAtUtc  最近修改时刻，epoch 毫秒，UTC
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
        Integer[] hours = new Integer[ActivityDayConfig.HOURS_PER_DAY];
        for (int h = 0; h < ActivityDayConfig.HOURS_PER_DAY; h++) {
            hours[h] = config.hourlyCap(h);
        }
        return new ActivityDayConfigResponse(
                config.activityId(),
                config.utcDate(),
                config.dailyTotalCap(),
                List.of(hours),
                config.version(),
                config.updatedAtUtc());
    }
}
