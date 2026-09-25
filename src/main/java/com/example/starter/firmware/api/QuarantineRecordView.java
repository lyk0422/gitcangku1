package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.QuarantineRecord;

/**
 * 设备隔离/解除隔离历史记录视图。
 */
public record QuarantineRecordView(long recordId, String deviceId, String operation, String operator,
                                   String reasonCode, String deviceVersion, String createdAtUtc) {

    public static QuarantineRecordView of(QuarantineRecord record) {
        return new QuarantineRecordView(record.id(), record.deviceId(), record.operation().name(),
                record.operatorName(), record.reasonCode(), record.deviceVersion(),
                record.createdAtUtc());
    }
}
