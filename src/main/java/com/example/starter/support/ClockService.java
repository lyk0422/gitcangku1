package com.example.starter.support;

import java.time.Instant;

/**
 * 可替换的时间来源，业务一律取 UTC 瞬时值，测试可固定时钟。
 */
public interface ClockService {

    /**
     * 当前时间（UTC）。
     *
     * @return 当前瞬时
     */
    Instant now();
}
