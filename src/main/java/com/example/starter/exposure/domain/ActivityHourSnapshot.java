package com.example.starter.exposure.domain;

import java.time.LocalDate;

/**
 * 活动某 UTC 日某小时的日结快照 PO。日结查询首次执行时固化每小时确认数与取消数
 * （含过期数），后续配置变更不改写已结算日。
 *
 * @param activityId     活动编号
 * @param utcDate        结算所属 UTC 日
 * @param utcHour        UTC 小时（0～23），区间 [hour, hour+1)
 * @param confirmedCount 该小时已固化的确认数（CONFIRMED），单位次
 * @param cancelledCount 该小时已固化的取消数（CANCELLED，不含过期），单位次
 * @param expiredCount   该小时已固化的过期数（EXPIRED），单位次
 * @param configVersion  结算时该日配置版本；当日无配置为 null
 * @param settledAtUtc   固化时刻（epoch 毫秒，UTC）
 */
public record ActivityHourSnapshot(
        String activityId,
        LocalDate utcDate,
        int utcHour,
        int confirmedCount,
        int cancelledCount,
        int expiredCount,
        Integer configVersion,
        long settledAtUtc
) {
}
