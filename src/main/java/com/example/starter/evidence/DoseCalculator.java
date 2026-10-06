package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.DoseSegmentRequest;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 剂量段校验与剂量计算。剂量=照度×精确分钟，按 DECIMAL128 精度累加，不做舍入；
 * 显示值统一 HALF_UP 保留4位小数，仅用于展示，不参与任何判定。
 */
public final class DoseCalculator {

    /**
     * 单次归还允许的最大段数。
     */
    public static final int MAX_SEGMENTS = 20;

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal NANOS_PER_MINUTE = BigDecimal.valueOf(60_000_000_000L);

    private DoseCalculator() {
    }

    /**
     * 校验剂量段并计算当次总剂量。要求：1至20段；每段起点早于终点；
     * 区间互不重叠且首尾相接，完整覆盖 [loanAt, returnedAt]。
     *
     * @param segments   剂量段（顺序不限，校验前按起点排序）
     * @param loanAt     实际借出时刻（UTC）
     * @param returnedAt 实际归还时刻（UTC）
     * @return 各段剂量（按时间顺序）与当次总剂量
     */
    public static DoseComputation validateAndCompute(List<DoseSegmentRequest> segments,
                                                     LocalDateTime loanAt,
                                                     LocalDateTime returnedAt) {
        if (segments == null || segments.isEmpty()) {
            throw ApiException.badRequest("缺少剂量段：已登记上限的证物归还须携带1至" + MAX_SEGMENTS + "段");
        }
        if (segments.size() > MAX_SEGMENTS) {
            throw ApiException.badRequest("剂量段数量超过上限: " + segments.size());
        }
        List<DoseSegmentRequest> sorted = new ArrayList<>(segments);
        sorted.sort(Comparator.comparing(DoseSegmentRequest::startAt));
        List<BigDecimal> doses = new ArrayList<>(sorted.size());
        BigDecimal total = BigDecimal.ZERO;
        LocalDateTime expectedStart = loanAt;
        for (DoseSegmentRequest segment : sorted) {
            if (!segment.startAt().isBefore(segment.endAt())) {
                throw ApiException.badRequest("剂量段起点须早于终点: " + segment.startAt());
            }
            if (segment.lux().signum() < 0) {
                throw ApiException.badRequest("照度不得为负: " + segment.lux());
            }
            if (!segment.startAt().equals(expectedStart)) {
                throw ApiException.badRequest("剂量段须互不重叠且完整覆盖实际借出至归还");
            }
            BigDecimal dose = computeDose(segment.lux(), segment.startAt(), segment.endAt());
            doses.add(dose);
            total = total.add(dose, MC);
            expectedStart = segment.endAt();
        }
        if (!expectedStart.equals(returnedAt)) {
            throw ApiException.badRequest("剂量段须完整覆盖至实际归还时刻: " + returnedAt);
        }
        return new DoseComputation(sorted, doses, total);
    }

    /**
     * 单段剂量=照度×精确分钟（纳秒级），DECIMAL128 精度，不舍入。
     */
    public static BigDecimal computeDose(BigDecimal lux, LocalDateTime startAt,
                                         LocalDateTime endAt) {
        BigDecimal nanos = BigDecimal.valueOf(Duration.between(startAt, endAt).toNanos());
        BigDecimal minutes = nanos.divide(NANOS_PER_MINUTE, MC);
        return lux.multiply(minutes, MC);
    }

    /**
     * 显示值：HALF_UP 保留4位小数，仅用于展示，不参与判定。
     */
    public static String display(BigDecimal dose) {
        return dose.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 剂量计算结果。
     *
     * @param sortedSegments 按起点升序的剂量段
     * @param segmentDoses   各段剂量（与 sortedSegments 同序）
     * @param totalDose      当次总剂量
     */
    public record DoseComputation(List<DoseSegmentRequest> sortedSegments,
                                  List<BigDecimal> segmentDoses,
                                  BigDecimal totalDose) {
    }
}
