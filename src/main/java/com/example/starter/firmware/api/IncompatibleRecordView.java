package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.IncompatibleRecord;

/**
 * 设备不兼容拦截记录视图。
 */
public record IncompatibleRecordView(String deviceId, String hardwareModel, String firmwareVersion,
                                     int matrixVersion, String blockedAtUtc) {

    public static IncompatibleRecordView of(IncompatibleRecord record) {
        return new IncompatibleRecordView(record.deviceId(), record.hardwareModel(),
                record.firmwareVersion(), record.matrixVersion(), record.blockedAtUtc());
    }
}
