package com.example.starter.firmware.api;

import java.util.List;

/**
 * 设备隔离/解除历史响应。
 */
public record QuarantineHistoryResponse(String deviceId, List<QuarantineRecordView> records) {
}
