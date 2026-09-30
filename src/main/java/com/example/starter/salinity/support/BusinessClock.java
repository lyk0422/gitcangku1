package com.example.starter.salinity.support;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 业务时钟：默认返回系统 UTC 时间，测试可替换为固定/步进时钟。
 */
@Component
public class BusinessClock {

    /**
     * @return 当前时刻（UTC）
     */
    public Instant now() {
        return Instant.now();
    }
}
