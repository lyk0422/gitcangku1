package com.example.starter.consent;

/**
 * 委托边状态：ACTIVE 有效；REVOKED 已撤销。
 * 到期不改变状态：status 仍为 ACTIVE 时，只要 expires_at 已到即视为不再有效。
 */
public enum DelegationStatus {
    ACTIVE,
    REVOKED
}
