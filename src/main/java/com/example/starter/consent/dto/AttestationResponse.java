package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.AttestationStatus;
import com.example.starter.consent.Purpose;

/**
 * 证明版本响应：返回证明的接收方、用途代次作用域、版本号、到期、声明摘要与状态。
 *
 * @param attestationId 证明逻辑标识（接收方＋用途＋代次），同作用域续签共享
 * @param version       证明版本号，从 1 开始递增，续签生成新版本
 * @param recipientId   接收方标识
 * @param purpose       证明对应授权用途
 * @param epoch         证明对应授权代次
 * @param expiresAt     到期时刻（UTC）
 * @param claimDigest   声明摘要
 * @param status        状态：ACTIVE 生效中 / SUPERSEDED 已被续签替代 / REVOKED 已撤销
 * @param submittedAt   提交时刻（UTC）
 * @param revokedAt     撤销时刻（UTC），未撤销为 null
 */
public record AttestationResponse(String attestationId, int version, String recipientId,
                                  Purpose purpose, int epoch, Instant expiresAt, String claimDigest,
                                  AttestationStatus status, Instant submittedAt, Instant revokedAt) {
}
