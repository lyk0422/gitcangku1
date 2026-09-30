package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动某 UTC 日 24 小时匀速投放诊断视图（只读）。
 *
 * @param activityId    活动编号
 * @param utcDate       诊断的 UTC 日，格式 yyyy-MM-dd
 * @param dailyTotalCap 日总确认额度，单位次
 * @param generatedAtUtc 诊断生成时刻，epoch 毫秒，UTC
 * @param hours         按小时升序的 24 行诊断，小时区间 [h:00, h+1:00) 左闭右开
 */
public record HourDiagnosticResponse(
        String activityId,
        LocalDate utcDate,
        int dailyTotalCap,
        long generatedAtUtc,
        List<HourStatus> hours
) {
    /**
     * 单个 UTC 小时的投放状态。数量均为实际值，单位次。
     *
     * @param hour           UTC 小时序号（0～23，区间左闭右开）
     * @param hourStartUtc   该小时起始时刻，epoch 毫秒，UTC（区间左端点）
     * @param budget         该小时配置预算，单位次（要求值）
     * @param used           该小时当前占用名额（RESERVED 与 CONFIRMED 合计），单位次（实际值）
     * @param reservedCount  该小时当前仍为 RESERVED 的预占数
     * @param confirmedCount 该小时已确认预占数
     * @param cancelledCount 该小时主动取消的预占数（取消已释放同小时名额）
     * @param expiredCount   该小时过期释放的预占数
     * @param remaining      该小时剩余名额 = budget - used（差额，非负）
     */
    public record HourStatus(
            int hour,
            long hourStartUtc,
            int budget,
            int used,
            int reservedCount,
            int confirmedCount,
            int cancelledCount,
            int expiredCount,
            int remaining
    ) {
    }
}
