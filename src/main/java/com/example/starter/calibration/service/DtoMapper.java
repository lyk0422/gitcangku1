package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.util.List;

import com.example.starter.calibration.api.dto.CertificateResponse;
import com.example.starter.calibration.api.dto.DifferentialResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.ReleaseRecordResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Differential;
import com.example.starter.calibration.model.DifferentialStatus;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.ReleaseRecord;

/**
 * 领域模型到响应 DTO 的映射。
 */
final class DtoMapper {

    private DtoMapper() {
    }

    /** 十进制输出：去掉多余尾零，零统一输出 "0"。 */
    static String format(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.compareTo(BigDecimal.ZERO) == 0) {
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
                releases.stream().map(DtoMapper::toResponse).toList());
    }

    static ReleaseRecordResponse toResponse(ReleaseRecord record) {
        return new ReleaseRecordResponse(record.batchId(), record.releasedBy(), record.releasedAt());
    }

    static DifferentialResponse toResponse(Differential d, String referenceKey, String dutKey,
                                           boolean referenceCertRevoked, boolean dutCertRevoked) {
        boolean usable = d.status() == DifferentialStatus.RELEASED
                && !referenceCertRevoked && !dutCertRevoked;
        return new DifferentialResponse(
                d.id(),
                d.instrumentId(),
                d.referenceMeasurementId(),
                referenceKey,
                d.dutMeasurementId(),
                dutKey,
                d.referenceCertificateId(),
                d.dutCertificateId(),
                format(d.referenceValue()),
                format(d.dutValue()),
                format(d.diffValue()),
                d.displayDiff().toPlainString(),
                format(d.lowerLimit()),
                format(d.upperLimit()),
                d.passed(),
                d.status().name(),
                usable,
                d.version(),
                d.createdBy(),
                d.createdAt(),
                d.releasedBy(),
                d.releasedAt(),
                d.cancelledBy(),
                d.cancelledAt());
    }
}
