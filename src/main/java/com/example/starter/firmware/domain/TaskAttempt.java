package com.example.starter.firmware.domain;

/**
 * 任务尝试代次历史，只增不改：重新拉取开新代次时旧代次记录保留。
 *
 * @param id               记录ID
 * @param taskId           所属任务ID
 * @param attemptNo        代次号，从1开始
 * @param releaseId        代次建立时发布单ID（快照）
 * @param releaseVersion   代次建立时发布单版本号（快照，后续扩量/恢复不改写）
 * @param status           代次状态
 * @param requiredShards   要求分片数（快照）
 * @param receivedShards   已接收的不同分片序号数（实际值）
 * @param packageDigest    完整包摘要（快照）
 * @param aggregateDigest  完整集合聚合摘要，仅终态判定时固化，接收中为 null
 * @param failureReason    完整性失败原因码，仅 INTEGRITY_FAILED 非 null
 * @param createdAtUtc     代次建立时刻，UTC ISO-8601
 * @param finalizedAtUtc   完整性判定时刻，UTC ISO-8601；接收中为 null
 */
public record TaskAttempt(long id, long taskId, int attemptNo, long releaseId, int releaseVersion,
                          AttemptStatus status, int requiredShards, int receivedShards,
                          String packageDigest, String aggregateDigest, String failureReason,
                          String createdAtUtc, String finalizedAtUtc) {
}
