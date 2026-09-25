package com.example.starter.firmware.domain;

import java.time.LocalDateTime;

/**
 * 金丝雀推进历史记录，只增不改。
 *
 * @param releaseId   所属发布单ID
 * @param fromLevel   推进前已解锁的最高级别
 * @param toLevel     推进目标级别；等于级别总数+1 表示发布单进入 COMPLETED 终态
 * @param promoteKey  推进幂等键，全局唯一
 * @param sampleCount 推进成功时当前级别样本数快照
 * @param failCount   推进成功时当前级别失败样本数快照
 * @param promotedAt  推进时间（服务器本地时区）
 */
public record CanaryPromotion(long releaseId, int fromLevel, int toLevel, String promoteKey,
                              int sampleCount, int failCount, LocalDateTime promotedAt) {
}
