package com.example.starter.calibration.model;

/**
 * 拟合状态：PENDING 待批准 / APPROVED 已批准（批准一次创建不可变证书，终态）。
 */
public enum FitStatus {
    PENDING,
    APPROVED
}
