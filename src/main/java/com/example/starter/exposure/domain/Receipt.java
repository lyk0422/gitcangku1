package com.example.starter.exposure.domain;

/**
 * 曝光确认回执 PO。每行不可变：回执时刻、预占快照、窗口版本与裁决结果
 * 在受理时固定，随后续窗口调整或其他操作不改写。
 *
 * @param receiptSeq      提交顺序号（数据库自增，全局单调，用于稳定排序）
 * @param receiptId       回执编号，全局唯一
 * @param reservationId   所属预占单编号
 * @param campaignId      所属公告编号（受理时快照）
 * @param visitorId       合成访客编号（受理时快照）
 * @param operatorId      提交回执的操作者
 * @param receiptAtUtc    回执上报时刻，epoch 毫秒，UTC
 * @param reservedAtUtc   预占时刻快照，epoch 毫秒，UTC
 * @param windowVersion   预占时刻的归因窗口版本号快照
 * @param windowMinutes   预占时刻的归因窗口分钟数快照
 * @param result          归因裁决结果
 * @param batchRequestId  所属批量回执请求的幂等键；单笔回执为 null
 * @param createdAtUtc    服务端受理时刻，epoch 毫秒，UTC
 */
public record Receipt(
        long receiptSeq,
        String receiptId,
        String reservationId,
        String campaignId,
        String visitorId,
        String operatorId,
        long receiptAtUtc,
        long reservedAtUtc,
        int windowVersion,
        int windowMinutes,
        ReceiptResult result,
        String batchRequestId,
        long createdAtUtc
) {
}
