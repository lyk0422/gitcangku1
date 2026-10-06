package com.example.starter.race.domain;

/**
 * 轨迹采样点：从起跑起的时刻与累计前进距离；隐含起点固定为 (0,0)。
 *
 * @param timeMs     从起跑起的时刻（毫秒，正整数，同一人严格递增）
 * @param distanceMm 累计前进距离（毫米，非负，同一人不减）
 */
public record TrajectoryPoint(long timeMs, long distanceMm) {
}
