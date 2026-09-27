package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动日结快照视图。某 UTC 日结束（当日最后一刻 + 预占 TTL）后首次查询时固化，
 * 后续配置变更不改写已结算日；hours 恒为 24 条，按 hourOfDay 升序。
 *
 * @param activityId       活动编号
 * @param utcDate          结算的 UTC 日，格式 yyyy-MM-dd
 * @param hours            24 个小时结算项
 * @param snapshottedAtUtc 快照固化时刻，epoch 毫秒，UTC
 */
public record SettlementResponse(
        String activityId,
        LocalDate utcDate,
        List<SettlementHour> hours,
        long snapshottedAtUtc
) {
}
