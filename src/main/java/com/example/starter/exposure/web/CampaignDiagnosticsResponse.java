package com.example.starter.exposure.web;

import java.util.List;
import java.util.Map;

/**
 * 公告归因诊断只读视图。数量为实际统计值，比例为实际归因占比。
 *
 * @param campaignId                公告编号
 * @param windowVersion             当前窗口版本号（要求值版本）
 * @param attributionWindowMinutes  当前窗口分钟数（要求值）
 * @param evaluatedAtUtc            诊断时刻，epoch 毫秒，UTC（只读统计，不触发结算）
 * @param reservationCounts         各预占状态实际数量，键为 RESERVED/CONFIRMED/ATTRIBUTED/CANCELLED/EXPIRED
 * @param totalReservations         预占单实际总数
 * @param receiptCounts             各回执结果实际数量，键为 ATTRIBUTED/LATE_RECEIPT/ALREADY_ATTRIBUTED/ALREADY_CONFIRMED
 * @param totalReceipts             回执实际总数
 * @param attributedRatio           实际归因比例 = ATTRIBUTED 回执数 / 回执总数；无回执时为 0
 * @param recentReceipts            近期回执明细，按受理时刻、流水编号稳定升序，上限由调用方给定
 */
public record CampaignDiagnosticsResponse(
        String campaignId,
        int windowVersion,
        int attributionWindowMinutes,
        long evaluatedAtUtc,
        Map<String, Integer> reservationCounts,
        int totalReservations,
        Map<String, Integer> receiptCounts,
        int totalReceipts,
        double attributedRatio,
        List<ReceiptItemResult> recentReceipts
) {
}
