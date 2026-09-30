package com.example.starter.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.OutlierRecord;
import com.example.starter.calibration.model.OutlierStatus;
import com.example.starter.calibration.service.BatchStatistics;

/**
 * 批次统计纯逻辑：实际数量、排除数量、待裁决差额及均值/极值；
 * REJECT 排除后重算，空活动集合的 null 含义稳定。
 */
class BatchStatisticsTest {

    private static Measurement reading(long id, String value, boolean excluded) {
        return new Measurement(id, "M-" + id, "INS", Instant.parse("2026-06-01T00:00:00Z"),
                new BigDecimal(value), new BigDecimal("0"), new BigDecimal("9"), "alice",
                1L, new BigDecimal(value), true,
                excluded ? MeasurementStatus.EXCLUDED : MeasurementStatus.PENDING,
                1L, 1, excluded,
                excluded ? Instant.parse("2026-06-02T00:00:00Z") : null,
                excluded ? 7L : null,
                Instant.parse("2026-06-01T00:00:00Z"));
    }

    private static OutlierRecord outlier(long id, OutlierStatus status) {
        return new OutlierRecord(id, "O-" + id, 1L, 1, id, new BigDecimal("5"), new BigDecimal("1"),
                new BigDecimal("4"), new BigDecimal("6"), new BigDecimal("9"), new BigDecimal("9"),
                new BigDecimal("4"), new BigDecimal("3"), "alice", status,
                Instant.parse("2026-06-01T00:00:00Z"),
                status == OutlierStatus.PENDING ? null : Instant.parse("2026-06-03T00:00:00Z"));
    }

    @Test
    void countsActualExcludedActiveAndPendingGap() {
        List<Measurement> measurements = List.of(
                reading(1, "2", false), reading(2, "3", false), reading(3, "9", true));
        List<OutlierRecord> outliers = List.of(
                outlier(3, OutlierStatus.REJECTED), outlier(4, OutlierStatus.PENDING));

        BatchStatistics stats = BatchStatistics.compute(measurements, outliers);

        assertEquals(3, stats.submittedTotal(), "实际提交总数=3");
        assertEquals(1, stats.excludedCount(), "REJECT 排除数量=1");
        assertEquals(2, stats.activeCount(), "参与统计的实际数量=2");
        assertEquals(2, stats.outlierTotal());
        assertEquals(1, stats.pendingOutlierCount(), "待裁决离群实际值=1，要求值=0");
        assertEquals(1, stats.rejectedOutlierCount());
        assertEquals(0, stats.acceptedOutlierCount());
        assertEquals(1, stats.pendingOutlierGap(), "差额=实际1-要求0=1");
        assertFalse(stats.isQuorumSatisfied(), "存在待裁决离群时放行前提不满足");
    }

    @Test
    void allOutliersDecidedSatisfiesReleaseAndRecomputesMeanOverActiveOnly() {
        // 读数 2、8、9，其中 9 被排除；活动集合 [2,8]，均值 5、极值 2/8
        List<Measurement> measurements = List.of(
                reading(1, "2", false), reading(2, "8", false), reading(3, "9", true));
        List<OutlierRecord> outliers = List.of(
                outlier(3, OutlierStatus.REJECTED), outlier(5, OutlierStatus.ACCEPTED));

        BatchStatistics stats = BatchStatistics.compute(measurements, outliers);

        assertTrue(stats.isQuorumSatisfied());
        assertEquals(0, stats.pendingOutlierGap(), "要求值0、实际0，差额0");
        assertEquals(2, stats.activeCount());
        assertEquals(new BigDecimal("5.000000000000"), stats.activeMean(),
                "排除 9 后活动读数 [2,8] 均值为 5");
        assertEquals(new BigDecimal("2"), stats.activeMin());
        assertEquals(new BigDecimal("8"), stats.activeMax());
    }

    @Test
    void emptyActiveSetHasNullAggregates() {
        BatchStatistics stats = BatchStatistics.compute(List.of(), List.of());
        assertEquals(0, stats.submittedTotal());
        assertEquals(0, stats.activeCount());
        assertTrue(stats.isQuorumSatisfied(), "无读数也无待裁决离群时，离群裁决前提满足（放行另有非空校验）");
        assertNull(stats.activeMean());
        assertNull(stats.activeMin());
        assertNull(stats.activeMax());
    }
}
