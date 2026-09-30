package com.example.starter.exposure.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动某 UTC 日的匀速投放配置 PO。24 个小时预算均为非负整数且合计等于日总确认额度；
 * 小时区间左闭右开。修改需携带 expectedVersion 乐观锁，已开始小时不可下调。
 *
 * @param activityId     活动编号
 * @param utcDate        配置所属 UTC 日
 * @param dailyTotalCap  该日总确认额度，单位次，非负
 * @param hourlyBudgets  24 个小时预算（下标即小时 0..23），单位次，非负且合计等于 dailyTotalCap
 * @param version        配置版本，每次修改 +1
 * @param updatedAtUtc   最近修改时刻（epoch 毫秒，UTC）
 */
public record ActivityDayConfig(
        String activityId,
        LocalDate utcDate,
        int dailyTotalCap,
        List<Integer> hourlyBudgets,
        long version,
        long updatedAtUtc
) {
}
