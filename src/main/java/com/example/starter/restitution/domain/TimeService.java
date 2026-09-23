package com.example.starter.restitution.domain;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 统一时间来源；测试可注入固定/可控 Clock，未提供时使用系统默认时区时钟。
 */
@Component
public class TimeService {

    private final Clock clock;

    public TimeService(ObjectProvider<Clock> clockProvider) {
        Clock provided = clockProvider.getIfAvailable();
        this.clock = provided != null ? provided : Clock.systemDefaultZone();
    }

    public long nowMillis() {
        return clock.millis();
    }
}
