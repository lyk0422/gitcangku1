package com.example.starter.incident;

/**
 * 资源资质状态：ACTIVE 有效（登记后可参与租约资质校验），
 * REVOKED 已被提前撤销（未来有效的高危租约据此转入资质风险门禁）。
 */
public enum CredentialStatus {

    /** 有效。 */
    ACTIVE,

    /** 已提前撤销。 */
    REVOKED;
}
