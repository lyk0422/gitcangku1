package com.example.starter.blind.dto;

import java.util.List;

/**
 * 轮换单视图（只读）：返回前后名册、授权代次与知情冲突依据。
 *
 * @param rotationKey        轮换单业务键
 * @param experimentId       实验编号
 * @param status             状态：ACTIVATED
 * @param expectedVersion    提交时的期望版本
 * @param newVersion         激活后的实验版本
 * @param beforeRoster       激活前名册（首次轮换为三类空集合）
 * @param targetRoster       激活的目标名册
 * @param beforeGenerationId 激活前活动代次主键；首次轮换为 null
 * @param generation         本次生成的新授权代次
 * @param conflictEvidence   本次激活时存在的知情冲突依据（通常为空，非空也保留为证据）
 * @param createdBy          提交人
 * @param createdAt          创建时间，Unix 毫秒 UTC
 * @param activatedAt        激活时间，Unix 毫秒 UTC
 */
public record RotationOrderView(
        String rotationKey,
        String experimentId,
        String status,
        int expectedVersion,
        int newVersion,
        RosterView beforeRoster,
        RosterView targetRoster,
        Long beforeGenerationId,
        GenerationView generation,
        List<ConflictEvidenceView> conflictEvidence,
        String createdBy,
        long createdAt,
        long activatedAt
) {
}
