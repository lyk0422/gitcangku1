package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动某 UTC 日结视图。UTC 日结束后首次查询固化快照，后续配置变更不改写已结算日；
 * 当日或未到的日返回实时统计。
 *
 * @param activityId   活动编号
 * @param utcDate      查询的 UTC 日，格式 yyyy-MM-dd
 * @param settled      是否已固化（该 UTC 日已结束）
 * @param settledAtUtc 快照固化时刻，epoch 毫秒，UTC；未固化为 null
 * @param hours        24 个小时结算明细（下标即小时 0..23）
 */
public record ActivityDaySettlementResponse(
        String activityId,
        LocalDate utcDate,
        boolean settled,
        Long settledAtUtc,
        List<ActivityHourSettlement> hours
) {
    /**
     * 单个小时的结算明细。
     *
     * @param hourOfDay      小时序号，取值 0..23
     * @param confirmedCount 该小时确认数，单位次
     * @param cancelledCount 该小时取消数，单位次
     * @param expiredCount   该小时过期数，单位次
     * @param reservedCount  该小时仍为 RESERVED 的数量（仅实时统计可能非 0），单位次
     */
    public record ActivityHourSettlement(
            int hourOfDay,
            int confirmedCount,
            int cancelledCount,
            int expiredCount,
            int reservedCount
    ) {
    }
}
