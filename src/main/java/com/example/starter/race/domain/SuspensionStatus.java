package com.example.starter.race.domain;

/**
 * 中止事件状态：SUSPENDED-中止中（等待恢复）；RESUMED-已恢复，补偿生效。
 */
public enum SuspensionStatus {
    SUSPENDED,
    RESUMED
}
