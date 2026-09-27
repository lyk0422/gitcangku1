package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动小时诊断视图：按当前时刻实时计算某 UTC 日每小时状态，只读不改变任何状态。
 *
 * @param activityId     活动编号
 * @param utcDate        诊断的 UTC 日，格式 yyyy-MM-dd
 * @param diagnosedAtUtc 诊断所用当前时刻，epoch 毫秒，UTC
 * @param hours          恰好 24 个小时诊断项，按下标对应 UTC 小时 0~23
 */
public record ActivityHourDiagnosticsResponse(
        String activityId,
        LocalDate utcDate,
        long diagnosedAtUtc,
        List<HourDiagnostics> hours
) {
    /**
     * 单小时诊断项。
     *
     * @param hourOfDay      UTC 小时，0~23
     * @param budget         当前配置的该小时预算，单位次；0 表示未配置
     * @param activeReserved 仍有效（未到期）的 RESERVED 预占数，单位次
     * @param pendingExpired 已到期但尚未结算的 RESERVED 预占数，单位次
     * @param confirmed      已确认数，单位次
     * @param cancelled      已取消数，单位次
     * @param expired        已过期数，单位次
     */
    public record HourDiagnostics(int hourOfDay, int budget, int activeReserved, int pendingExpired,
                                  int confirmed, int cancelled, int expired) {
    }
}
