package com.example.starter.calibration.model;

/**
 * 离群记录状态：PENDING 待裁决 / ACCEPTED 已接受（保留读数）/ REJECTED 已拒绝（排除读数）。
 */
public enum OutlierStatus {
    PENDING,
    ACCEPTED,
    REJECTED
}
