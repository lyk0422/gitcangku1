package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.GrantStatus;
import com.example.starter.consent.Purpose;

/**
 * 授权响应：返回主体、用途、代次、当前状态与到期时刻。
 *
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch      授权代次，从 1 开始递增
 * @param status     状态：ACTIVE 有效 / REVOKED 已撤回
 * @param expiresAt  授权到期时刻（UTC）
 */
public record GrantResponse(String subjectKey, Purpose purpose, int epoch, GrantStatus status, Instant expiresAt) {
}
