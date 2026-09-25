package com.example.starter.consent.dto;

import com.example.starter.consent.GrantStatus;
import com.example.starter.consent.Purpose;

/**
 * 子范围响应：返回子范围所属主体、用途、代次、标识、标签与当前状态。
 *
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch      子范围所属授权代次
 * @param scopeKey   子范围标识
 * @param label      子范围标签（合成字符串）
 * @param status     状态：ACTIVE 有效 / REVOKED 已撤回
 */
public record ScopeResponse(String subjectKey, Purpose purpose, int epoch,
                            String scopeKey, String label, GrantStatus status) {
}
