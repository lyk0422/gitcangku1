package com.example.starter.consent;

/**
 * 授权代次状态：ACTIVE 有效；REVOKED 已撤回；MIGRATED 已被用途拆分迁移。
 * 只允许 ACTIVE → REVOKED 或 ACTIVE → MIGRATED；MIGRATED 授权不可再用于新查询。
 */
public enum GrantStatus {
    ACTIVE,
    REVOKED,
    MIGRATED
}
