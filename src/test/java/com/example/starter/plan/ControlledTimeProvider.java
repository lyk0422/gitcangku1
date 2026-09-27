package com.example.starter.plan;

import com.example.starter.plan.service.TimeProvider;
import java.time.Instant;

/**
 * 测试用可控时钟：业务代码统一经 {@link TimeProvider} 取当前 UTC 时刻，
 * 危险品配置生效区间等时间边界用固定时刻确定性验证，不依赖睡眠。
 */
public class ControlledTimeProvider extends TimeProvider {

    private volatile long millis;

    public ControlledTimeProvider(Instant initial) {
        this.millis = initial.toEpochMilli();
    }

    /**
     * 设置当前 UTC 时刻。
     */
    public void setTime(Instant instant) {
        this.millis = instant.toEpochMilli();
    }

    @Override
    public long nowMillis() {
        return millis;
    }
}
