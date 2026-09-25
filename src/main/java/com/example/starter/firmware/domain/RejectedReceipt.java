package com.example.starter.firmware.domain;

/**
 * 隔离门禁被拒回执记录，只增不改；解除隔离后历史记录仍不可改写。
 *
 * @param id              记录ID
 * @param taskId          提交回执的任务ID
 * @param releaseId       任务所属发布单ID
 * @param deviceId        提交回执的设备ID
 * @param submittedResult 设备提交的回执结果：SUCCESS 或 FAILED
 * @param rejectCode      拒绝原因代码：DEVICE_QUARANTINED 设备隔离中
 * @param createdAt       回执被拒时间（服务器本地时区，ISO-8601）
 */
public record RejectedReceipt(long id, long taskId, long releaseId, String deviceId,
                              ReceiptResult submittedResult, String rejectCode, String createdAt) {
}
