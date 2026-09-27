package com.example.starter.firmware.domain;

/**
 * 设备投放任务，同设备同发布单最多一条。完整性字段对无分片清单的遗留发布单取空值/0。
 *
 * @param id                     任务ID
 * @param releaseId              所属发布单ID
 * @param deviceId               设备ID
 * @param status                 任务状态
 * @param firstResult            首次安装回执结果（SUCCESS/FAILED），未回执为 null；INTEGRITY_FAILED 不写
 * @param attemptNo              当前尝试代次，从1开始，完整性失败后重新拉取加一
 * @param failureReason          当前代次完整性失败原因码（{@link IntegrityFailureReason}），未失败为 null
 * @param requiredShards         当前代次要求分片数（拉取时快照），0为遗留发布单
 * @param receivedShards         当前代次已接收的不同分片序号数（实际值）
 * @param packageDigest          当前代次完整包摘要快照，拉取时固化；遗留发布单为 null
 * @param attemptFinalizedAtUtc  当前代次完整性判定时刻，UTC ISO-8601；接收中为 null
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, int attemptNo, String failureReason,
                          int requiredShards, int receivedShards, String packageDigest,
                          String attemptFinalizedAtUtc) {
}
