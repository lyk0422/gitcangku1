package com.example.starter.incident;

/**
 * 资源凭据状态：ACTIVE 可用于派发与执行；REVOKED 已撤销，不得再用于校验或自动解锁。
 * 仅允许 ACTIVE→REVOKED，撤销不可逆；凭据过期以 expiresAt（UTC）判定，不改变状态值。
 */
public enum CredentialStatus {

    /** 有效（是否在有效期内仍需结合 expiresAt 与当前 UTC 时刻判断）。 */
    ACTIVE,

    /** 已撤销，立即失效。 */
    REVOKED;
}
