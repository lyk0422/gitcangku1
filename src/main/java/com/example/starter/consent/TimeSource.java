package com.example.starter.consent;

import java.time.Instant;

/**
 * 统一时钟来源：业务校验在同一事务内只读取一次“当前时刻”，测试可替换为可控时钟。
 */
public interface TimeSource {

    /**
     * 当前时刻（UTC）。
     */
    Instant now();
}
