package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 挂起时限纯计算测试：不依赖 Spring 与数据库，直接验证挂起区间整体排除、
 * 生效区间冻结、恢复后剩余时限重算、累计口径与多区间叠加。
 */
class SuspensionClockTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");
    private static final Duration LIMIT = Duration.ofMinutes(5);

    private static Instant m(long minutes) {
        return T0.plus(minutes, ChronoUnit.MINUTES);
    }

    private Suspension closed(long id, Instant from, Instant to) {
        return new Suspension(id, 1L, "SK-" + id, "原因", "alice", from,
                "说明", "alice", to, from, to);
    }

    private Suspension open(long id, Instant from) {
        return new Suspension(id, 1L, "SK-" + id, "原因", "alice", from,
                null, null, null, from, from);
    }

    @Test
    void noSuspension_wallClockConsumesFullLimit() {
        List<Suspension> none = List.of();
        assertThat(SuspensionClock.remaining(T0, LIMIT, none, T0)).isEqualTo(LIMIT);
        assertThat(SuspensionClock.remaining(T0, LIMIT, none, m(3))).isEqualTo(Duration.ofMinutes(2));
        assertThat(SuspensionClock.isOverdue(T0, LIMIT, none, m(4).plusSeconds(59))).isFalse();
        // 期限边界时刻即视为到期
        assertThat(SuspensionClock.isOverdue(T0, LIMIT, none, m(5))).isTrue();
        assertThat(SuspensionClock.remaining(T0, LIMIT, none, m(6))).isEqualTo(Duration.ofMinutes(-1));
        assertThat(SuspensionClock.effectiveDeadline(T0, LIMIT, none, m(6))).isEqualTo(m(5));
    }

    @Test
    void openSuspension_freezesRemainingAndDefersDeadline_withWallClockPassing() {
        // 2 分钟时挂起，挂起前已消耗 2 分钟，剩余应为 3 分钟且挂起期间不递减
        List<Suspension> suspended = List.of(open(1L, m(2)));
        assertThat(SuspensionClock.remaining(T0, LIMIT, suspended, m(3))).isEqualTo(Duration.ofMinutes(3));
        assertThat(SuspensionClock.remaining(T0, LIMIT, suspended, m(30))).isEqualTo(Duration.ofMinutes(3));
        assertThat(SuspensionClock.isOverdue(T0, LIMIT, suspended, m(30))).isFalse();
        // 有效期限随当前时刻顺延：墙钟过了 30 分钟，有效期限也顺延到 T0+33m
        assertThat(SuspensionClock.effectiveDeadline(T0, LIMIT, suspended, m(30))).isEqualTo(m(33));
        // 生效中区间计入“截至当前累计挂起”，但不计入“已封口累计”
        assertThat(SuspensionClock.totalSuspended(suspended, m(30))).isEqualTo(Duration.ofMinutes(28));
        assertThat(SuspensionClock.totalClosedSuspended(suspended)).isZero();
        assertThat(SuspensionClock.findOpen(suspended)).isPresent();
    }

    @Test
    void resumedInterval_excludedEntirely_remainingRecomputedOnce() {
        // 2 分钟挂起、10 分钟恢复：区间 8 分钟整体排除，恢复瞬间剩余 3 分钟
        List<Suspension> one = List.of(closed(1L, m(2), m(10)));
        assertThat(SuspensionClock.totalClosedSuspended(one)).isEqualTo(Duration.ofMinutes(8));
        assertThat(SuspensionClock.remaining(T0, LIMIT, one, m(10))).isEqualTo(Duration.ofMinutes(3));
        // 恢复后有效期限固定顺延 8 分钟，不再随墙钟变化
        assertThat(SuspensionClock.effectiveDeadline(T0, LIMIT, one, m(10))).isEqualTo(m(13));
        assertThat(SuspensionClock.effectiveDeadline(T0, LIMIT, one, m(40))).isEqualTo(m(13));
        // 恢复后继续活跃消耗：+12m 时活跃消耗 4 分钟，剩 1 分钟；+13m 到期
        assertThat(SuspensionClock.remaining(T0, LIMIT, one, m(12))).isEqualTo(Duration.ofMinutes(1));
        assertThat(SuspensionClock.isOverdue(T0, LIMIT, one, m(12).plusSeconds(59))).isFalse();
        assertThat(SuspensionClock.isOverdue(T0, LIMIT, one, m(13))).isTrue();
    }

    @Test
    void multipleClosedIntervals_accumulateAndExcludeAll() {
        // 两段各 2 分钟挂起：[2,4] 与 [6,8]
        List<Suspension> two = List.of(
                closed(1L, m(2), m(4)),
                closed(2L, m(6), m(8)));
        assertThat(SuspensionClock.totalClosedSuspended(two)).isEqualTo(Duration.ofMinutes(4));
        assertThat(SuspensionClock.findOpen(two)).isEmpty();
        // 墙钟 8 分钟，活跃消耗 4 分钟，剩 1 分钟
        assertThat(SuspensionClock.remaining(T0, LIMIT, two, m(8))).isEqualTo(Duration.ofMinutes(1));
        // 有效期限 = T0+5m+4m = T0+9m
        assertThat(SuspensionClock.effectiveDeadline(T0, LIMIT, two, m(8))).isEqualTo(m(9));
        assertThat(SuspensionClock.isOverdue(T0, LIMIT, two, m(9))).isTrue();
    }

    @Test
    void closedPlusOpen_openAddsOnTopOfClosed() {
        // 已封口 [0,2]（2 分钟），4 分钟时再次挂起，6 分钟查询
        List<Suspension> mixed = List.of(
                closed(1L, T0, m(2)),
                open(2L, m(4)));
        Instant now = m(6);
        assertThat(SuspensionClock.totalSuspended(mixed, now)).isEqualTo(Duration.ofMinutes(4));
        assertThat(SuspensionClock.totalClosedSuspended(mixed)).isEqualTo(Duration.ofMinutes(2));
        // 墙钟 6 - 挂起 4 = 活跃 2，剩余 3
        assertThat(SuspensionClock.remaining(T0, LIMIT, mixed, now)).isEqualTo(Duration.ofMinutes(3));
        assertThat(SuspensionClock.effectiveDeadline(T0, LIMIT, mixed, now)).isEqualTo(m(9));
    }
}
