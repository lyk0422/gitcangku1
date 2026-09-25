package com.example.starter.consent;

/**
 * 接收方证明版本状态：
 * ACTIVE 生效中（到期判断另按 expires_at 与当前 UTC 时间比较）；
 * SUPERSEDED 已被续签新版本替代，历史保留；
 * REVOKED 已被撤销，历史保留。
 */
public enum AttestationStatus {
    ACTIVE,
    SUPERSEDED,
    REVOKED
}
