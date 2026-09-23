package com.example.starter.consent.dto;

import java.time.Instant;

/**
 * 委托边响应：返回委托边的完整标识、版本、状态与到期时刻。
 *
 * @param delegationKey 委托边全局唯一键
 * @param subjectKey    授权主体标识
 * @param purpose       用途
 * @param epoch         所属授权代次
 * @param fromKey       边起点
 * @param toKey         边终点（被委托处理方）
 * @param version       边版本，从 1 递增
 * @param status        状态：ACTIVE 有效 / REVOKED 已撤销
 * @param expiresAt     边到期时刻（UTC）
 */
public record DelegationResponse(
        String delegationKey,
        String subjectKey,
        String purpose,
        int epoch,
        String fromKey,
        String toKey,
        int version,
        String status,
        Instant expiresAt) {
}
