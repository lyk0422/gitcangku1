package com.example.starter.domain;

/**
 * 证书状态：VALID 有效可放行；INVALID 依赖检测作废且批次尚未放行而失效。
 * 已放行批次的证书不随作废改变，始终保留 VALID 快照。
 */
public enum CertificateStatus {
    VALID,
    INVALID
}
