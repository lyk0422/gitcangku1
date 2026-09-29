package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动某 UTC 日日结视图。日结查询固化每小时确认数与取消数（含过期数）快照，
 * 已固化小时后续不再改写。
 *
 * @param activityId    活动编号
 * @param utcDate       结算所属 UTC 日，格式 yyyy-MM-dd
 * @param settledAtUtc  本次日结执行时刻，epoch 毫秒，UTC
 * @param hours         长度 24 的小时结算结果，下标即 UTC 小时
 */
public record ActivityDaySettlementResponse(
        String activityId,
        LocalDate utcDate,
        long settledAtUtc,
        List<ActivitySettlementHour> hours
) {
}
