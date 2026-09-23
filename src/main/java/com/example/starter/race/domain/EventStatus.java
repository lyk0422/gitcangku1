package com.example.starter.race.domain;

/**
 * 中止事件状态：SUSPENDED-中止中（尚未恢复）；RESUMED-已恢复，净计时已重算。
 */
public enum EventStatus {
    SUSPENDED,
    RESUMED
}
