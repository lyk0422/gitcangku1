package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动某 UTC 日的小时账目诊断视图（纯读取，不改变状态）。
 *
 * @param activityId   活动编号
 * @param utcDate      查询的 UTC 日，格式 yyyy-MM-dd
 * @param configured   当日是否已配置预算
 * @param dailyTotal   当日总确认额度，单位次；未配置为 null
 * @param hours        24 个小时诊断项，按 hourOfDay 升序，恒为 24 条
 * @param queriedAtUtc 查询时刻，epoch 毫秒，UTC
 */
public record ActivityDiagnosticsResponse(
        String activityId,
        LocalDate utcDate,
        boolean configured,
        Integer dailyTotal,
        List<ActivityHourDiagnostic> hours,
        long queriedAtUtc
) {
}
