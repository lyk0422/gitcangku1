package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动小时额度诊断视图：当前占用、剩余与预算对照。只读。
 *
 * @param activityId 活动编号
 * @param utcDate    诊断的 UTC 日，格式 yyyy-MM-dd
 * @param version    当前配置版本；未配置为 null
 * @param hours      24 个小时诊断项，下标即小时（0~23，UTC）
 * @param diagnosedAtUtc 诊断时刻，epoch 毫秒，UTC
 */
public record ActivityDiagnosticsResponse(
        String activityId,
        LocalDate utcDate,
        Integer version,
        List<HourDiagnostics> hours,
        long diagnosedAtUtc
) {
    /**
     * 单小时诊断项。
     *
     * @param hourOfDay 小时（0~23，UTC），区间左闭右开
     * @param budget    该小时预算；未配置为 null
     * @param used      当前占用数（RESERVED 与 CONFIRMED 合计），单位次
     * @param remaining 剩余额度（budget - used）；未配置为 null
     * @param confirmed 该小时累计确认数，单位次
     * @param cancelled 该小时累计取消/过期数，单位次
     */
    public record HourDiagnostics(
            int hourOfDay,
            Integer budget,
            int used,
            Integer remaining,
            int confirmed,
            int cancelled
    ) {
    }
}