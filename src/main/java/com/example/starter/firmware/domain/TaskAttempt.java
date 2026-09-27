package com.example.starter.firmware.domain;

/**
 * 任务尝试代次。判定证据（发布版本、聚合摘要、判定结果与时刻）在判定时固化，之后不可改。
 *
 * @param id                尝试记录ID
 * @param taskId            任务ID
 * @param attemptNo         尝试代次，从1开始
 * @param releaseId         固化：代次建立时所属发布单ID
 * @param toVersion         固化：代次建立时发布单目标版本
 * @param expectedAggregate 固化：代次建立时登记的聚合摘要；无分片清单为 null
 * @param requiredCount     固化：要求的分片总数；无分片清单为0
 * @param receivedCount     本代次已接收分片数（去重后）
 * @param verdict           判定结果（INSTALLABLE/INTEGRITY_FAILED），未判定为 null
 * @param verdictReason     判定原因，未判定为 null
 * @param actualAggregate   判定时计算的聚合摘要，未判定为 null
 * @param decidedAtUtc      判定时刻（UTC ISO-8601），未判定为 null
 * @param createdAtUtc      代次建立时刻（UTC ISO-8601）
 */
public record TaskAttempt(long id, long taskId, int attemptNo, long releaseId, String toVersion,
                          String expectedAggregate, int requiredCount, int receivedCount,
                          String verdict, String verdictReason, String actualAggregate,
                          String decidedAtUtc, String createdAtUtc) {
}
