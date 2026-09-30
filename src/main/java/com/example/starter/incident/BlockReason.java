package com.example.starter.incident;

/**
 * 任务阻断原因：仅在执行前凭据检查不通过时写入派发记录与任务。
 * CREDENTIAL_EXPIRED 凭据有效期已结束；CREDENTIAL_REVOKED 凭据已被撤销。
 */
public enum BlockReason {

    /** 凭据有效期已结束（当前 UTC 时刻 ≥ expiresAt）。 */
    CREDENTIAL_EXPIRED,

    /** 凭据已被撤销（状态为 REVOKED）。 */
    CREDENTIAL_REVOKED;
}
