package com.example.starter.exposure.domain;

/**
 * 活动 PO。公告可归入活动；活动按 UTC 自然日配置总确认额度与 24 个小时预算，
 * 同一套配置在每个 UTC 自然日重复生效。
 *
 * <p>24 个小时预算均为非负整数，且合计必须等于 {@link #dailyTotalCap}。
 * 小时区间采用左闭右开：budgetHour[h] 对应 [h:00, h+1:00)（UTC）。</p>
 *
 * @param activityId    活动编号，全局唯一
 * @param dailyTotalCap 每 UTC 日总确认额度，单位次，非负
 * @param hourlyBudget  长度 24 的数组，hourlyBudget[h] 为 UTC 小时 h 的预算，单位次，非负；
 *                      所有元素之和必须等于 dailyTotalCap
 * @param version       配置版本号，初版为 0；修改配置须携带 expectedVersion 做乐观锁裁决
 * @param createdAtUtc  创建时刻，epoch 毫秒，UTC
 * @param updatedAtUtc  最近一次配置修改时刻，epoch 毫秒，UTC
 */
public record Activity(
        String activityId,
        int dailyTotalCap,
        int[] hourlyBudget,
        long version,
        long createdAtUtc,
        long updatedAtUtc
) {
    public Activity {
        if (hourlyBudget == null || hourlyBudget.length != 24) {
            throw new IllegalArgumentException("hourlyBudget must contain exactly 24 entries");
        }
        // 防御性拷贝，保证 record 不可变
        hourlyBudget = hourlyBudget.clone();
    }

    /** 指定 UTC 小时 h（[0,23]）的预算，单位次。 */
    public int budget(int hour) {
        return hourlyBudget[hour];
    }

    @Override
    public int[] hourlyBudget() {
        return hourlyBudget.clone();
    }
}
