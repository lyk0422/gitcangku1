package com.example.starter.consent.dto;

import java.time.Instant;
import java.util.List;

import com.example.starter.consent.Purpose;

/**
 * 跨对象查询响应：同时为本次查询生成的合规快照；后续迁移、撤回或清除不改写快照内容。
 *
 * @param snapshotId     快照标识
 * @param subjectKey     主体标识（合成字符串）
 * @param purpose        用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch          查询时授权有效代次
 * @param allowedRegions 本次允许的驻留区域集合
 * @param items          全部对象的冻结结果，按记录键升序
 * @param createdAt      快照生成时间（UTC）
 */
public record BatchQueryResponse(String snapshotId, String subjectKey, Purpose purpose, int epoch,
                                 List<String> allowedRegions, List<BatchQueryItem> items,
                                 Instant createdAt) {
}
