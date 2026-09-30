package com.example.starter.pass.support;

import com.example.starter.pass.clock.TimeSource;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 测试用可控 UTC 时钟：测试显式设定当前时刻，断言 confirmedAt/receivedAt 等固化时间戳。
 */
@Component
@Primary
public class FixedTimeSource implements TimeSource {

    private volatile Instant instant = Instant.parse("2026-10-01T00:00:00Z");

    /** 设定当前 UTC 时刻。 */
    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    @Override
    public Instant now() {
        return instant;
    }
}
