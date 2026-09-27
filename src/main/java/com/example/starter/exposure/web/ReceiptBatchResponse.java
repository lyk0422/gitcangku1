package com.example.starter.exposure.web;

import java.util.List;

/**
 * 批量回执提交结果（首次完整快照；同 requestId 同参重放原样返回）。
 * 数量均为实际裁决值；比例为实际归因占比。
 *
 * @param requestId               幂等键
 * @param operatorId              操作者
 * @param receivedAtUtc           服务端受理（提交裁决）时刻，epoch 毫秒，UTC
 * @param totalCount              实际裁决条目数量
 * @param attributedCount         结果为 ATTRIBUTED 的实际数量
 * @param lateCount               结果为 LATE_RECEIPT 的实际数量
 * @param alreadyAttributedCount  结果为 ALREADY_ATTRIBUTED 的实际数量
 * @param alreadyConfirmedCount   结果为 ALREADY_CONFIRMED 的实际数量
 * @param attributedRatio         实际归因比例 = attributedCount / totalCount，区间 [0,1]
 * @param results                 每条回执裁决明细，按规范化对象集合顺序（预占编号升序）稳定返回
 */
public record ReceiptBatchResponse(
        String requestId,
        String operatorId,
        long receivedAtUtc,
        int totalCount,
        int attributedCount,
        int lateCount,
        int alreadyAttributedCount,
        int alreadyConfirmedCount,
        double attributedRatio,
        List<ReceiptItemResult> results
) {
}
