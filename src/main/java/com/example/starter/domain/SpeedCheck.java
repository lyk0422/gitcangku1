package com.example.starter.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 定时航线航段速度检查。
 *
 * <p>航段速度 = 欧氏距离（米） / 到达时刻差（秒），允许闭区间 [1, 50] 米/秒。
 * 判定不做开方：直接比较平方距离与时间阈值的平方（均为精确 BigDecimal 整数/小数运算），
 * 避免先开方再舍入把 50.0001 米/秒当成 50.00 放行。</p>
 */
public final class SpeedCheck {

    /** 最低允许速度（米/秒，含）。 */
    public static final BigDecimal MIN_SPEED = BigDecimal.ONE;
    /** 最高允许速度（米/秒，含）。 */
    public static final BigDecimal MAX_SPEED = BigDecimal.valueOf(50);

    private static final BigDecimal MAX_SPEED_SQ = MAX_SPEED.multiply(MAX_SPEED);

    private SpeedCheck() {
    }

    /**
     * 速度越界信息。
     *
     * @param segmentIndex         航段号，从 1 开始（航段 1 连接第 1、2 个航点）
     * @param speedMetersPerSecond 未舍入的航段速度（米/秒）
     */
    public record SpeedViolation(int segmentIndex, double speedMetersPerSecond) {
    }

    /**
     * 按顺序检查全部航段速度，返回第一个越界航段；全部在 [1,50] 米/秒内返回 null。
     *
     * @param points       有序航点（米，最多 3 位小数）
     * @param epochSeconds 各航点 UTC 到达时刻（epoch 整秒），调用方保证严格递增且与航点一一对应
     */
    public static SpeedViolation firstViolation(List<Point> points, List<Long> epochSeconds) {
        for (int i = 0; i + 1 < points.size(); i++) {
            long dt = epochSeconds.get(i + 1) - epochSeconds.get(i);
            Point a = points.get(i);
            Point b = points.get(i + 1);
            BigDecimal dx = b.x().subtract(a.x());
            BigDecimal dy = b.y().subtract(a.y());
            // 平方距离（平方米），精确小数运算，不开方
            BigDecimal distSq = dx.multiply(dx).add(dy.multiply(dy));
            BigDecimal dtSq = BigDecimal.valueOf(dt).multiply(BigDecimal.valueOf(dt));
            // 速度 < 1 <=> 距离平方 < (1*dt)^2；速度 > 50 <=> 距离平方 > (50*dt)^2
            if (distSq.compareTo(dtSq) < 0
                    || distSq.compareTo(dtSq.multiply(MAX_SPEED_SQ)) > 0) {
                double speed = Math.sqrt(distSq.doubleValue()) / dt;
                return new SpeedViolation(i + 1, speed);
            }
        }
        return null;
    }
}