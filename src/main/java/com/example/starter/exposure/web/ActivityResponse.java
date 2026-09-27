package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Activity;

import java.util.List;

/**
 * 活动配置视图。
 *
 * @param activityId      活动编号
 * @param dailyTotalCap   每 UTC 日总确认额度，单位次，等于 24 个小时预算合计
 * @param hourlyBudgets   固定 24 项，依次为 UTC 00～23 时预算，单位次
 * @param version         当前配置版本（创建为 1，每次修改加 1）
 * @param createdAtUtc    创建时刻，epoch 毫秒，UTC
 * @param updatedAtUtc    最近配置修改时刻，epoch 毫秒，UTC
 */
public record ActivityResponse(
        String activityId,
        int dailyTotalCap,
        List<Integer> hourlyBudgets,
        int version,
        long createdAtUtc,
        long updatedAtUtc
) {
    public static ActivityResponse from(Activity activity) {
        return new ActivityResponse(
                activity.activityId(),
                activity.dailyTotalCap(),
                activity.hourlyBudgets(),
                activity.version(),
                activity.createdAtUtc(),
                activity.updatedAtUtc());
    }
}
