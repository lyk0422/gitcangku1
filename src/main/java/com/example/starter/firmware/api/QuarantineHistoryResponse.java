package com.example.starter.firmware.api;

import java.util.List;

/**
 * 设备隔离历史响应（只读），按操作时间升序。
 */
public record QuarantineHistoryResponse(String deviceId, List<QuarantineRecordView> records) {
}
