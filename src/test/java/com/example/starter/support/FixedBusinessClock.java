package com.example.starter.support;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可控业务时钟：测试可固定或逐步推进时刻，避免用真实时间和睡眠判断结果。
 */
public class FixedBusinessClock implements BusinessClock {

    private final AtomicReference<LocalDateTime> current = new AtomicReference<>(
            LocalDateTime.of(2026, 9, 15, 10, 0));

    @Override
    public LocalDateTime now() {
        return current.get();
    }

    /**
     * 将时钟固定到指定时刻。
     */
    public void setTime(LocalDateTime time) {
        current.set(time);
    }

    /**
     * 将时钟向前推进指定秒数。
     */
    public void advanceSeconds(long seconds) {
        current.updateAndGet(t -> t.plusSeconds(seconds));
    }
}
