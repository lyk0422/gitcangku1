package com.example.starter.exposure.web;

/**
 * 单条回执裁决结果视图。区间左闭右开：[windowStartUtc, windowEndUtc)。
 *
 * @param reservationId             目标预占单编号
 * @param campaignId                校验通过的归属公告
 * @param visitorId                 校验通过的归属访客
 * @param result                    裁决结果：ATTRIBUTED/LATE_RECEIPT/ALREADY_ATTRIBUTED/ALREADY_CONFIRMED
 * @param windowVersion             裁决所用窗口版本（预占快照，不可变）
 * @param attributionWindowMinutes  裁决所用窗口分钟数（要求值，预占快照）
 * @param windowStartUtc            窗口起点（=预占时刻，含），epoch 毫秒，UTC
 * @param windowEndUtc              窗口终点（起点 + 窗口分钟，不含），epoch 毫秒，UTC
 * @param receiptAtUtc              回执业务时刻（实际值），epoch 毫秒，UTC
 * @param withinWindow              回执时刻是否实际落在窗口内（true=窗口内；false=窗口外）
 * @param deltaFromStartMillis      回执时刻相对窗口起点的差额（实际值-要求起点），毫秒；负值表示早于预占
 * @param deltaToEndMillis          窗口终点相对回执时刻的差额（要求终点-实际值），毫秒；负值/零表示已到窗口外
 * @param attributedAtUtc           引用的首次归因业务时刻；仅 ALREADY_* 回填，其余为 null
 * @param lateReason                LATE_RECEIPT 的可区分原因：AT_OR_AFTER_WINDOW_END（回执时刻不早于窗口终点）/
 *                                  RESERVATION_EXPIRED（预占已过期）；其余结果为 null。
 *                                  回执时刻早于预占属于整批 422，不产生流水
 */
public record ReceiptItemResult(
        String reservationId,
        String campaignId,
        String visitorId,
        String result,
        int windowVersion,
        int attributionWindowMinutes,
        long windowStartUtc,
        long windowEndUtc,
        long receiptAtUtc,
        boolean withinWindow,
        long deltaFromStartMillis,
        long deltaToEndMillis,
        Long attributedAtUtc,
        String lateReason
) {
}
