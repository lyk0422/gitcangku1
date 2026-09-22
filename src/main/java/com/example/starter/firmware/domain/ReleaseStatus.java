package com.example.starter.firmware.domain;

/**
 * 发布单状态：ACTIVE 投放中；PAUSED 失败率自动暂停（可人工恢复）；CANCELLED 已取消（终态）。
 */
public enum ReleaseStatus {
    ACTIVE,
    PAUSED,
    CANCELLED
}
