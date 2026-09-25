package com.example.starter.consent;

/**
 * 委托状态：有效（ACTIVE）或已撤销（REVOKED），只允许从有效变为已撤销。
 */
public enum DelegateStatus {
    ACTIVE,
    REVOKED
}
