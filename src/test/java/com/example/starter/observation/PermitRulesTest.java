package com.example.starter.observation;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 采样许可纯规则单元测试：网格闭区间、UTC 左闭右开时间窗、重叠/命中与创建区间合法性的边界。
 */
class PermitRulesTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-02T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-03T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-04T00:00:00Z");

    private SamplingPermit permit(String permitId, int gridMin, int gridMax,
                                  Instant from, Instant to, Instant terminatedAt, PermitStatus status) {
        return new SamplingPermit(permitId, "dev-1", "v1", gridMin, gridMax, from, to,
                terminatedAt, null, status, 10, 0);
    }

    @Test
    void gridIntersectionTreatsSharedCellsAsOverlap() {
        assertThat(PermitRules.gridIntersects(1, 10, 10, 20)).isTrue();
        assertThat(PermitRules.gridIntersects(1, 10, 5, 8)).isTrue();
        // 相邻但不共享网格单元：不相交
        assertThat(PermitRules.gridIntersects(1, 10, 11, 20)).isFalse();
    }

    @Test
    void timeWindowsAreHalfOpenSoTouchingEndpointsDoNotOverlap() {
        assertThat(PermitRules.timeIntersects(T0, T2, T1, T3)).isTrue();
        // [T0,T1) 与 [T1,T2) 仅端点相接：不重叠
        assertThat(PermitRules.timeIntersects(T0, T1, T1, T2)).isFalse();
        assertThat(PermitRules.timeIntersects(T1, T2, T0, T1)).isFalse();
    }

    @Test
    void overlapsRequiresBothGridAndTimeIntersectionAndActive() {
        SamplingPermit active = permit("p1", 1, 10, T0, T2, null, PermitStatus.ACTIVE);
        assertThat(PermitRules.overlaps(active, 5, 15, T1, T3)).isTrue();
        // 网格不相交
        assertThat(PermitRules.overlaps(active, 11, 20, T1, T3)).isFalse();
        // 时间窗仅端点相接
        assertThat(PermitRules.overlaps(active, 5, 8, T2, T3)).isFalse();
        // 已撤销许可不阻挡同区域新许可
        SamplingPermit revoked = permit("p2", 1, 10, T0, T2, null, PermitStatus.REVOKED);
        assertThat(PermitRules.overlaps(revoked, 1, 10, T0, T2)).isFalse();
    }

    @Test
    void overlapsRespectsEarlierTermination() {
        // 许可提前在 T1 结束：与之在 [T1,T2) 相交的新许可不重叠
        SamplingPermit terminated = permit("p1", 1, 10, T0, T2, T1, PermitStatus.ACTIVE);
        assertThat(PermitRules.overlaps(terminated, 1, 10, T1, T2)).isFalse();
        assertThat(PermitRules.overlaps(terminated, 1, 10, T0, T1)).isTrue();
    }

    @Test
    void matchesChecksDeviceGridAndHalfOpenWindowAndStatus() {
        SamplingPermit active = permit("p1", 10, 20, T0, T2, null, PermitStatus.ACTIVE);
        assertThat(PermitRules.matches(active, "dev-1", 10, T0)).isTrue();
        assertThat(PermitRules.matches(active, "dev-1", 20, T1)).isTrue();
        // 网格越界
        assertThat(PermitRules.matches(active, "dev-1", 21, T1)).isFalse();
        // 时刻在终点（不含）
        assertThat(PermitRules.matches(active, "dev-1", 15, T2)).isFalse();
        // 时刻早于起点
        assertThat(PermitRules.matches(active, "dev-1", 15, T0.minusSeconds(1))).isFalse();
        // 设备不符
        assertThat(PermitRules.matches(active, "dev-other", 15, T1)).isFalse();
        // 已撤销不命中
        SamplingPermit revoked = permit("p2", 10, 20, T0, T2, null, PermitStatus.REVOKED);
        assertThat(PermitRules.matches(revoked, "dev-1", 15, T1)).isFalse();
        // 提前结束：终止时刻本身不命中
        SamplingPermit terminated = permit("p3", 10, 20, T0, T2, T1, PermitStatus.ACTIVE);
        assertThat(PermitRules.matches(terminated, "dev-1", 15, T1.minusSeconds(1))).isTrue();
        assertThat(PermitRules.matches(terminated, "dev-1", 15, T1)).isFalse();
    }

    @Test
    void createRangeMustStartAtOrAfterNowAndEndAfterStart() {
        assertThat(PermitRules.isValidCreateRange(T0, T1, T0)).isTrue();
        // 起点早于当前时刻：禁止追溯
        assertThat(PermitRules.isValidCreateRange(T0, T2, T1)).isFalse();
        // 终点不晚于起点
        assertThat(PermitRules.isValidCreateRange(T1, T1, T0)).isFalse();
        assertThat(PermitRules.isValidCreateRange(T2, T1, T0)).isFalse();
    }

    @Test
    void coversGridRangeChecksInclusiveBounds() {
        SamplingPermit active = permit("p1", 10, 20, T0, T2, null, PermitStatus.ACTIVE);
        assertThat(active.coversGridRange(10, 20)).isTrue();
        assertThat(active.coversGridRange(12, 18)).isTrue();
        assertThat(active.coversGridRange(9, 15)).isFalse();
        assertThat(active.coversGridRange(15, 21)).isFalse();
    }
}
