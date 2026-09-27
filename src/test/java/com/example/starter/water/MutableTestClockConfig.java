package com.example.starter.water;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：以 {@link Primary} 替换生产 {@code utcClock}，默认委托系统 UTC 时钟，
 * 时间边界测试可固定到任意 UTC 时刻并在用例结束后复位。所有时间敏感测试通过它确定性推进，
 * 不依赖睡眠与真实墙钟。
 */
@Configuration
public class MutableTestClockConfig {

    /** 测试共享的可变 UTC 时钟。 */
    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Clock.systemUTC());
    }

    /** 可在测试中固定/复位的 UTC 时钟。 */
    public static class MutableClock extends Clock {

        private final ZoneId zone = ZoneOffset.UTC;
        private volatile Clock delegate;

        public MutableClock(Clock delegate) {
            this.delegate = delegate;
        }

        /** 固定到指定 UTC 时刻。 */
        public void fixedAt(Instant instant) {
            this.delegate = Clock.fixed(instant, zone);
        }

        /** 恢复为系统 UTC 时钟。 */
        public void reset() {
            this.delegate = Clock.systemUTC();
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return delegate.withZone(zone);
        }

        @Override
        public Instant instant() {
            return delegate.instant();
        }
    }
}
