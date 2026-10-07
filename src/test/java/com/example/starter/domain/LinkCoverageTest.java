package com.example.starter.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链路覆盖计算纯单元测试：合并并集、跨段连续失联、精确有理数阈值。
 */
@DisplayName("链路覆盖合并与连续失联计算")
class LinkCoverageTest {

    private static SegmentProfile seg(int durationMs, CoverageInterval... coverage) {
        return new SegmentProfile(durationMs, List.of(coverage));
    }

    // ============================ 合并并集 ============================

    @Test
    @DisplayName("重叠与端点相接区间合并，乱序输入归一")
    void mergeOverlappingAndTouching() {
        List<CoverageInterval> merged = LinkCoverage.merge(List.of(
                new CoverageInterval(2000, 4000),
                new CoverageInterval(0, 2000),
                new CoverageInterval(3000, 5000),
                new CoverageInterval(7000, 9000)));
        assertEquals(List.of(new CoverageInterval(0, 5000), new CoverageInterval(7000, 9000)),
                merged);
    }

    @Test
    @DisplayName("嵌套区间不双计，空覆盖并集为空")
    void nestedNotDoubleCountedAndEmpty() {
        assertEquals(List.of(new CoverageInterval(0, 10000)), LinkCoverage.merge(List.of(
                new CoverageInterval(0, 10000),
                new CoverageInterval(4000, 6000),
                new CoverageInterval(2000, 3000))));
        assertEquals(List.of(), LinkCoverage.merge(List.of()));
    }

    @Test
    @DisplayName("端点相接的零时长缺口不计：两段相接并成整段")
    void zeroLengthEndpointGapNotCounted() {
        List<LinkCoverage.LinkGap> gaps = LinkCoverage.continuousLosses(List.of(
                seg(10000, new CoverageInterval(0, 5000), new CoverageInterval(5000, 10000))));
        assertTrue(gaps.isEmpty());
    }

    // ============================ 连续失联 ============================

    @Test
    @DisplayName("单段头部/中间/尾部缺口分别计算，恰好 5000 毫秒允许")
    void singleSegmentHeadMidTailGaps() {
        List<LinkCoverage.LinkGap> gaps = LinkCoverage.continuousLosses(List.of(
                seg(10000, new CoverageInterval(1000, 2000), new CoverageInterval(4000, 5000))));
        assertEquals(3, gaps.size());
        assertEquals("1000", gaps.get(0).lossMs());
        assertEquals("2000", gaps.get(1).lossMs());
        assertEquals("5000", gaps.get(2).lossMs());
        assertFalse(LinkCoverage.anyBlocked(gaps), "等于 5000 毫秒允许");
    }

    @Test
    @DisplayName("合成样例：两段各 10 秒，前 80% + 后 70%，相接失联 2+3=5 秒允许")
    void syntheticTwoSegmentsExactly5000Allowed() {
        List<LinkCoverage.LinkGap> gaps = LinkCoverage.continuousLosses(List.of(
                seg(10000, new CoverageInterval(0, 8000)),
                seg(10000, new CoverageInterval(3000, 10000))));
        assertEquals(1, gaps.size());
        assertEquals(0, gaps.get(0).fromSegment());
        assertEquals(1, gaps.get(0).toSegment());
        assertEquals("5000", gaps.get(0).lossMs());
        assertFalse(LinkCoverage.anyBlocked(gaps));
    }

    @Test
    @DisplayName("合成样例：第二段改后 69%，相接失联 2+3.1=5.1 秒阻断")
    void syntheticTwoSegments5100Blocked() {
        List<LinkCoverage.LinkGap> gaps = LinkCoverage.continuousLosses(List.of(
                seg(10000, new CoverageInterval(0, 8000)),
                seg(10000, new CoverageInterval(3100, 10000))));
        assertEquals(1, gaps.size());
        assertEquals("5100", gaps.get(0).lossMs());
        assertTrue(LinkCoverage.anyBlocked(gaps));
    }

    @Test
    @DisplayName("全段失联串联前后缺口成同一连续失联")
    void fullyUncoveredMiddleSegmentChainsGaps() {
        List<LinkCoverage.LinkGap> gaps = LinkCoverage.continuousLosses(List.of(
                seg(10000, new CoverageInterval(0, 9000)),
                seg(20000),
                seg(10000, new CoverageInterval(1000, 10000))));
        assertEquals(1, gaps.size());
        assertEquals(0, gaps.get(0).fromSegment());
        assertEquals(2, gaps.get(0).toSegment());
        assertEquals("22000", gaps.get(0).lossMs());
        assertTrue(LinkCoverage.anyBlocked(gaps));
    }

    @Test
    @DisplayName("空覆盖合法表示全段失联；全程覆盖无缺口")
    void emptyCoverageIsFullLossAndFullCoverageHasNoGap() {
        List<LinkCoverage.LinkGap> empty = LinkCoverage.continuousLosses(List.of(seg(4000)));
        assertEquals(1, empty.size());
        assertEquals("4000", empty.get(0).lossMs());
        assertFalse(LinkCoverage.anyBlocked(empty));

        List<LinkCoverage.LinkGap> blocked = LinkCoverage.continuousLosses(List.of(seg(6000)));
        assertEquals("6000", blocked.get(0).lossMs());
        assertTrue(LinkCoverage.anyBlocked(blocked));

        assertTrue(LinkCoverage.continuousLosses(List.of(
                seg(10000, new CoverageInterval(0, 10000)))).isEmpty());
    }

    @Test
    @DisplayName("精确有理数：1 刻度 × 3 毫秒 = 0.0003 毫秒，不提前取整")
    void exactRationalNotRounded() {
        List<LinkCoverage.LinkGap> gaps = LinkCoverage.continuousLosses(List.of(
                seg(3, new CoverageInterval(0, 9999))));
        assertEquals(1, gaps.size());
        assertEquals("0.0003", gaps.get(0).lossMs());
        assertFalse(LinkCoverage.anyBlocked(gaps));
    }

    @Test
    @DisplayName("阈值边界：5000 允许，5001 阻断")
    void thresholdBoundary() {
        List<LinkCoverage.LinkGap> allowed = LinkCoverage.continuousLosses(List.of(
                seg(10000, new CoverageInterval(0, 5000))));
        assertEquals("5000", allowed.get(0).lossMs());
        assertFalse(LinkCoverage.anyBlocked(allowed));

        List<LinkCoverage.LinkGap> blocked = LinkCoverage.continuousLosses(List.of(
                seg(10000, new CoverageInterval(0, 4999))));
        assertEquals("5001", blocked.get(0).lossMs());
        assertTrue(LinkCoverage.anyBlocked(blocked));
    }

    @Test
    @DisplayName("尾缺口不与全覆盖相邻段连接")
    void tailGapDoesNotJoinFullyCoveredNextSegment() {
        List<LinkCoverage.LinkGap> gaps = LinkCoverage.continuousLosses(List.of(
                seg(10000, new CoverageInterval(0, 8000)),
                seg(10000, new CoverageInterval(0, 10000))));
        assertEquals(1, gaps.size());
        assertEquals(0, gaps.get(0).fromSegment());
        assertEquals(0, gaps.get(0).toSegment());
        assertEquals("2000", gaps.get(0).lossMs());
    }
}
