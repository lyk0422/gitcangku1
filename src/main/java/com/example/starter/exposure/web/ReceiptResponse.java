package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Receipt;
import com.example.starter.exposure.domain.ReceiptResult;

/**
 * 回执视图。时长字段同时给出实际值（delayMillis）、要求值（windowMillis）
 * 与差额（lateByMillis），便于核对窗口裁决。
 *
 * @param receiptId     回执编号
 * @param reservationId 所属预占单编号
 * @param campaignId    所属公告编号
 * @param visitorId     合成访客编号
 * @param operator      提交回执的操作者
 * @param receiptAtUtc  回执上报时刻，epoch 毫秒，UTC
 * @param reservedAtUtc 预占时刻快照，epoch 毫秒，UTC
 * @param windowVersion 预占时刻的归因窗口版本号快照
 * @param windowMinutes 预占时刻的归因窗口分钟数快照
 * @param result        归因裁决结果（ATTRIBUTED / LATE_RECEIPT）
 * @param delayMillis   实际回执时延 = receiptAtUtc - reservedAtUtc，单位毫秒
 * @param windowMillis  裁决所用窗口 = windowMinutes × 60000，单位毫秒
 * @param lateByMillis  迟到差额 = delayMillis - windowMillis，单位毫秒；窗口内为 null
 * @param createdAtUtc  服务端受理时刻，epoch 毫秒，UTC
 */
public record ReceiptResponse(
        String receiptId,
        String reservationId,
        String campaignId,
        String visitorId,
        String operator,
        long receiptAtUtc,
        long reservedAtUtc,
        int windowVersion,
        int windowMinutes,
        ReceiptResult result,
        long delayMillis,
        long windowMillis,
        Long lateByMillis,
        long createdAtUtc
) {
    public static ReceiptResponse from(Receipt r) {
        long windowMillis = r.windowMinutes() * 60_000L;
        long delayMillis = r.receiptAtUtc() - r.reservedAtUtc();
        Long lateByMillis = delayMillis >= windowMillis ? delayMillis - windowMillis : null;
        return new ReceiptResponse(
                r.receiptId(),
                r.reservationId(),
                r.campaignId(),
                r.visitorId(),
                r.operatorId(),
                r.receiptAtUtc(),
                r.reservedAtUtc(),
                r.windowVersion(),
                r.windowMinutes(),
                r.result(),
                delayMillis,
                windowMillis,
                lateByMillis,
                r.createdAtUtc());
    }
}
