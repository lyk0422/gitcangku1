package com.example.starter.exposure.domain;

/**
 * 访客同意决定。
 * <ul>
 *     <li>ALLOW：在同意区间生效期内允许创建曝光预占；</li>
 *     <li>DENY：在同意区间生效期内拒绝创建曝光预占（CONSENT_DENIED）。</li>
 * </ul>
 */
public enum ConsentDecision {
    ALLOW,
    DENY
}
