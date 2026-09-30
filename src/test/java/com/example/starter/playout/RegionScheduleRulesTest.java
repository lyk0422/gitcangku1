package com.example.starter.playout;

import com.example.starter.playout.api.Dtos.RegionSegmentInput;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 区域切换核心规则的纯单元测试：左闭右开区间判重（相邻端点可接续）与幂等参数指纹。
 * 不依赖 Spring 与数据库，覆盖题干涉及的时段边界。
 */
class RegionScheduleRulesTest {

    private static final OffsetDateTime T_10 = utc(10, 0);
    private static final OffsetDateTime T_1030 = utc(10, 30);
    private static final OffsetDateTime T_11 = utc(11, 0);
    private static final OffsetDateTime T_12 = utc(12, 0);

    // ---------- 区间左闭右开 ----------

    @Test
    void overlappingIntervalsDetected() {
        // 部分重叠
        assertThat(RegionPlayoutService.intervalsOverlap(
                ms(T_10), ms(T_11), ms(T_1030), ms(T_12))).isTrue();
        // 包含关系
        assertThat(RegionPlayoutService.intervalsOverlap(
                ms(T_10), ms(T_12), ms(T_1030), ms(T_11))).isTrue();
        // 同起点
        assertThat(RegionPlayoutService.intervalsOverlap(
                ms(T_10), ms(T_11), ms(T_10), ms(T_12))).isTrue();
        // 完全相同
        assertThat(RegionPlayoutService.intervalsOverlap(
                ms(T_10), ms(T_11), ms(T_10), ms(T_11))).isTrue();
        // 起点恰落在对方区间起点（含边界）
        assertThat(RegionPlayoutService.intervalsOverlap(
                ms(T_10), ms(T_11), ms(T_10), ms(T_1030))).isTrue();
    }

    @Test
    void adjacentEndpointsDoNotOverlap() {
        // 一端 end == 另一端 start：可接续，不算重叠
        assertThat(RegionPlayoutService.intervalsOverlap(
                ms(T_10), ms(T_11), ms(T_11), ms(T_12))).isFalse();
        assertThat(RegionPlayoutService.intervalsOverlap(
                ms(T_11), ms(T_12), ms(T_10), ms(T_11))).isFalse();
        // 完全分离
        assertThat(RegionPlayoutService.intervalsOverlap(
                ms(T_10), ms(T_1030), ms(T_11), ms(T_12))).isFalse();
    }

    // ---------- 幂等参数指纹 ----------

    @Test
    void fingerprintStableForSameRegionListInSameOrder() {
        List<RegionSegmentInput> first = List.of(
                segment("A", "S", T_10, T_11),
                segment("B", "S", T_11, T_12));
        List<RegionSegmentInput> replay = List.of(
                segment("A", "S", T_10, T_11),
                segment("B", "S", T_11, T_12));
        assertThat(RegionPlayoutService.canonicalSegments(first))
                .isEqualTo(RegionPlayoutService.canonicalSegments(replay));
    }

    @Test
    void fingerprintChangesWhenAssetOrWindowOrRegionChanges() {
        List<RegionSegmentInput> base = new ArrayList<>(List.of(segment("A", "S", T_10, T_11)));

        // 换素材
        assertThat(RegionPlayoutService.canonicalSegments(List.of(segment("A", "X", T_10, T_11))))
                .isNotEqualTo(RegionPlayoutService.canonicalSegments(base));
        // 换开始时段
        assertThat(RegionPlayoutService.canonicalSegments(List.of(segment("A", "S", T_1030, T_11))))
                .isNotEqualTo(RegionPlayoutService.canonicalSegments(base));
        // 换结束时段
        assertThat(RegionPlayoutService.canonicalSegments(List.of(segment("A", "S", T_10, T_12))))
                .isNotEqualTo(RegionPlayoutService.canonicalSegments(base));
        // 换区域
        assertThat(RegionPlayoutService.canonicalSegments(List.of(segment("B", "S", T_10, T_11))))
                .isNotEqualTo(RegionPlayoutService.canonicalSegments(base));
    }

    @Test
    void fingerprintIsUtcInvariantAcrossOffsetExpressions() {
        // 同一 UTC 时刻用不同偏移表达（10:00Z == 18:00+08:00），指纹应一致
        RegionSegmentInput utcView = segment("A", "S", T_10, T_11);
        RegionSegmentInput shanghaiView = segment("A", "S",
                T_10.withOffsetSameInstant(ZoneOffset.ofHours(8)),
                T_11.withOffsetSameInstant(ZoneOffset.ofHours(8)));
        assertThat(RegionPlayoutService.canonicalSegments(List.of(utcView)))
                .isEqualTo(RegionPlayoutService.canonicalSegments(List.of(shanghaiView)));
    }

    private static RegionSegmentInput segment(String regionId, String assetId,
                                              OffsetDateTime start, OffsetDateTime end) {
        return new RegionSegmentInput(regionId, assetId, start, end);
    }

    private static long ms(OffsetDateTime time) {
        return time.toInstant().toEpochMilli();
    }

    private static OffsetDateTime utc(int hour, int minute) {
        return OffsetDateTime.of(2026, 9, 22, hour, minute, 0, 0, ZoneOffset.UTC);
    }
}
