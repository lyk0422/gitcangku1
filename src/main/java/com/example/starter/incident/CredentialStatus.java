package com.example.starter.incident;

/**
 * 资源凭据状态：ACTIVE 有效 / REVOKED 已撤销。
 * 撤销不可逆；是否在有效期内另由 expiresAt 与当前 UTC 时刻判定。
 */
public enum CredentialStatus {

    /** 有效（仍需结合 expiresAt 判断是否在有效期内）。 */
    ACTIVE,

    /** 已撤销，撤销不可逆，任何派发与执行前检查均不得通过。 */
    REVOKED;
}
