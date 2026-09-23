package com.example.starter.consent;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 可控时钟测试配置：以主 Bean 身份替换系统时钟，使到期边界测试无需休眠。
 * 使用该配置的测试在每个用例开始前应调用 {@link MutableTimeSource#reset()}。
 */
@TestConfiguration(proxyBeanMethods = false)
public class ClockTestConfig {

    /**
     * 可手动设置的时间源，初始为真实当前时刻。
     */
    public static class MutableTimeSource implements TimeSource {

        private volatile Instant instant = Instant.now();

        @Override
        public Instant now() {
            return instant;
        }

        public void set(Instant instant) {
            this.instant = instant;
        }

        public void reset() {
            this.instant = Instant.now();
        }
    }

    @Bean
    @Primary
    public MutableTimeSource mutableTimeSource() {
        return new MutableTimeSource();
    }
}
