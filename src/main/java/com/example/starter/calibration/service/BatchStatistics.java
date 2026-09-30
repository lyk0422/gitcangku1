package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.OutlierRecord;

/**
 * 批次统计纯逻辑。输入当前修订下一致的读数集合与离群集合，输出封存用统计值。
 * 数量语义明确区分：
 * <ul>
 *   <li>submittedTotal：修订下读数提交总数（实际值）；</li>
 *   <li>excludedCount：被 REJECT 裁决排除的数量；</li>
 *   <li>activeCount：参与统计与放行的实际数量 = submittedTotal - excludedCount；</li>
 *   <li>pendingOutlierCount：仍未形成有效裁决（含待仲裁）的离群数量；要求值为 0，差额即该数。</li>
 * </ul>
 * 均值与极差基于未舍入计算值（{@code computedValue}），仅对展示结果做 HALF_UP 12 位舍入。
 */
public record BatchStatistics(
        int submittedTotal,
        int excludedCount,
        int activeCount,
        int outlierTotal,
        int pendingOutlierCount,
        int acceptedOutlierCount,
        int rejectedOutlierCount,
        BigDecimal activeMean,
        BigDecimal activeMin,
        BigDecimal activeMax) {

    /** 放行要求的待裁决离群数量（要求值）。 */
    public static final int REQUIRED_PENDING_OUTLIERS = 0;

    /**
     * 计算统计。outliers 为当前修订下全部离群记录；measurements 为全部读数（含已排除）。
     */
    public static BatchStatistics compute(List<Measurement> measurements, List<OutlierRecord> outliers) {
        int submittedTotal = measurements.size();
        int excludedCount = (int) measurements.stream().filter(Measurement::excluded).count();
        int activeCount = submittedTotal - excludedCount;

        int pending = 0;
        int accepted = 0;
        int rejected = 0;
        for (OutlierRecord outlier : outliers) {
            switch (outlier.status()) {
                case PENDING -> pending++;
                case ACCEPTED -> accepted++;
                case REJECTED -> rejected++;
            }
        }

        List<BigDecimal> activeValues = measurements.stream()
                .filter(m -> !m.excluded())
                .map(Measurement::computedValue)
                .toList();
        BigDecimal mean = null;
        BigDecimal min = null;
        BigDecimal max = null;
        if (!activeValues.isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal value : activeValues) {
                sum = sum.add(value);
            }
            mean = sum.divide(BigDecimal.valueOf(activeValues.size()), 12, RoundingMode.HALF_UP);
            min = activeValues.stream().min(BigDecimal::compareTo).orElseThrow();
            max = activeValues.stream().max(BigDecimal::compareTo).orElseThrow();
        }
        return new BatchStatistics(submittedTotal, excludedCount, activeCount, outliers.size(),
                pending, accepted, rejected, mean, min, max);
    }

    /** 是否满足放行的离群裁决前提：待裁决（含待仲裁）离群差额为 0。 */
    public boolean isQuorumSatisfied() {
        return pendingOutlierCount == REQUIRED_PENDING_OUTLIERS;
    }

    /** 距放行要求还差的待裁决离群数量（要求值 0 - 实际待裁决数，不为负）。 */
    public int pendingOutlierGap() {
        return Math.max(0, pendingOutlierCount - REQUIRED_PENDING_OUTLIERS);
    }
}
