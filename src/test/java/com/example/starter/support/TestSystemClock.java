package com.example.starter.support;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 测试用可控时钟：默认返回 JVM 当前时间，可固定到指定时刻并在用例后复位。
 * 以 {@code @Primary} 覆盖生产用 {@link DefaultSystemClock}。
 */
@Configuration
@Primary
public class TestSystemClock implements SystemClock {

    private final AtomicLong fixedAt = new AtomicLong(-1L);

    @Override
    public long nowMillis() {
        long value = fixedAt.get();
        return value < 0 ? System.currentTimeMillis() : value;
    }

    /**
     * 固定当前时刻。
     */
    public void setFixed(long epochMillis) {
        fixedAt.set(epochMillis);
    }

    /**
     * 恢复为系统时钟。
     */
    public void reset() {
        fixedAt.set(-1L);
    }
}
