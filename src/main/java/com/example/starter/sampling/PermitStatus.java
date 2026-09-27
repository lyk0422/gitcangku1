package com.example.starter.sampling;

/**
 * 采样许可状态：ACTIVE 有效 / ENDED 已提前结束（窗口已前移，仅影响之后采样）/ REVOKED 已撤销。
 */
public enum PermitStatus {
    ACTIVE,
    ENDED,
    REVOKED
}
