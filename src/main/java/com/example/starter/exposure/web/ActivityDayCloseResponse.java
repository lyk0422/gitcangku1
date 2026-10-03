package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动日结视图：固化该 UTC 日每小时的确认数与取消数快照，
 * 后续配置变更不改写已结算数据。只读。
 *
 * @param activityId    活动编号
 * @param utcDate       结算的 UTC 日，格式 yyyy-MM-dd
 * @param dailyTotalCap 当前配置的日总确认额度；未配置为 null
 * @param version       当前配置版本；未配置为 null
 * @param hours         24 个小时快照，下标即小时（0~23，UTC）
 */
public record ActivityDayCloseResponse(
        String activityId,
        LocalDate utcDate,
        Integer dailyTotalCap,
        Integer version,
        List<HourClose> hours
) {
    /**
     * 单小时日结快照。
     *
     * @param hourOfDay 小时（0~23，UTC），区间左闭右开
     * @param budget    该小时预算；未配置为 null
     * @param confirmed 该小时累计确认数快照，单位次
     * @param cancelled 该小时累计取消/过期数快照，单位次
     */
    public record HourClose(
            int hourOfDay,
            Integer budget,
            int confirmed,
            int cancelled
    ) {
    }
}