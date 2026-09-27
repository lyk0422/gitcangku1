package com.example.starter.plan.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 线路既有运行参数：正常速度（计划占用时分所对应的运行速度）与最小间隔（顺延步长）。
 * 可通过 rail.line.* 配置覆盖。
 */
@Component
public class RailLineProperties {

    /** 线路正常速度，单位 km/h；计划占用时分视为按该速度运行。 */
    private final int normalSpeedKmh;

    /** 线路既有最小间隔，单位分钟；限速重排按该间隔的整数倍整体顺延。 */
    private final int minIntervalMinutes;

    public RailLineProperties(
            @Value("${rail.line.normal-speed-kmh:300}") int normalSpeedKmh,
            @Value("${rail.line.min-interval-minutes:10}") int minIntervalMinutes) {
        this.normalSpeedKmh = normalSpeedKmh;
        this.minIntervalMinutes = minIntervalMinutes;
    }

    public int normalSpeedKmh() {
        return normalSpeedKmh;
    }

    public int minIntervalMinutes() {
        return minIntervalMinutes;
    }
}
