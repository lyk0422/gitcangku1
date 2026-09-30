package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.AttestationStatus;
import com.example.starter.consent.Purpose;

/**
 * 接收方承诺响应：返回承诺主体、接收方、用途、授权代次、UTC 到期时刻与状态。
 *
 * @param recipientKey 接收方标识
 * @param subjectKey   授权主体标识
 * @param purpose      承诺用途
 * @param epoch        承诺对应的授权代次
 * @param expiresAt    承诺到期时刻（UTC）
 * @param status       状态：ACTIVE 有效 / REVOKED 已撤回
 */
public record AttestationResponse(
        String recipientKey,
        String subjectKey,
        Purpose purpose,
        int epoch,
        Instant expiresAt,
        AttestationStatus status) {
}
