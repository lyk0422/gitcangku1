package com.example.starter.exposure.domain;

import java.util.List;

/**
 * 活动 PO。活动按 UTC 自然日配置总确认额度（{@link #dailyTotalCap}）与 24 个小时预算
 * （{@link #hourlyBudgets}，下标 0～23 对应 UTC 00～23 时，区间左闭右开）。
 * 24 个预算均为非负整数且合计必须等于日总额。
 *
 * @param activityId      活动编号，全局唯一
 * @param dailyTotalCap   每 UTC 日总确认额度，单位次，非负，等于 24 个小时预算合计
 * @param hourlyBudgets   固定长度 24 的 UTC 小时预算，单位次，非负整数
 * @param version         配置乐观锁版本：创建为 1，每次修改加 1，修改须携带 expectedVersion
 * @param createdAtUtc    创建时刻，epoch 毫秒，UTC
 * @param updatedAtUtc    最近配置修改时刻，epoch 毫秒，UTC
 */
public record Activity(
        String activityId,
        int dailyTotalCap,
        List<Integer> hourlyBudgets,
        int version,
        long createdAtUtc,
        long updatedAtUtc
) {
    /** UTC 小时预算固定 24 个（00～23 时）。 */
    public static final int HOURS_PER_DAY = 24;

    public Activity {
        if (hourlyBudgets == null || hourlyBudgets.size() != HOURS_PER_DAY) {
            throw new IllegalArgumentException("hourlyBudgets must contain exactly 24 hours");
        }
        hourlyBudgets = List.copyOf(hourlyBudgets);
    }

    /** 指定 UTC 小时（0～23）的预算，单位次。 */
    public int budget(int utcHour) {
        return hourlyBudgets.get(utcHour);
    }
}
