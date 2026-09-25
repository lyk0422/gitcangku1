package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.RejectedReceipt;

/**
 * 被拒回执视图：隔离期间进行中任务回执被门禁拒绝的留痕，解除隔离后仍不可改写。
 */
public record RejectedReceiptView(long id, long taskId, long releaseId, String deviceId,
                                 String submittedResult, String rejectCode, String createdAt) {

    public static RejectedReceiptView of(RejectedReceipt record) {
        return new RejectedReceiptView(record.id(), record.taskId(), record.releaseId(), record.deviceId(),
                record.submittedResult().name(), record.rejectCode(), record.createdAt());
    }
}
