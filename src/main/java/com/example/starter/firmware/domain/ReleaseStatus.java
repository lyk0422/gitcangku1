package com.example.starter.firmware.domain;

/**
 * 发布单状态：ACTIVE 投放中；PAUSED 失败自动暂停；COMPLETED 已完成（终态）；CANCELLED 已取消（终态）。
 */
public enum ReleaseStatus {
    ACTIVE,
    PAUSED,
    COMPLETED,
    CANCELLED
}
