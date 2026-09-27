package com.example.starter.firmware.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 业务证据时刻统一规则：UTC、秒精度、ISO-8601（如 2026-09-28T07:00:00Z）。
 * 统一秒精度后字符串字典序与时间序完全一致，可直接用于左闭右开区间比较；
 * 同一秒内的先后由事件表自增 id 保证。
 */
public final class UtcTimes {

    private UtcTimes() {
    }

    public static String now(Clock clock) {
        return Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
