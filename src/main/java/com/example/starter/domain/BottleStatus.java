package com.example.starter.domain;

/**
 * 试剂瓶状态：ACTIVE 可用；QUARANTINED 已隔离，禁止继续领用。
 */
public enum BottleStatus {
    ACTIVE,
    QUARANTINED
}