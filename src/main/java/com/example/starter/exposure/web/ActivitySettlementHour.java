package com.example.starter.exposure.web;

/**
 * 活动某 UTC 小时日结快照视图。快照首次日结时固化，后续配置变更不改写。
 *
 * @param utcHour        UTC 小时（0～23），区间 [startAtUtc, endAtUtc)
 * @param startAtUtc     小时起点，epoch 毫秒，UTC
 * @param endAtUtc       小时终点，epoch 毫秒，UTC
 * @param confirmedCount 固化确认数（CONFIRMED），单位次
 * @param cancelledCount 固化取消数（CANCELLED，不含过期），单位次
 * @param expiredCount   固化过期数（EXPIRED），单位次
 * @param budget         结算时配置的小时预算；当日无配置为 null
 * @param configVersion  结算时配置版本；当日无配置为 null
 * @param frozen         true=读取的是此前已固化快照；false=本次首次固化
 */
public record ActivitySettlementHour(
        int utcHour,
        long startAtUtc,
        long endAtUtc,
        int confirmedCount,
        int cancelledCount,
        int expiredCount,
        Integer budget,
        Integer configVersion,
        boolean frozen
) {
}
