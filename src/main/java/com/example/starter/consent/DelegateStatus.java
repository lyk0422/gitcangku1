package com.example.starter.consent;

/**
 * 委托状态：ACTIVE 有效；REVOKED 已撤销。撤销只影响后续查询，已生成快照不受影响。
 */
public enum DelegateStatus {
    ACTIVE,
    REVOKED
}
