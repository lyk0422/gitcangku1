package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.Purpose;

/**
 * 快照历史摘要项（只读）。
 *
 * @param snapshotId 快照标识
 * @param requestId  生成快照的幂等请求标识
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch      快照时的授权代次
 * @param itemCount  快照内对象数量
 * @param createdAt  快照生成时间（UTC）
 */
public record SnapshotSummaryResponse(String snapshotId, String requestId, String subjectKey,
                                      Purpose purpose, int epoch, int itemCount, Instant createdAt) {
}
