package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Activity;

import java.util.List;

/**
 * 活动视图。
 *
 * @param activityId     活动编号
 * @param dailyTotalCap  每 UTC 日总确认额度，单位次
 * @param hourlyBudgets  24 个小时预算，按下标对应 UTC 小时 0~23；0 表示该小时未配置预算
 * @param version        当前配置版本号
 * @param createdAtUtc   创建时刻，epoch 毫秒，UTC
 */
public record ActivityResponse(
        String activityId,
        int dailyTotalCap,
        List<Integer> hourlyBudgets,
        int version,
        long createdAtUtc
) {
    public static ActivityResponse from(Activity activity, List<Integer> hourlyBudgets) {
        return new ActivityResponse(
                activity.activityId(),
                activity.dailyTotalCap(),
                List.copyOf(hourlyBudgets),
                activity.version(),
                activity.createdAtUtc());
    }
}
