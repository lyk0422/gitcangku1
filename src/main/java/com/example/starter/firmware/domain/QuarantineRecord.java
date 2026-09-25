package com.example.starter.firmware.domain;

/**
 * 设备隔离/解除历史记录，只增不改。
 *
 * @param id              记录ID
 * @param deviceId        设备ID
 * @param action          操作类型：QUARANTINE 隔离；RELEASE 解除
 * @param reasonCode      原因代码
 * @param expectedVersion 操作提交时的设备当前版本快照
 * @param operator        操作者工号
 * @param createdAt       操作时间（服务器本地时区，ISO-8601）
 */
public record QuarantineRecord(long id, String deviceId, QuarantineAction action, String reasonCode,
                               String expectedVersion, String operator, String createdAt) {
}
