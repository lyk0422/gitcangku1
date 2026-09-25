package com.example.starter.firmware.domain;

/**
 * 设备隔离/解除隔离历史记录，只增不改。
 *
 * @param id            记录ID
 * @param deviceId      设备ID
 * @param operation     操作：QUARANTINE 隔离，UNQUARANTINE 解除隔离
 * @param operatorName  操作运维人标识；解除隔离的操作人必须与隔离人不同
 * @param reasonCode    原因代码：隔离时为隔离原因，解除时为原因已消除的确认说明
 * @param deviceVersion 操作时刻设备当前固件版本
 * @param createdAtUtc  操作时刻，UTC，ISO-8601 格式
 */
public record QuarantineRecord(long id, String deviceId, QuarantineOperation operation,
                               String operatorName, String reasonCode, String deviceVersion,
                               String createdAtUtc) {
}
