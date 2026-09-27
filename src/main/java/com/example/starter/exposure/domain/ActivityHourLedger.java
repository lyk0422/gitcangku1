package com.example.starter.exposure.domain;

/**
 * 活动小时额度账目 PO。按 活动 × UTC 日 × 小时（UTC，0-23）记账，单位次。
 * 预占占用 reserved，确认时 reserved 转 confirmed，取消/过期时 reserved 转 cancelled/expired；
 * 全部计数非负，已确认名额永不释放。
 *
 * @param activityId     活动编号
 * @param utcDate        账目所属 UTC 日（java.time.LocalDate 对应的 java.sql.Date）
 * @param hourOfDay      UTC 小时，0-23
 * @param reservedCount  当前未确认预占占用数
 * @param confirmedCount 已确认数（终态，不释放）
 * @param cancelledCount 已取消数（终态）
 * @param expiredCount   已过期数（终态）
 */
public record ActivityHourLedger(
        String activityId,
        java.sql.Date utcDate,
        int hourOfDay,
        int reservedCount,
        int confirmedCount,
        int cancelledCount,
        int expiredCount
) {
    /** 当前小时已占用名额（未确认预占 + 已确认），用于与小时预算比较。 */
    public int occupied() {
        return reservedCount + confirmedCount;
    }
}
