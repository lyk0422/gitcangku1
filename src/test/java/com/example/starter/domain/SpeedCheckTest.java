package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 航段速度闭区间 [1,50] 米/秒的精确判定单元测试。
 * 判定基于平方距离与时间阈值平方的精确比较，不做开方舍入。
 */
class SpeedCheckTest {

    private static Point p(String x, String y) {
        return new Point(new BigDecimal(x), new BigDecimal(y));
    }

    @Test
    void boundarySpeedsAreAccepted() {
        // 恰好 50 米/秒：100 米 / 2 秒
        assertNull(SpeedCheck.firstViolation(
                List.of(p("0", "0"), p("100", "0")), List.of(0L, 2L)));
        // 恰好 1 米/秒：1 米 / 1 秒
        assertNull(SpeedCheck.firstViolation(
                List.of(p("0", "0"), p("1", "0")), List.of(0L, 1L)));
        // 恰好 50 米/秒：5000 米 / 100 秒
        assertNull(SpeedCheck.firstViolation(
                List.of(p("0", "0"), p("5000", "0")), List.of(0L, 100L)));
        // 区间内的普通速度：3-4-5 直角边，50 米 / 5 秒 = 10 米/秒
        assertNull(SpeedCheck.firstViolation(
                List.of(p("0", "0"), p("30", "40")), List.of(0L, 5L)));
    }

    @Test
    void slightlyAboveMaxIsRejectedWithoutRounding() {
        // 100.001 米 / 2 秒 = 50.0005 米/秒：显示两位小数是 50.00，但必须判超速
        SpeedCheck.SpeedViolation v = SpeedCheck.firstViolation(
                List.of(p("0", "0"), p("100.001", "0")), List.of(0L, 2L));
        assertEquals(1, v.segmentIndex());
        assertTrue(v.speedMetersPerSecond() > 50.0);
        assertEquals("50.00", String.format("%.2f", v.speedMetersPerSecond()),
                "显示舍入到 50.00 也不能放行");
        // 50.0001 米/秒同样不得放行
        SpeedCheck.SpeedViolation v2 = SpeedCheck.firstViolation(
                List.of(p("0", "0"), p("50.0001", "0")), List.of(0L, 1L));
        assertEquals(1, v2.segmentIndex());
        assertTrue(v2.speedMetersPerSecond() > 50.0);
    }

    @Test
    void belowMinIsRejectedIncludingZeroSpeed() {
        // 0.999 米 / 1 秒 < 1 米/秒
        SpeedCheck.SpeedViolation slow = SpeedCheck.firstViolation(
                List.of(p("0", "0"), p("0.999", "0")), List.of(0L, 1L));
        assertEquals(1, slow.segmentIndex());
        assertTrue(slow.speedMetersPerSecond() < 1.0);
        // 同位置两点速度为 0，不合格
        SpeedCheck.SpeedViolation zero = SpeedCheck.firstViolation(
                List.of(p("5", "5"), p("5", "5")), List.of(0L, 3L));
        assertEquals(1, zero.segmentIndex());
        assertEquals(0.0, zero.speedMetersPerSecond());
    }

    @Test
    void reportsFirstViolatingSegmentIndex() {
        // 第一段合法（10 米/秒），第二段 200 米 / 2 秒 = 100 米/秒超速
        SpeedCheck.SpeedViolation v = SpeedCheck.firstViolation(
                List.of(p("0", "0"), p("20", "0"), p("220", "0")),
                List.of(0L, 2L, 4L));
        assertEquals(2, v.segmentIndex());
        assertEquals(100.0, v.speedMetersPerSecond());
    }
}