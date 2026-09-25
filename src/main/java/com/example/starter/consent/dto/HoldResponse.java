package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.HoldStatus;
import com.example.starter.consent.Purpose;

/**
 * 保留冻结响应：返回冻结标识、目标代次、法定事由、状态与 UTC 到期时刻。
 *
 * @param holdKey     冻结标识
 * @param subjectKey  主体标识（合成字符串）
 * @param purpose     用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch       被冻结的授权代次
 * @param legalReason 法定事由
 * @param status      状态：ACTIVE 生效 / RELEASED 已解除
 * @param createdBy   创建人（保留角色操作人标识）
 * @param expiresAt   UTC 到期时刻
 */
public record HoldResponse(
        String holdKey,
        String subjectKey,
        Purpose purpose,
        int epoch,
        String legalReason,
        HoldStatus status,
        String createdBy,
        Instant expiresAt) {
}
