package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动某 UTC 日预算配置视图。
 *
 * @param activityId   活动编号
 * @param utcDate      配置所属 UTC 日，格式 yyyy-MM-dd
 * @param dailyTotal   当日总确认额度，单位次，等于 hourBudgets 合计
 * @param hourBudgets  24 个小时（UTC，0-23）预算，单位次
 * @param version      本次变更后的活动配置版本号
 * @param updatedAtUtc 本次配置变更时刻，epoch 毫秒，UTC
 */
public record ActivityBudgetResponse(
        String activityId,
        LocalDate utcDate,
        int dailyTotal,
        List<Integer> hourBudgets,
        int version,
        long updatedAtUtc
) {
}
