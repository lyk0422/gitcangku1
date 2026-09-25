package com.example.starter.firmware.domain;

/**
 * 被拒回执记录（如隔离设备已开始任务的回执），只增不改。
 *
 * @param id            记录ID
 * @param requestId     被拒回执请求的 requestId，唯一
 * @param taskId        任务ID
 * @param releaseId     所属发布单ID
 * @param deviceId      设备ID
 * @param result        被拒的回执结果（SUCCESS/FAILED）
 * @param reasonCode    拒绝原因代码，如 DEVICE_QUARANTINED
 * @param rejectedAtUtc 拒绝时刻，UTC，ISO-8601 格式
 */
public record RejectedReceipt(long id, String requestId, long taskId, long releaseId, String deviceId,
                              ReceiptResult result, String reasonCode, String rejectedAtUtc) {
}
