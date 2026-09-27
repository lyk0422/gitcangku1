package com.example.starter.exposure.web;

/**
 * 日结快照单小时视图。
 *
 * @param hourOfDay      UTC 小时，0-23
 * @param confirmedCount 该小时已确认数，单位次
 * @param cancelledCount 该小时已取消数，单位次
 * @param expiredCount   该小时已过期数，单位次
 */
public record SettlementHour(
        int hourOfDay,
        int confirmedCount,
        int cancelledCount,
        int expiredCount
) {
}
