package com.example.starter.calibration.model;

/**
 * 参考点拟合状态：PENDING 待批准 / APPROVED 已批准。
 */
public enum FitStatus {
    /** 待批准：拟合已冻结，尚未创建证书。 */
    PENDING,
    /** 已批准：已创建不可变证书，证书 ID 已登记。 */
    APPROVED
}
