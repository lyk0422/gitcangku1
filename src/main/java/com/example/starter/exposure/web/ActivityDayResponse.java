package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动某 UTC 日的投放诊断只读视图：活动配置的小时预算与账目实际计数并列，
 * 并给出要求值（预算）、实际占用与剩余差额。时间一律按 UTC，小时区间左闭右开。
 *
 * @param activityId      活动编号
 * @param utcDate         诊断的 UTC 日
 * @param dailyTotalCap   活动日总确认额度（要求值），单位次
 * @param currentUtcHour  诊断时刻的当前 UTC 小时（0～23）
 * @param hours           固定 24 项小时诊断，按小时升序
 * @param diagnosedAtUtc  诊断时刻，epoch 毫秒，UTC
 */
public record ActivityDayResponse(
        String activityId,
        LocalDate utcDate,
        int dailyTotalCap,
        int currentUtcHour,
        List<Hour> hours,
        long diagnosedAtUtc
) {
    /**
     * 单个 UTC 小时诊断。
     *
     * @param utcHour         UTC 小时（0～23），区间 [utcHour:00, utcHour+1:00)
     * @param hourBudget      该小时预算（要求值），单位次
     * @param reservedCount   当前占用数（RESERVED 与 CONFIRMED 合计，实际值），单位次
     * @param confirmedCount  累计确认数，单位次
     * @param cancelledCount  累计取消数，单位次
     * @param expiredCount    累计过期数，单位次
     * @param remaining       剩余可预占名额（差额 = 预算 - 当前占用），单位次
     */
    public record Hour(
            int utcHour,
            int hourBudget,
            int reservedCount,
            int confirmedCount,
            int cancelledCount,
            int expiredCount,
            int remaining
    ) {
    }
}
