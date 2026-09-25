package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.AttestationStatus;
import com.example.starter.consent.Purpose;

/**
 * 接收方证明响应：返回证明的一个版本及其当前状态。
 *
 * @param recipientId     数据接收方标识
 * @param purpose         用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch           证明适用的授权代次
 * @param version         证明版本，同一接收方＋用途＋代次内从 1 开始递增
 * @param expiresAt       UTC 到期时刻
 * @param statementDigest 声明摘要
 * @param status          状态：ACTIVE 生效 / SUPERSEDED 已被续签取代 / REVOKED 已撤销
 */
public record AttestationResponse(
        String recipientId,
        Purpose purpose,
        int epoch,
        int version,
        Instant expiresAt,
        String statementDigest,
        AttestationStatus status) {
}
