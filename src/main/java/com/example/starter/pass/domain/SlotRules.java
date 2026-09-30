package com.example.starter.pass.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;

/**
 * 时隙申请的纯业务规则校核（无 IO、无时钟），便于单元测试覆盖边界。
 *
 * <p>时间窗口统一为 UTC、半开区间 {@code [start, end)}；容量按整段窗口折算：
 * {@code 容量(字节/分钟) × 窗口秒数 / 60 ≥ 计划字节数}。</p>
 */
public final class SlotRules {

    private SlotRules() {
    }

    /**
     * 校核申请窗口是否为非空且完整容纳在预测可见窗口内。
     *
     * @return 不满足时返回 {@link RejectReason#WINDOW_GAP}
     */
    public static Optional<RejectReason> checkWindow(Instant requestStart, Instant requestEnd,
                                                     Instant predictionStart, Instant predictionEnd) {
        if (requestStart == null || requestEnd == null || predictionStart == null || predictionEnd == null
                || !requestStart.isBefore(requestEnd)) {
            return Optional.of(RejectReason.WINDOW_GAP);
        }
        boolean contained = !requestStart.isBefore(predictionStart) && !requestEnd.isAfter(predictionEnd);
        return contained ? Optional.empty() : Optional.of(RejectReason.WINDOW_GAP);
    }

    /**
     * 校核过境最低仰角是否达到站点门槛（相等视为达标）。
     *
     * @param passMinElevationDeg    预测给出的过境最低仰角，单位：度
     * @param stationMinElevationDeg 站点可接受的最低仰角，单位：度
     */
    public static Optional<RejectReason> checkElevation(BigDecimal passMinElevationDeg,
                                                        BigDecimal stationMinElevationDeg) {
        return passMinElevationDeg.compareTo(stationMinElevationDeg) >= 0
                ? Optional.empty()
                : Optional.of(RejectReason.ELEVATION_TOO_LOW);
    }

    /**
     * 校核整段窗口容量是否容纳得下计划数据量。
     *
     * @param plannedBytes     计划数据量，单位：字节
     * @param bytesPerMinute   天线容量快照，单位：字节/分钟
     * @param windowSeconds    窗口时长，单位：秒
     */
    public static boolean capacityFits(long plannedBytes, long bytesPerMinute, long windowSeconds) {
        if (plannedBytes <= 0 || bytesPerMinute <= 0 || windowSeconds <= 0) {
            return false;
        }
        BigInteger lhs = BigInteger.valueOf(plannedBytes).multiply(BigInteger.valueOf(60L));
        BigInteger rhs = BigInteger.valueOf(bytesPerMinute).multiply(BigInteger.valueOf(windowSeconds));
        return lhs.compareTo(rhs) <= 0;
    }

    /**
     * 依次校核窗口、仰角、容量，返回第一个不满足的原因；全部通过返回空。
     */
    public static Optional<RejectReason> validate(Instant requestStart, Instant requestEnd,
                                                  Instant predictionStart, Instant predictionEnd,
                                                  BigDecimal passMinElevationDeg,
                                                  BigDecimal stationMinElevationDeg,
                                                  long plannedBytes, long stationBytesPerMinute) {
        Optional<RejectReason> window = checkWindow(requestStart, requestEnd, predictionStart, predictionEnd);
        if (window.isPresent()) {
            return window;
        }
        Optional<RejectReason> elevation = checkElevation(passMinElevationDeg, stationMinElevationDeg);
        if (elevation.isPresent()) {
            return elevation;
        }
        long seconds = java.time.Duration.between(requestStart, requestEnd).getSeconds();
        return capacityFits(plannedBytes, stationBytesPerMinute, seconds)
                ? Optional.empty()
                : Optional.of(RejectReason.CAPACITY_EXCEEDED);
    }

    /**
     * 校核实际接收字节：不得为负且不得超过计划量。
     *
     * @param actualBytes  实际接收字节数，单位：字节
     * @param plannedBytes 计划数据量，单位：字节
     */
    public static Optional<RejectReason> checkActualBytes(long actualBytes, long plannedBytes) {
        if (actualBytes < 0 || actualBytes > plannedBytes) {
            return Optional.of(RejectReason.ACTUAL_BYTES_INVALID);
        }
        return Optional.empty();
    }
}
