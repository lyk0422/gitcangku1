package com.example.starter.consent;

/**
 * 委托边状态：ACTIVE 有效；REVOKED 已撤销。只允许从 ACTIVE 变为 REVOKED。
 * 撤销只影响撤销提交后的写入，历史记录中保存的写入依据保持不可变。
 */
public enum DelegationStatus {
    ACTIVE,
    REVOKED
}
