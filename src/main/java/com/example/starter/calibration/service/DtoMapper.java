package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.List;

import com.example.starter.calibration.api.dto.BatchDetailResponse;
import com.example.starter.calibration.api.dto.BatchResponse;
import com.example.starter.calibration.api.dto.CertificateResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.OutlierResponse;
import com.example.starter.calibration.api.dto.ReleaseRecordResponse;
import com.example.starter.calibration.api.dto.ReviewResponse;
import com.example.starter.calibration.api.dto.RevisionResponse;
import com.example.starter.calibration.model.BatchEvent;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementBatch;
import com.example.starter.calibration.model.MeasurementRevision;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.OutlierRecord;
import com.example.starter.calibration.model.OutlierReview;
import com.example.starter.calibration.model.ReleaseRecord;

/**
 * 领域模型到响应 DTO 的映射。
 */
final class DtoMapper {

    private DtoMapper() {
    }

    /** 十进制输出：去掉多余尾零，零统一输出 "0"；null 保持 null（空集合统计语义）。 */
    static String format(java.math.BigDecimal value) {
        if (value == null) {
            return null;
        }
        java.math.BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.compareTo(java.math.BigDecimal.ZERO) == 0) {
            return "0";
        }
        return stripped.toPlainString();
    }

    static CertificateResponse toResponse(Certificate cert) {
        return new CertificateResponse(
                cert.id(),
                cert.instrumentId(),
                cert.validFrom(),
                cert.validTo(),
                format(cert.a()),
                format(cert.b()),
                cert.revoked(),
                cert.revokedAt(),
                cert.createdAt());
    }

    static MeasurementResponse toResponse(Measurement m, boolean certificateRevoked,
                                          List<ReleaseRecord> releases) {
        boolean usable = m.status() == MeasurementStatus.RELEASED && !certificateRevoked;
        return new MeasurementResponse(
                m.id(),
                m.measurementKey(),
                m.instrumentId(),
                m.measuredAt(),
                format(m.rawReading()),
                format(m.lowerLimit()),
                format(m.upperLimit()),
                m.submittedBy(),
                m.certificateId(),
                format(m.computedValue()),
                m.displayValue().toPlainString(),
                m.passed(),
                m.status().name(),
                usable,
                m.createdAt(),
                releases.stream().map(DtoMapper::toResponse).toList(),
                m.excluded());
    }

    static ReleaseRecordResponse toResponse(ReleaseRecord record) {
        return new ReleaseRecordResponse(record.batchId(), record.releasedBy(), record.releasedAt());
    }

    static BatchResponse toResponse(MeasurementBatch batch) {
        return new BatchResponse(
                batch.batchKey(),
                batch.instrumentId(),
                batch.currentRevisionNo(),
                batch.status().name(),
                batch.createdBy(),
                batch.createdAt());
    }

    static RevisionResponse toResponse(MeasurementRevision revision) {
        return new RevisionResponse(
                revision.revisionNo(),
                format(revision.targetValue()),
                format(revision.tolerance()),
                format(revision.lowerLimit()),
                format(revision.upperLimit()),
                revision.reason(),
                revision.createdBy(),
                revision.createdAt(),
                revision.releasedAt());
    }

    static ReviewResponse toResponse(OutlierReview review) {
        return new ReviewResponse(
                review.id(),
                review.reviewer(),
                review.decision().name(),
                review.reason(),
                review.measurementRevision(),
                review.certificateId(),
                review.stale(),
                review.createdAt());
    }

    static OutlierResponse toResponse(OutlierRecord outlier, String batchKey, String measurementKey,
                                      List<String> staleReasons, List<OutlierReview> reviews) {
        int accepts = 0;
        int rejects = 0;
        for (OutlierReview review : reviews) {
            if (review.stale()) {
                continue;
            }
            if (review.decision().name().equals("ACCEPT")) {
                accepts++;
            } else {
                rejects++;
            }
        }
        return new OutlierResponse(
                outlier.outlierKey(),
                batchKey,
                outlier.revisionNo(),
                measurementKey,
                format(outlier.targetValue()),
                format(outlier.tolerance()),
                format(outlier.lowerLimit()),
                format(outlier.upperLimit()),
                format(outlier.rawReading()),
                format(outlier.computedValue()),
                format(outlier.deviation()),
                format(outlier.excess()),
                outlier.submittedBy(),
                outlier.status().name(),
                outlier.detectedAt(),
                outlier.decidedAt(),
                accepts,
                rejects,
                List.copyOf(staleReasons),
                reviews.stream().map(DtoMapper::toResponse).toList());
    }

    static BatchDetailResponse.BatchEventResponse toResponse(BatchEvent event) {
        return new BatchDetailResponse.BatchEventResponse(
                event.id(),
                event.revisionNo(),
                event.eventType(),
                event.actor(),
                event.payload(),
                event.occurredAt());
    }

    static BatchDetailResponse.BatchStatisticsResponse toResponse(BatchStatistics stats, Instant computedAt) {
        return new BatchDetailResponse.BatchStatisticsResponse(
                stats.submittedTotal(),
                stats.excludedCount(),
                stats.activeCount(),
                stats.outlierTotal(),
                stats.pendingOutlierCount(),
                stats.acceptedOutlierCount(),
                stats.rejectedOutlierCount(),
                BatchStatistics.REQUIRED_PENDING_OUTLIERS,
                stats.pendingOutlierGap(),
                !stats.isQuorumSatisfied(),
                format(stats.activeMean()),
                format(stats.activeMin()),
                format(stats.activeMax()),
                computedAt);
    }
}
