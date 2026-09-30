package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.AttestationStatus;
import com.example.starter.consent.Purpose;

/**
 * 接收方承诺响应：承诺提交成功或同 requestId 重放时返回首次承诺版本。
 *
 * @param recipientKey 接收方标识
 * @param subjectKey   授权主体标识
 * @param purpose      承诺用途
 * @param epoch        承诺的授权代次
 * @param expiresAt    UTC 到期时刻
 * @param status       当前状态：ACTIVE 有效 / EXPIRED 已到期 / REVOKED 随授权撤回而失效
 * @param createdAt    UTC 提交时刻
 */
public record AttestationResponse(String recipientKey, String subjectKey, Purpose purpose, int epoch,
                                  Instant expiresAt, AttestationStatus status, Instant createdAt) {
}
