package com.example.starter.consent.dto;

import com.example.starter.consent.GrantStatus;

/**
 * 授权响应：返回主体、用途、代次与当前状态。
 *
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途代码
 * @param epoch      授权代次，从 1 开始递增
 * @param status     状态：ACTIVE 有效 / REVOKED 已撤回 / MIGRATED 已随用途拆分迁移
 */
public record GrantResponse(String subjectKey, String purpose, int epoch, GrantStatus status) {
}
