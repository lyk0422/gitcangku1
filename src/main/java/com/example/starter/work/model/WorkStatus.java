package com.example.starter.work.model;

/**
 * 施工单状态：ACTIVE 生效中 / CANCELLED 已取消（占用立即释放，取消记录不可变保留）。
 */
public enum WorkStatus {
    ACTIVE,
    CANCELLED
}
