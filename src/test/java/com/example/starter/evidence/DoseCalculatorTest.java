package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.DoseSegmentRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 剂量段校验与剂量计算单元测试：精确累加不舍入、覆盖/重叠校验、HALF_UP 显示。
 */
class DoseCalculatorTest {

    private static final LocalDateTime LOAN_AT = LocalDateTime.of(2026, 9, 22, 8, 0, 0);

    private static DoseSegmentRequest segment(LocalDateTime start, LocalDateTime end, String lux) {
        return new DoseSegmentRequest(start, end, new BigDecimal(lux));
    }

    private static void assertBadRequest(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void singleSegmentDoseIsLuxTimesExactMinutes() {
        LocalDateTime end = LOAN_AT.plusMinutes(10);
        DoseCalculator.DoseComputation result = DoseCalculator.validateAndCompute(
                List.of(segment(LOAN_AT, end, "6")), LOAN_AT, end);
        assertThat(result.totalDose()).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(result.segmentDoses()).hasSize(1);
        assertThat(result.segmentDoses().get(0)).isEqualByComparingTo(new BigDecimal("60"));
    }

    @Test
    void multipleSegmentsAccumulateWithoutRounding() {
        LocalDateTime t1 = LOAN_AT.plusMinutes(10);
        LocalDateTime t2 = t1.plusMinutes(5);
        LocalDateTime t3 = t2.plusMinutes(15);
        DoseCalculator.DoseComputation result = DoseCalculator.validateAndCompute(
                List.of(segment(t2, t3, "1.5"), segment(LOAN_AT, t1, "6"), segment(t1, t2, "0.001")),
                LOAN_AT, t3);
        // 60 + 0.005 + 22.5 = 82.505，乱序输入按起点排序后首尾相接
        assertThat(result.totalDose()).isEqualByComparingTo(new BigDecimal("82.505"));
        assertThat(result.sortedSegments().get(0).startAt()).isEqualTo(LOAN_AT);
    }

    @Test
    void fractionalMinuteDoseKeepsFullPrecisionAndDisplayRoundsHalfUp() {
        LocalDateTime end = LOAN_AT.plusSeconds(1);
        DoseCalculator.DoseComputation result = DoseCalculator.validateAndCompute(
                List.of(segment(LOAN_AT, end, "1")), LOAN_AT, end);
        // 1/60 分钟：不舍入，保留 DECIMAL128 精度；显示 HALF_UP 四位
        assertThat(result.totalDose()).isEqualByComparingTo(
                BigDecimal.ONE.divide(BigDecimal.valueOf(60), MathContext.DECIMAL128));
        assertThat(result.totalDose()).isLessThan(new BigDecimal("0.0167"));
        assertThat(DoseCalculator.display(result.totalDose())).isEqualTo("0.0167");
        assertThat(DoseCalculator.display(new BigDecimal("60"))).isEqualTo("60.0000");
    }

    @Test
    void missingOrEmptySegmentsRejected() {
        LocalDateTime end = LOAN_AT.plusMinutes(10);
        assertBadRequest(() -> DoseCalculator.validateAndCompute(null, LOAN_AT, end));
        assertBadRequest(() -> DoseCalculator.validateAndCompute(List.of(), LOAN_AT, end));
    }

    @Test
    void moreThanTwentySegmentsRejected() {
        List<DoseSegmentRequest> segments = new ArrayList<>();
        LocalDateTime cursor = LOAN_AT;
        for (int i = 0; i < 21; i++) {
            segments.add(segment(cursor, cursor.plusMinutes(1), "1"));
            cursor = cursor.plusMinutes(1);
        }
        LocalDateTime end = cursor;
        assertBadRequest(() -> DoseCalculator.validateAndCompute(segments, LOAN_AT, end));
    }

    @Test
    void overlappingSegmentsRejected() {
        LocalDateTime end = LOAN_AT.plusMinutes(20);
        assertBadRequest(() -> DoseCalculator.validateAndCompute(
                List.of(segment(LOAN_AT, LOAN_AT.plusMinutes(15), "1"),
                        segment(LOAN_AT.plusMinutes(10), end, "1")), LOAN_AT, end));
    }

    @Test
    void gapBetweenSegmentsRejected() {
        LocalDateTime end = LOAN_AT.plusMinutes(20);
        assertBadRequest(() -> DoseCalculator.validateAndCompute(
                List.of(segment(LOAN_AT, LOAN_AT.plusMinutes(5), "1"),
                        segment(LOAN_AT.plusMinutes(10), end, "1")), LOAN_AT, end));
    }

    @Test
    void segmentsNotCoveringLoanStartOrReturnEndRejected() {
        LocalDateTime end = LOAN_AT.plusMinutes(10);
        // 首段起点晚于实际借出时刻
        assertBadRequest(() -> DoseCalculator.validateAndCompute(
                List.of(segment(LOAN_AT.plusMinutes(1), end, "1")), LOAN_AT, end));
        // 末段终点早于实际归还时刻
        assertBadRequest(() -> DoseCalculator.validateAndCompute(
                List.of(segment(LOAN_AT, end.minusMinutes(1), "1")), LOAN_AT, end));
        // 末段终点晚于实际归还时刻
        assertBadRequest(() -> DoseCalculator.validateAndCompute(
                List.of(segment(LOAN_AT, end.plusMinutes(1), "1")), LOAN_AT, end));
    }

    @Test
    void segmentWithStartNotBeforeEndRejected() {
        LocalDateTime end = LOAN_AT.plusMinutes(10);
        assertBadRequest(() -> DoseCalculator.validateAndCompute(
                List.of(segment(end, LOAN_AT, "1")), LOAN_AT, end));
        assertBadRequest(() -> DoseCalculator.validateAndCompute(
                List.of(segment(LOAN_AT, LOAN_AT, "1")), LOAN_AT, LOAN_AT));
    }

    @Test
    void negativeLuxRejected() {
        LocalDateTime end = LOAN_AT.plusMinutes(10);
        assertBadRequest(() -> DoseCalculator.validateAndCompute(
                List.of(segment(LOAN_AT, end, "-0.001")), LOAN_AT, end));
    }
}
