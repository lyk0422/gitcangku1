package com.example.starter.consent;

import java.time.Instant;

/**
 * 领域统一时钟：所有业务时间戳以 UTC 口径获取。
 *
 * <p>测试中可替换为可控实现（固定或步进），不依赖真实休眠来判定时间边界。
 */
public interface DomainClock {

    /**
     * 当前时间（UTC，毫秒精度）。
     */
    Instant nowUtc();
}
