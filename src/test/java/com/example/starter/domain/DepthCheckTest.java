package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 潮高区间拼接覆盖与最低潮高计算的纯单元测试。
 */
class DepthCheckTest {

    private static final LocalDateTime T08 = LocalDateTime.of(2026, 10, 1, 8, 0);

    private static final LocalDateTime T10 = LocalDateTime.of(2026, 10, 1, 10, 0);

    @Test
    void coversWhenWindowsExactlyMatchRange() {
        List<TideWindow> windows = List.of(
                new TideWindow(T08, T10, new BigDecimal("0.70")));
        assertThat(DepthCheck.coversWholeRange(windows, T08, T10)).isTrue();
    }

    @Test
    void coversWhenAdjacentWindowsMeetEndToStart() {
        List<TideWindow> windows = List.of(
                new TideWindow(T08, LocalDateTime.of(2026, 10, 1, 9, 0), new BigDecimal("0.90")),
                new TideWindow(LocalDateTime.of(2026, 10, 1, 9, 0), T10, new BigDecimal("0.70")));
        assertThat(DepthCheck.coversWholeRange(windows, T08, T10)).isTrue();
    }

    @Test
    void doesNotCoverWhenGapExists() {
        List<TideWindow> windows = List.of(
                new TideWindow(T08, LocalDateTime.of(2026, 10, 1, 8, 30), new BigDecimal("0.90")),
                new TideWindow(LocalDateTime.of(2026, 10, 1, 9, 0), T10, new BigDecimal("0.70")));
        assertThat(DepthCheck.coversWholeRange(windows, T08, T10)).isFalse();
    }

    @Test
    void doesNotCoverWhenFirstWindowStartsLate() {
        List<TideWindow> windows = List.of(
                new TideWindow(LocalDateTime.of(2026, 10, 1, 8, 30), T10, new BigDecimal("0.70")));
        assertThat(DepthCheck.coversWholeRange(windows, T08, T10)).isFalse();
    }

    @Test
    void doesNotCoverWhenLastWindowEndsEarly() {
        List<TideWindow> windows = List.of(
                new TideWindow(T08, LocalDateTime.of(2026, 10, 1, 9, 30), new BigDecimal("0.70")));
        assertThat(DepthCheck.coversWholeRange(windows, T08, T10)).isFalse();
    }

    @Test
    void coversWhenWindowExtendsBeyondRange() {
        List<TideWindow> windows = List.of(
                new TideWindow(LocalDateTime.of(2026, 10, 1, 7, 0),
                        LocalDateTime.of(2026, 10, 1, 12, 0), new BigDecimal("0.70")));
        assertThat(DepthCheck.coversWholeRange(windows, T08, T10)).isTrue();
    }

    @Test
    void doesNotCoverEmptyWindows() {
        assertThat(DepthCheck.coversWholeRange(List.of(), T08, T10)).isFalse();
    }

    @Test
    void takesMinTideHeightAcrossWindowsIncludingNegative() {
        List<TideWindow> windows = List.of(
                new TideWindow(T08, LocalDateTime.of(2026, 10, 1, 9, 0), new BigDecimal("1.20")),
                new TideWindow(LocalDateTime.of(2026, 10, 1, 9, 0), T10, new BigDecimal("-0.30")));
        assertThat(DepthCheck.minTideHeight(windows)).isEqualByComparingTo("-0.30");
    }
}
