package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.util.List;

import com.example.starter.calibration.api.dto.AuditView;
import com.example.starter.calibration.api.dto.OutlierView;
import com.example.starter.calibration.api.dto.ReadingView;
import com.example.starter.calibration.api.dto.ReviewView;
import com.example.starter.calibration.api.dto.SnapshotView;
import com.example.starter.calibration.api.dto.StatsView;
import com.example.starter.calibration.model.BatchReading;
import com.example.starter.calibration.model.BatchReleaseSnapshot;
import com.example.starter.calibration.model.BatchStatsRecord;
import com.example.starter.calibration.model.OutlierAudit;
import com.example.starter.calibration.model.OutlierRecord;
import com.example.starter.calibration.model.OutlierReview;

/**
 * 离群复核子域的模型到视图 DTO 映射。十进制输出复用 {@link DtoMapper#format}。
 */
final class BatchDtoMapper {

    private BatchDtoMapper() {
    }

    static String format(BigDecimal value) {
        return value == null ? null : DtoMapper.format(value);
    }

    static ReadingView toView(BatchReading r) {
        return new ReadingView(r.id(), r.readingKey(), r.batchRevision(), r.measuredAt(),
                DtoMapper.format(r.value()), r.submittedBy(), r.certificateId(), r.certificateVersion(),
                r.excluded(), r.createdAt());
    }

    static ReviewView toView(OutlierReview r) {
        return new ReviewView(r.id(), r.reviewer(), r.decision().name(), r.reason(),
                r.batchRevision(), r.certificateId(), r.certificateVersion(), r.status().name(), r.createdAt());
    }

    static OutlierView toView(OutlierRecord o, String readingKey, List<OutlierReview> reviews) {
        return new OutlierView(o.id(), o.readingId(), readingKey, o.batchRevision(),
                DtoMapper.format(o.targetValue()), DtoMapper.format(o.allowedDeviation()),
                DtoMapper.format(o.readingValue()), DtoMapper.format(o.actualDeviation()),
                DtoMapper.format(o.excess()), o.status().name(), o.decidedAt(), o.createdAt(),
                reviews.stream().map(BatchDtoMapper::toView).toList());
    }

    static StatsView toView(BatchStatsRecord s) {
        return new StatsView(s.id(), s.batchRevision(), s.includedCount(), s.excludedCount(),
                format(s.meanValue()), format(s.minValue()), format(s.maxValue()),
                s.trigger().name(), s.createdAt());
    }

    static SnapshotView toView(BatchReleaseSnapshot s) {
        return new SnapshotView(s.id(), s.batchRevision(), s.includedCount(), s.excludedCount(),
                format(s.meanValue()), format(s.minValue()), format(s.maxValue()),
                s.releasedBy(), s.releasedAt());
    }

    static AuditView toView(OutlierAudit a) {
        return new AuditView(a.id(), a.eventType().name(), a.outlierId(), a.batchRevision(),
                format(a.targetValue()), format(a.allowedDeviation()), format(a.readingValue()),
                a.actor(), a.decision(), a.detail(), a.createdAt());
    }
}