package com.example.starter.firmware.domain;

/**
 * 发布单状态：ACTIVE 投放中；CANCELLED 已取消；COMPLETED 已完成（金丝雀最高级别推进后的终态，不再解锁更多设备）。
 */
public enum ReleaseStatus {
    ACTIVE,
    CANCELLED,
    COMPLETED
}
