package com.example.starter.exposure.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动某 UTC 自然日的匀速投放配置。24 个小时预算均为非负整数且合计等于日总额；
 * version 从 1 开始，每次成功修改 +1，修改需携带 expectedVersion。
 *
 * @param activityId    活动编号
 * @param utcDate       配置生效的 UTC 日，左闭右开 [当天00:00, 次日00:00)
 * @param dailyTotalCap 该 UTC 日总确认额度，单位次，取值 0~100000
 * @param hourlyBudgets 24 个小时预算，下标即小时（0~23，UTC），单位次；合计等于 dailyTotalCap
 * @param version       配置版本，首次配置为 1
 * @param updatedAtUtc  最近一次修改时刻，epoch 毫秒，UTC
 */
public record ActivityDayConfig(
        String activityId,
        LocalDate utcDate,
        int dailyTotalCap,
        List<Integer> hourlyBudgets,
        int version,
        long updatedAtUtc
) {
}