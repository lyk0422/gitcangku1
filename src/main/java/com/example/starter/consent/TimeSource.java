package com.example.starter.consent;

import java.time.Instant;

/**
 * 时间源：统一提供当前 UTC 时刻，生产环境使用系统时钟，测试中可替换为可控时钟。
 */
public interface TimeSource {

    /**
     * 当前 UTC 时刻。
     */
    Instant now();
}
