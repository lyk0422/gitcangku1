package com.example.starter.germination.domain;

/**
 * 发芽率证书状态。
 * <ul>
 *     <li>{@link #VALID}：当前有效，仓储可据此放行；已放行批次的证书始终保持该状态作为快照；</li>
 *     <li>{@link #INVALID}：证书未放行时，其固化的检测记录被作废导致失效，不能再用于放行。</li>
 * </ul>
 */
public enum CertificateStatus {
    /** 有效（含已放行批次留存的证书快照）。 */
    VALID,
    /** 未放行即因检测作废而失效。 */
    INVALID
}
