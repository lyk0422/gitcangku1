package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动日结快照视图（只读）：固化某 UTC 日每小时的确认数与取消数。
 * 快照来自只增账目，后续活动配置变更不改写已结算日。
 *
 * @param activityId      活动编号
 * @param utcDate         结算的 UTC 日，格式 yyyy-MM-dd
 * @param hours           24 个小时结算条目（0~23 时升序）
 * @param totalConfirmed  当日确认总数，单位次
 * @param totalCancelled  当日释放总数（取消与过期合计），单位次
 * @param snapshottedAtUtc 生成快照的当前时刻，epoch 毫秒，UTC
 */
public record DailySettlementResponse(
        String activityId,
        LocalDate utcDate,
        List<HourSettlement> hours,
        int totalConfirmed,
        int totalCancelled,
        long snapshottedAtUtc
) {
    /**
     * 单个小时结算条目。
     *
     * @param hourOfDay      UTC 小时（0~23）
     * @param confirmedCount 该小时确认数，单位次
     * @param cancelledCount 该小时释放数（取消与过期合计），单位次
     */
    public record HourSettlement(
            int hourOfDay,
            int confirmedCount,
            int cancelledCount
    ) {
    }
}
