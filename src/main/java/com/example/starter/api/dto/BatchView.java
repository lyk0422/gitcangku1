package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批次查询视图：目标版本、每卷原测与复检、采纳依据、最终色差及放行冻结明细。
 */
public record BatchView(
        long batchId,
        String batchNo,
        String status,
        int targetVersion,
        double targetL,
        double targetA,
        double targetB,
        List<RollView> rolls,
        ReleaseView release) {

    /** 单条测色记录视图。 */
    public record MeasurementView(
            long id,
            String kind,
            double l,
            double a,
            double b,
            Instant measuredAt,
            int targetVersion,
            double deltaE,
            boolean qualified) {
    }

    /** 当前有效复检裁决视图。 */
    public record RulingView(String decision, String inspector, Instant ruledAt) {
    }

    /** 单卷视图：原测、复检、裁决与当前采纳结果。 */
    public record RollView(
            long rollId,
            String rollNo,
            MeasurementView original,
            MeasurementView recheck,
            RulingView ruling,
            String adoptedKind,
            Double adoptedDeltaE) {
    }

    /** 放行冻结明细视图。 */
    public record ReleaseLineView(
            String rollNo,
            String adoptedKind,
            double l,
            double a,
            double b,
            Instant measuredAt,
            double deltaE) {
    }

    /** 勘误视图。 */
    public record ErrataView(long id, String note, Instant createdAt) {
    }

    /** 放行单视图：冻结的目标版本、时刻、明细与勘误。 */
    public record ReleaseView(
            long releaseId,
            int targetVersion,
            Instant releasedAt,
            List<ReleaseLineView> lines,
            List<ErrataView> errata) {
    }
}