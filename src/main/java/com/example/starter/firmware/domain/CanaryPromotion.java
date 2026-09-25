package com.example.starter.firmware.domain;

/**
 * 金丝雀级别推进历史记录，只增不改。
 *
 * @param id          推进记录ID
 * @param releaseId   所属发布单ID
 * @param fromLevel   推进前已解锁最高级别
 * @param toLevel     新解锁级别；推进到终态 COMPLETED 时为 null
 * @param action      动作：UNLOCK 解锁下一级别，COMPLETE 进入完成终态
 * @param sampleCount 推进时当前级别样本数快照
 * @param failedCount 推进时当前级别失败样本数快照
 * @param promoteKey  本次推进的幂等键（全局唯一）
 */
public record CanaryPromotion(long id, long releaseId, int fromLevel, Integer toLevel, String action,
                              int sampleCount, int failedCount, String promoteKey) {
}
