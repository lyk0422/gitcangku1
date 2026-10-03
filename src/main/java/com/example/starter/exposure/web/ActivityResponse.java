package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Activity;

import java.util.List;

/**
 * 活动视图。
 *
 * @param activityId      活动编号
 * @param dailyConfirmCap 每 UTC 日总确认额度，单位次
 * @param hourlyBudgets   24 个 UTC 小时预算（0~23 时，单位次）；未配置为 null
 * @param version         当前配置版本
 * @param createdAtUtc    创建时刻，epoch 毫秒，UTC
 */
public record ActivityResponse(
        String activityId,
        int dailyConfirmCap,
        List<Integer> hourlyBudgets,
        long version,
        long createdAtUtc
) {
    public static ActivityResponse from(Activity activity, List<Integer> hourlyBudgets) {
        return new ActivityResponse(
                activity.activityId(),
                activity.dailyConfirmCap(),
                hourlyBudgets,
                activity.version(),
                activity.createdAtUtc());
    }
}
