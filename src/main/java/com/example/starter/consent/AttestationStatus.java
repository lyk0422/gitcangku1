package com.example.starter.consent;

/**
 * 接收方承诺状态：ACTIVE 有效（授权代次有效且未到期）；EXPIRED 已到期；REVOKED 随授权代次撤回而失效。
 *
 * <p>承诺不单独提供撤回操作：授权方撤回授权代次后，该代次上的所有承诺对读取立即失效。
 */
public enum AttestationStatus {
    ACTIVE,
    EXPIRED,
    REVOKED
}
