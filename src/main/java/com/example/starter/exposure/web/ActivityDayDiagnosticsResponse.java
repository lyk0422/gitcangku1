package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动某 UTC 日 24 小时投放诊断视图（只读）。时间按 UTC，小时左闭右开。
 *
 * @param activityId    活动编号
 * @param utcDate       诊断所属 UTC 日，格式 yyyy-MM-dd
 * @param generatedAtUtc 诊断生成时刻，epoch 毫秒，UTC
 * @param hours         长度 24 的小时状态，下标即 UTC 小时
 */
public record ActivityDayDiagnosticsResponse(
        String activityId,
        LocalDate utcDate,
        long generatedAtUtc,
        List<ActivityHourStatus> hours
) {
}
