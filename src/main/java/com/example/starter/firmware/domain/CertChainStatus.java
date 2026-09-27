package com.example.starter.firmware.domain;

/**
 * 证书链状态：ACTIVE 已登记且未撤销（仅在自身有效半开区间内才是当前链）；REVOKED 已撤销（终态）。
 */
public enum CertChainStatus {
    ACTIVE,
    REVOKED
}
