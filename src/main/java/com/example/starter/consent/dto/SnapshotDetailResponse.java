package com.example.starter.consent.dto;

import java.time.Instant;
import java.util.List;

import com.example.starter.consent.Purpose;

/**
 * 快照明细响应：返回首次成功查询冻结的一致快照，后续迁移/撤回/清除均不改写。
 *
 * @param snapshotId 快照标识
 * @param requestId  生成快照的幂等请求标识
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch      快照时的授权代次
 * @param items      冻结明细，按快照内排序返回
 * @param createdAt  快照生成时间（UTC）
 */
public record SnapshotDetailResponse(String snapshotId, String requestId, String subjectKey,
                                     Purpose purpose, int epoch, List<BatchQueryItem> items,
                                     Instant createdAt) {
}
