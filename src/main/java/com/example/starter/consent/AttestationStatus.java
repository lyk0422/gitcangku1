package com.example.starter.consent;

/**
 * 接收方承诺状态：ACTIVE 有效；REVOKED 已撤回。只允许从 ACTIVE 变为 REVOKED，
 * 撤回不删除承诺本身，也不会因授权重新授权而恢复旧 epoch 的承诺。
 */
public enum AttestationStatus {
    ACTIVE,
    REVOKED
}
