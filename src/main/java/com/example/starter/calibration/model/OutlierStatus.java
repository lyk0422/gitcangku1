package com.example.starter.calibration.model;

/**
 * 离群记录裁决状态：PENDING 待裁决/待仲裁，ACCEPTED 裁决保留读数，REJECTED 裁决排除读数。
 */
public enum OutlierStatus {
    PENDING,
    ACCEPTED,
    REJECTED
}
