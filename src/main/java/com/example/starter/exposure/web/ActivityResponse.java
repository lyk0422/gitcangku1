package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Activity;

import java.util.List;

/**
 * 活动视图。
 *
 * @param activityId    活动编号
 * @param dailyTotalCap 每 UTC 日总确认额度，单位次
 * @param hourlyBudget  24 个小时预算，hourlyBudget[h] 对应 UTC 小时 h（左闭右开），单位次
 * @param version       当前配置版本号；初版为 0，每次成功修改 +1
 * @param createdAtUtc  创建时刻，epoch 毫秒，UTC
 * @param updatedAtUtc  最近配置修改时刻，epoch 毫秒，UTC
 */
public record ActivityResponse(
        String activityId,
        int dailyTotalCap,
        List<Integer> hourlyBudget,
        long version,
        long createdAtUtc,
        long updatedAtUtc
) {
    public static ActivityResponse from(Activity activity) {
        int[] budget = activity.hourlyBudget();
        List<Integer> list = new java.util.ArrayList<>(24);
        for (int value : budget) {
            list.add(value);
        }
        return new ActivityResponse(
                activity.activityId(),
                activity.dailyTotalCap(),
                list,
                activity.version(),
                activity.createdAtUtc(),
                activity.updatedAtUtc());
    }
}
