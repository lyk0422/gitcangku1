package com.example.starter.consent;

/**
 * 子范围状态：ACTIVE 有效；REVOKED 已独立撤回。只允许从 ACTIVE 变为 REVOKED。
 *
 * <p>子范围状态独立于授权代次状态：代次整体撤回时不逐行改写子范围状态，
 * 但整体撤回优先，整体撤回后所有子范围一律 410。
 */
public enum ScopeStatus {
    ACTIVE,
    REVOKED
}
