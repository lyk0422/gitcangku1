package com.example.starter.consent;

/**
 * 授权代次状态：ACTIVE 有效；REVOKED 已撤回。只允许从 ACTIVE 变为 REVOKED。
 */
public enum GrantStatus {
    ACTIVE,
    REVOKED
}
