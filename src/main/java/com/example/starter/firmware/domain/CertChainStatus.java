package com.example.starter.firmware.domain;

/**
 * 证书链状态：ACTIVE 可在有效区间内成为当前链；REVOKED 已撤销（终态），
 * 撤销后新拉取不可使用，已固化该链的在途任务回执须人工复核。
 */
public enum CertChainStatus {
    ACTIVE,
    REVOKED
}
