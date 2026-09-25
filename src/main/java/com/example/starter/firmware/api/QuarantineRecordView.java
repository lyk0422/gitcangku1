package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.QuarantineRecord;

/**
 * 设备隔离/解除历史视图。
 */
public record QuarantineRecordView(long id, String deviceId, String action, String reasonCode,
                                  String expectedVersion, String operator, String createdAt) {

    public static QuarantineRecordView of(QuarantineRecord record) {
        return new QuarantineRecordView(record.id(), record.deviceId(), record.action().name(),
                record.reasonCode(), record.expectedVersion(), record.operator(), record.createdAt());
    }
}
