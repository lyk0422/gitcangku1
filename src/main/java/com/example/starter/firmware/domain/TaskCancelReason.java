package com.example.starter.firmware.domain;

/**
 * 任务不可变取消原因。
 *
 * @param id         记录ID
 * @param taskId     被取消的任务ID
 * @param releaseId  任务所属发布单ID
 * @param deviceId   设备ID
 * @param reasonCode 取消原因代码：DEVICE_QUARANTINED 设备隔离；RELEASE_CANCELLED 发布单取消
 * @param detail     取消原因明细
 * @param operator   触发取消的操作者；发布单取消路径记为 SYSTEM
 * @param createdAt  取消时间（服务器本地时区，ISO-8601）
 */
public record TaskCancelReason(long id, long taskId, long releaseId, String deviceId, String reasonCode,
                               String detail, String operator, String createdAt) {
}
