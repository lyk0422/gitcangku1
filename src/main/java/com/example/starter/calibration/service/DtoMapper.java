package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.util.List;

import com.example.starter.calibration.api.dto.CertificateResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.ReleaseRecordResponse;
import com.example.starter.calibration.api.dto.SessionEntryResponse;
import com.example.starter.calibration.api.dto.SessionResponse;
import com.example.starter.calibration.model.CalibrationSession;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.ReleaseRecord;
import com.example.starter.calibration.model.SessionEntry;

/**
 * 领域模型到响应 DTO 的映射。
 */
final class DtoMapper {

    private DtoMapper() {
    }

    /** 十进制输出：去掉多余尾零，零统一输出 "0"；null 原样返回。 */
    static String format(BigDecimal value) {
        if (value == null) {
            return null;
        }
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
                cert.certType().name(),
                format(cert.a()),
                format(cert.b()),
                format(cert.slope()),
                format(cert.upOffset()),
                format(cert.downOffset()),
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
                m.sessionId(),
                m.direction(),
                m.createdAt(),
                releases.stream().map(DtoMapper::toResponse).toList());
    }

    static ReleaseRecordResponse toResponse(ReleaseRecord record) {
        return new ReleaseRecordResponse(record.batchId(), record.releasedBy(), record.releasedAt());
    }

    static SessionResponse toResponse(CalibrationSession session, List<SessionEntry> entries,
                                      java.util.Map<Long, String> measurementKeys) {
        return new SessionResponse(
                session.id(),
                session.instrumentId(),
                session.certificateId(),
                session.status().name(),
                session.version(),
                session.createdAt(),
                session.closedAt(),
                entries.stream()
                        .map(e -> toResponse(e, measurementKeys.getOrDefault(e.measurementId(), "")))
                        .toList());
    }

    static SessionEntryResponse toResponse(SessionEntry entry, String measurementKey) {
        return new SessionEntryResponse(
                entry.seq(),
                format(entry.rawValue()),
                entry.direction().name(),
                format(entry.correctedValue()),
                entry.displayValue().toPlainString(),
                entry.passed(),
                format(entry.lowerLimit()),
                format(entry.upperLimit()),
                entry.submittedBy(),
                measurementKey);
    }
}
