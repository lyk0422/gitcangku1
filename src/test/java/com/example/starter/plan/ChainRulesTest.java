package com.example.starter.plan.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.ChainRules.Segment;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 车底交路衔接纯逻辑单元测试：站点衔接、最小周转边界、跨运营日分组与整批并入。
 */
class ChainRulesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 25);

    private Segment seg(long id, String key, String origin, String destination,
                        int startHour, int endHour) {
        return new Segment(id, key, DAY, origin, destination,
                Instant.parse("2026-09-25T0" + startHour + ":00:00Z"),
                Instant.parse("2026-09-25T0" + endHour + ":00:00Z"));
    }

    private Segment segInst(long id, String key, String origin, String destination,
                            String startIso, String endIso) {
        return new Segment(id, key, DAY, origin, destination,
                Instant.parse(startIso), Instant.parse(endIso));
    }

    private Segment segDate(long id, String key, LocalDate date, String origin, String destination,
                            Instant start, Instant end) {
        return new Segment(id, key, date, origin, destination, start, end);
    }

    @Test
    void continuousChainHasNoViolation() {
        Segment a = seg(1, "A", "BJ", "TJ", 0, 1);
        Segment b = seg(2, "B", "TJ", "LF", 2, 3);
        // 间隔 60 分钟，要求 30，站点衔接
        assertThat(ChainRules.validateMerged(List.of(a), List.of(b), 30)).isEmpty();
    }

    @Test
    void stationMismatchReportsBreakpoint() {
        Segment a = seg(1, "A", "BJ", "TJ", 0, 1);
        Segment b = seg(2, "B", "LF", "CD", 2, 3);
        List<Map<String, Object>> violations = ChainRules.validateMerged(List.of(a), List.of(b), 10);
        assertThat(violations).hasSize(1);
        Map<String, Object> v = violations.get(0);
        assertThat(v.get("type")).isEqualTo(ChainRules.STATION_MISMATCH);
        assertThat(v.get("predecessorScheduleKey")).isEqualTo("A");
        assertThat(v.get("successorScheduleKey")).isEqualTo("B");
        assertThat(v.get("breakpoint")).isEqualTo("TJ->LF");
        assertThat(v.get("actualGapMinutes")).isEqualTo(60L);
        assertThat(v.get("requiredMinutes")).isEqualTo(10);
    }

    @Test
    void turnaroundBoundaryExactlyRequiredIsValid() {
        Segment a = seg(1, "A", "BJ", "TJ", 0, 1);
        // 90 分钟后始发
        Segment b = segInst(2, "B", "TJ", "LF",
                "2026-09-25T02:30:00Z", "2026-09-25T03:00:00Z");
        assertThat(ChainRules.validateMerged(List.of(a), List.of(b), 90)).isEmpty();
        // 少 1 分钟即违规，并报告实际间隔与要求值
        List<Map<String, Object>> violations = ChainRules.validateMerged(List.of(a), List.of(b), 91);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).get("type")).isEqualTo(ChainRules.TURNAROUND_INSUFFICIENT);
        assertThat(violations.get(0).get("actualGapMinutes")).isEqualTo(90L);
        assertThat(violations.get(0).get("requiredMinutes")).isEqualTo(91);
    }

    @Test
    void overlappingTimeProducesNegativeGap() {
        Segment a = seg(1, "A", "BJ", "TJ", 2, 4);
        Segment b = seg(2, "B", "TJ", "LF", 3, 5);
        List<Map<String, Object>> violations = ChainRules.validateMerged(List.of(a), List.of(b), 10);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).get("actualGapMinutes")).isEqualTo(-60L);
    }

    @Test
    void candidatesBetweenEachOtherAreAlsoChecked() {
        // 既有链为空，同批两段自身不衔接
        Segment a = seg(1, "A", "BJ", "TJ", 0, 1);
        Segment b = seg(2, "B", "CD", "CQ", 2, 3);
        List<Map<String, Object>> violations =
                ChainRules.validateMerged(List.of(), List.of(a, b), 10);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).get("breakpoint")).isEqualTo("TJ->CD");

        // 同批三段全部连续时无违规，即使与传入顺序无关
        Segment c = seg(3, "C", "LF", "CD", 4, 5);
        Segment b2 = seg(2, "B", "TJ", "LF", 2, 3);
        assertThat(ChainRules.validateMerged(List.of(), List.of(c, a, b2), 30)).isEmpty();
    }

    @Test
    void candidateInsertedIntoMiddleChecksBothSides() {
        // 既有 A(0-1 BJ->TJ) 与 C(4-5 LF->CD)，新段 B(2-3 TJ->LF) 插入后应全部连续
        Segment a = seg(1, "A", "BJ", "TJ", 0, 1);
        Segment c = seg(3, "C", "LF", "CD", 4, 5);
        Segment b = seg(2, "B", "TJ", "LF", 2, 3);
        assertThat(ChainRules.validateMerged(List.of(a, c), List.of(b), 60)).isEmpty();

        // B 的周转不足：A 终到 01:00，B 始发 01:30，仅 30 分钟
        Segment bEarly = segInst(2, "B", "TJ", "LF",
                "2026-09-25T01:30:00Z", "2026-09-25T02:00:00Z");
        List<Map<String, Object>> violations =
                ChainRules.validateMerged(List.of(a, c), List.of(bEarly), 45);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).get("successorScheduleKey")).isEqualTo("B");
    }

    @Test
    void differentOperationDaysAreValidatedIndependently() {
        LocalDate day1 = LocalDate.of(2026, 9, 25);
        LocalDate day2 = LocalDate.of(2026, 9, 26);
        Instant d1Morning = Instant.parse("2026-09-25T00:00:00Z");
        Instant d2Morning = Instant.parse("2026-09-26T00:00:00Z");
        // 不同运营日即便站点不衔接也不算断点
        Segment a = segDate(1, "A", day1, "BJ", "TJ", d1Morning, d1Morning.plusSeconds(3600));
        Segment b = segDate(2, "B", day2, "CD", "CQ", d2Morning, d2Morning.plusSeconds(3600));
        assertThat(ChainRules.validatePublished(List.of(a, b), 240)).isEmpty();
    }

    @Test
    void validatePublishedFlagsEveryViolatingAdjacentPair() {
        // 三对相邻段全部周转不足时逐条列出
        Segment a = seg(1, "A", "S1", "S2", 0, 1);
        Segment b = seg(2, "B", "S2", "S3", 1, 2);
        Segment c = seg(3, "C", "S3", "S4", 2, 3);
        List<Map<String, Object>> violations =
                ChainRules.validatePublished(List.of(a, b, c), 240);
        assertThat(violations).hasSize(2);
        assertThat(violations.get(0).get("predecessorScheduleKey")).isEqualTo("A");
        assertThat(violations.get(1).get("predecessorScheduleKey")).isEqualTo("B");
    }
}
