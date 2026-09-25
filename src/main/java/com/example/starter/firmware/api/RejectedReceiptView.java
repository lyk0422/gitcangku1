package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.RejectedReceipt;

/**
 * 被拒回执记录视图。
 */
public record RejectedReceiptView(long recordId, String requestId, long taskId, long releaseId,
                                  String deviceId, String result, String reasonCode,
                                  String rejectedAtUtc) {

    public static RejectedReceiptView of(RejectedReceipt record) {
        return new RejectedReceiptView(record.id(), record.requestId(), record.taskId(),
                record.releaseId(), record.deviceId(), record.result().name(), record.reasonCode(),
                record.rejectedAtUtc());
    }
}
