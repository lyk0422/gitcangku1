package com.example.starter.sampling;

/**
 * 采样冲突簇状态：PENDING 待裁决 / ADJUDICATED 已人工裁决（不再重算）。
 */
public enum ClusterStatus {
    PENDING,
    ADJUDICATED
}
