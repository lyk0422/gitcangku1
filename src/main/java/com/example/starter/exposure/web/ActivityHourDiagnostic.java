package com.example.starter.exposure.web;

/**
 * 活动某 UTC 日单小时诊断视图。
 *
 * @param hourOfDay      UTC 小时，0-23
 * @param budget         该小时预算，单位次；当日未配置预算为 null
 * @param reservedCount  当前未确认预占占用数（可能含尚未结算的过期单，为账目原值）
 * @param confirmedCount 已确认数
 * @param cancelledCount 已取消数
 * @param expiredCount   已过期数
 * @param remaining      该小时剩余可预占数（budget - reserved - confirmed）；未配置预算为 null
 */
public record ActivityHourDiagnostic(
        int hourOfDay,
        Integer budget,
        int reservedCount,
        int confirmedCount,
        int cancelledCount,
        int expiredCount,
        Integer remaining
) {
}
