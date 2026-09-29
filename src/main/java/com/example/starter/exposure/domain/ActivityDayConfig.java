package com.example.starter.exposure.domain;

import java.time.LocalDate;

/**
 * 活动某 UTC 日投放配置 PO。24 个小时预算均为非负整数且合计等于
 * {@link #dailyTotalCap}；小时采用左闭右开区间 [h, h+1)，时间按 UTC。
 *
 * @param activityId    活动编号
 * @param utcDate       配置所属 UTC 日
 * @param dailyTotalCap 当日总确认额度，单位次，非负
 * @param hourlyCaps    长度 24 的小时预算数组，下标即 UTC 小时（0～23），单位次，非负
 * @param version       配置版本，首次为 1，每次修改 +1；修改须携带 expectedVersion
 * @param updatedAtUtc  最近修改时刻（epoch 毫秒，UTC）
 */
public record ActivityDayConfig(
        String activityId,
        LocalDate utcDate,
        int dailyTotalCap,
        int[] hourlyCaps,
        int version,
        long updatedAtUtc
) {

    public static final int HOURS_PER_DAY = 24;

    public ActivityDayConfig {
        if (hourlyCaps == null || hourlyCaps.length != HOURS_PER_DAY) {
            throw new IllegalArgumentException("hourlyCaps must contain exactly 24 entries");
        }
    }

    /** 指定小时预算，hour 取值 0～23。 */
    public int hourlyCap(int hour) {
        return hourlyCaps[hour];
    }
}
