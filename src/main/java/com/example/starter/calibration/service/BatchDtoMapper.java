package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.util.List;

import com.example.starter.calibration.api.dto.BatchReleaseResponse;
import com.example.starter.calibration.api.dto.BatchResponse;
import com.example.starter.calibration.api.dto.OutlierResponse;
import com.example.starter.calibration.api.dto.ReviewResponse;
import com.example.starter.calibration.api.dto.StatsRecordResponse;
import com.example.starter.calibration.api.dto.StatsResponse;
import com.example.starter.calibration.api.dto.VerdictResponse;
import com.example.starter.calibration.model.BatchRelease;
import com.example.starter.calibration.model.BatchStatsRecord;
import com.example.starter.calibration.model.MeasurementBatch;
import com.example.starter.calibration.model.OutlierRecord;
import com.example.starter.calibration.model.OutlierReview;
import com.example.starter.calibration.model.OutlierVerdict;

/**
 * 批次子系统领域模型到响应 DTO 的映射。
 */
final class BatchDtoMapper {

    private BatchDtoMapper() {
    }

    static String format(BigDecimal value) {
        return value == null ? null : DtoMapper.format(value);
    }

    static StatsResponse toResponse(BatchStats.Values stats) {
        if (stats == null) {
            return null;
        }
        return new StatsResponse(stats.includedCount(), stats.excludedCount(),
                format(stats.mean()), format(stats.min()), format(stats.max()));
    }

    static BatchResponse toResponse(MeasurementBatch batch, BatchStats.Values stats, int pendingOutliers) {
        return new BatchResponse(
                batch.id(),
                batch.batchKey(),
                batch.instrumentId(),
                format(batch.targetValue()),
                format(batch.allowedDeviation()),
                batch.revision(),
                batch.status().name(),
                batch.createdAt(),
                toResponse(stats),
                pendingOutliers);
    }

    static ReviewResponse toResponse(OutlierReview review, String outlierStatus) {
        return new ReviewResponse(
                review.id(),
                review.outlierId(),
                review.requestId(),
                review.reviewer(),
                review.decision().name(),
                review.reason(),
                review.batchRevision(),
                review.certificateId(),
                review.stale(),
                outlierStatus,
                review.createdAt());
    }

    static OutlierResponse toResponse(OutlierRecord outlier, String batchKey,
                                      OutlierVerdict verdict, List<OutlierReview> reviews) {
        return new OutlierResponse(
                outlier.id(),
                batchKey,
                outlier.batchRevision(),
                outlier.readingId(),
                format(outlier.targetValue()),
                format(outlier.allowedDeviation()),
                format(outlier.readingValue()),
                format(outlier.actualDeviation()),
                format(outlier.excessDeviation()),
                outlier.status().name(),
                outlier.detectedAt(),
                outlier.verdictAt(),
                verdict == null ? null : new VerdictResponse(verdict.decision().name(), verdict.formedAt()),
                reviews.stream().map(r -> toResponse(r, outlier.status().name())).toList());
    }

    static StatsRecordResponse toResponse(BatchStatsRecord record) {
        return new StatsRecordResponse(
                record.batchRevision(),
                record.triggerType(),
                record.includedCount(),
                record.excludedCount(),
                format(record.meanValue()),
                format(record.minValue()),
                format(record.maxValue()),
                record.createdAt());
    }

    static BatchReleaseResponse toResponse(MeasurementBatch batch, BatchRelease release) {
        return new BatchReleaseResponse(
                batch.batchKey(),
                release.batchRevision(),
                release.releasedBy(),
                release.releasedAt(),
                release.includedCount(),
                release.excludedCount(),
                format(release.meanValue()),
                format(release.minValue()),
                format(release.maxValue()));
    }
}
