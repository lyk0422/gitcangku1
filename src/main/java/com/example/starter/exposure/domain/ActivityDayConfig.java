package com.example.starter.exposure.domain;

/**
 * 活动某 UTC 日的预算配置 PO。dailyTotal 为当日总确认额度（单位次），
 * 写入时校验等于 24 个小时预算合计；小时预算存于 activity_hour_budget 表。
 *
 * @param activityId   活动编号
 * @param utcDate      配置所属 UTC 日（java.time.LocalDate 对应的 java.sql.Date）
 * @param dailyTotal   当日总确认额度，单位次，非负整数
 * @param updatedAtUtc 最近一次配置变更时刻（epoch 毫秒，UTC）
 */
public record ActivityDayConfig(
        String activityId,
        java.sql.Date utcDate,
        int dailyTotal,
        long updatedAtUtc
) {
}
