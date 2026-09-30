package com.example.starter.salinity.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 可控时钟：从固定起点按步进推进，时间相关测试不依赖真实睡眠。
 */
public class MutableClock extends BusinessClock {

    private Instant current;
    private final List<Instant> history = new ArrayList<>();

    /**
     * @param start 初始时刻（UTC）
     */
    public MutableClock(Instant start) {
        this.current = start;
    }

    @Override
    public Instant now() {
        history.add(current);
        return current;
    }

    /**
     * 推进时钟并返回新时刻。
     *
     * @param seconds 推进秒数
     */
    public Instant advanceSeconds(long seconds) {
        current = current.plusSeconds(seconds);
        return current;
    }

    /**
     * 直接设定当前时刻。
     */
    public void setInstant(Instant instant) {
        this.current = instant;
    }

    /**
     * @return 所有取时记录
     */
    public List<Instant> history() {
        return List.copyOf(history);
    }
}
