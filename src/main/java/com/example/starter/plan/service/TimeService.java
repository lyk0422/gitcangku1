package com.example.starter.plan.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * 统一时间源：默认取系统时钟；测试可将时钟固定到指定时刻并在用例后复位，
 * 使"未来已发布计划回查"等时间相关判定可重复验证。
 */
@Component
public class TimeService {

    private volatile Clock clock = Clock.system(PlanService.OPERATION_ZONE);

    /** 当前 UTC 毫秒。 */
    public long millis() {
        return Instant.now(clock).toEpochMilli();
    }

    /** 指定时区下的当前运营日。 */
    public LocalDate today(ZoneId zone) {
        return LocalDate.now(clock.withZone(zone));
    }

    /** 测试用：将时钟固定到指定时刻。 */
    public void pin(Instant instant) {
        this.clock = Clock.fixed(instant, ZoneId.of("UTC"));
    }

    /** 测试用：恢复系统时钟。 */
    public void reset() {
        this.clock = Clock.system(PlanService.OPERATION_ZONE);
    }
}
