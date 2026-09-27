package com.example.starter.plan.service;

import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 统一时钟：业务代码以此时钟取当前 UTC 时刻，生产实现为系统墙钟；
 * 测试可替换为可控实现，确定性验证配置生效区间（左闭右开）等时间边界，不依赖睡眠。
 */
@Component
public class TimeProvider {

    /**
     * 当前 UTC 毫秒。
     */
    public long nowMillis() {
        return System.currentTimeMillis();
    }

    /**
     * 当前 UTC 时刻。
     */
    public Instant now() {
        return Instant.ofEpochMilli(nowMillis());
    }
}
