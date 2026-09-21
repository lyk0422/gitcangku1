package com.example.starter.calibration.domain;

/**
 * 测量结果的放行状态。
 *
 * <p>PENDING_RELEASE：已提交待放行；RELEASED：已放行（放行历史永久保留，
 * 证书撤销后仅失去“当前可用”资格，不回写状态）。
 */
public enum MeasurementStatus {
    PENDING_RELEASE,
    RELEASED
}
