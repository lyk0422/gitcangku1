package com.example.starter.exposure.domain;

/**
 * 归因回执流水 PO，追加写入、不可变。单条提交或批量提交中的每个预占各产生一行，
 * 记录裁决所用窗口快照、窗口区间、回执业务时刻与受理时刻及裁决结果。
 *
 * @param receiptId                 回执流水编号
 * @param requestId                 提交该回执的写操作幂等键
 * @param operatorId                操作者编号
 * @param campaignId                校验通过的归属公告编号
 * @param reservationId             目标预占单编号
 * @param visitorId                 校验通过的归属访客编号
 * @param windowVersion             裁决所用窗口版本（取自预占快照）
 * @param attributionWindowMinutes  裁决所用窗口分钟数（取自预占快照）
 * @param windowStartUtc            窗口起点（=预占时刻，含），epoch 毫秒，UTC
 * @param windowEndUtc              窗口终点（起点 + 窗口分钟，不含），epoch 毫秒，UTC
 * @param receiptAtUtc              回执声明的业务时刻，epoch 毫秒，UTC
 * @param receivedAtUtc             服务端受理（提交）时刻，按提交顺序裁决，epoch 毫秒，UTC
 * @param result                    裁决结果
 * @param attributedAtUtc           引用的首次归因业务时刻；仅 ALREADY_* 回填，其余为 null
 * @param lateReason                LATE_RECEIPT 的可区分原因：AT_OR_AFTER_WINDOW_END/
 *                                  RESERVATION_CANCELLED/RESERVATION_EXPIRED；其余结果为 null
 */
public record AttributionReceipt(
        String receiptId,
        String requestId,
        String operatorId,
        String campaignId,
        String reservationId,
        String visitorId,
        int windowVersion,
        int attributionWindowMinutes,
        long windowStartUtc,
        long windowEndUtc,
        long receiptAtUtc,
        long receivedAtUtc,
        ReceiptResult result,
        Long attributedAtUtc,
        String lateReason
) {
}
