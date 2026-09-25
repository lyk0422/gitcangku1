package com.example.starter.observation;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 球面距离与同簇阈值判定的纯单元测试：50 米与 60 秒边界精确包含。
 */
class GeoDistanceTest {

    @Test
    void haversineOfSamePointIsZero() {
        assertThat(GeoDistance.haversineMeters(30.0, 120.0, 30.0, 120.0)).isEqualTo(0.0);
    }

    @Test
    void haversineMatchesKnownLatitudeDegreeLength() {
        // 一纬度约 111194.9 米（地球平均半径 6371000 米）
        assertThat(GeoDistance.haversineMeters(0.0, 0.0, 1.0, 0.0))
                .isCloseTo(111_194.9, within(1.0));
    }

    @Test
    void distanceLimitBoundaryIsInclusive() {
        double limit = GeoDistance.CLUSTER_DISTANCE_LIMIT_METERS;
        double withinDeg = Math.toDegrees(limit / GeoDistance.EARTH_RADIUS_METERS);
        while (GeoDistance.haversineMeters(0.0, 0.0, withinDeg, 0.0) > limit) {
            withinDeg = Math.nextDown(withinDeg);
        }
        // 恰好 50 米：同簇
        assertThat(GeoDistance.withinDistanceLimit(0.0, 0.0, withinDeg, 0.0)).isTrue();
        // 超过 50 米：不同簇
        double beyondDeg = Math.nextUp(withinDeg);
        while (GeoDistance.haversineMeters(0.0, 0.0, beyondDeg, 0.0) <= limit) {
            beyondDeg = Math.nextUp(beyondDeg);
        }
        assertThat(GeoDistance.withinDistanceLimit(0.0, 0.0, beyondDeg, 0.0)).isFalse();
    }

    @Test
    void timeLimitBoundaryIsInclusive() {
        Instant base = Instant.parse("2026-09-26T10:00:00Z");
        // 恰好 60 秒（两个方向）：同簇
        assertThat(GeoDistance.withinTimeLimit(base, base.plusSeconds(60))).isTrue();
        assertThat(GeoDistance.withinTimeLimit(base, base.minusSeconds(60))).isTrue();
        assertThat(GeoDistance.withinTimeLimit(base, base)).isTrue();
        // 61 秒：不同簇
        assertThat(GeoDistance.withinTimeLimit(base, base.plusSeconds(61))).isFalse();
    }

    @Test
    void sameClusterRequiresBothLimits() {
        Instant base = Instant.parse("2026-09-26T10:00:00Z");
        // 距离满足但时间不满足：不同簇
        assertThat(GeoDistance.sameCluster(30.0, 120.0, base, 30.0, 120.0, base.plusSeconds(61)))
                .isFalse();
        // 时间满足但距离不满足（约 111 米）：不同簇
        assertThat(GeoDistance.sameCluster(30.0, 120.0, base, 30.001, 120.0, base)).isFalse();
        // 两者都满足：同簇
        assertThat(GeoDistance.sameCluster(30.0, 120.0, base, 30.0001, 120.0, base.plusSeconds(30)))
                .isTrue();
    }
}
