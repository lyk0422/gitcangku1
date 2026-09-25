package com.example.starter.workblock.model;

/**
 * 施工单状态：ACTIVE 生效（窗口参与冲突裁决）；
 * CANCELLED 已取消（窗口立即释放，主记录与取消记录均不可变）。
 */
public enum WorkBlockStatus {
    ACTIVE,
    CANCELLED
}
