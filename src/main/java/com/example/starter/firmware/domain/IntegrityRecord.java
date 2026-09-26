package com.example.starter.firmware.domain;

/**
 * 分片完整性判定记录，只增不改。
 *
 * @param id                     核验记录ID
 * @param taskId                 任务ID
 * @param attempt                核验时的尝试代次
 * @param releaseId              所属发布单ID快照
 * @param firmwareVersion        核验时固化的目标固件版本
 * @param result                 核验结果：INSTALLABLE / INTEGRITY_FAILED
 * @param reason                 失败原因，成功为 null
 * @param receivedCount          判定时该代次已接收分片数
 * @param requiredCount          清单要求的分片总数
 * @param computedPackageDigest  按已接收完整集合计算的聚合摘要；集合不完整时为 null
 * @param expectedPackageDigest  清单登记的完整包聚合摘要
 * @param decidedAtUtc           判定时刻，UTC，ISO-8601 毫秒精度
 */
public record IntegrityRecord(long id, long taskId, int attempt, long releaseId, String firmwareVersion,
                              TaskStatus result, IntegrityFailureReason reason,
                              int receivedCount, int requiredCount,
                              String computedPackageDigest, String expectedPackageDigest,
                              String decidedAtUtc) {
}
