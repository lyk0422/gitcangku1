package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.util.List;

import com.example.starter.calibration.api.dto.CertificateResponse;
import com.example.starter.calibration.api.dto.CoefficientResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.MeasurementVersionSummary;
import com.example.starter.calibration.api.dto.ReleaseRecordResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.CompensationCoefficient;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.ReleaseRecord;

/**
 * 领域模型到响应 DTO 的映射。
 */
final class DtoMapper {

    private DtoMapper() {
    }

    /** 十进制输出：去掉多余尾零，零统一输出 "0"；null 输出 null。 */
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
                format(cert.a()),
                format(cert.b()),
                cert.revoked(),
                cert.revokedAt(),
                cert.createdAt());
    }

    static CoefficientResponse toResponse(CompensationCoefficient c) {
        return new CoefficientResponse(
                c.id(),
                c.instrumentModel(),
                c.versionNo(),
                format(c.k0()),
                format(c.kTemperature()),
                format(c.kHumidity()),
                format(c.tempMin()),
                format(c.tempMax()),
                format(c.humidityMin()),
                format(c.humidityMax()),
                c.createdAt());
    }

    static MeasurementVersionSummary toVersionSummary(Measurement m, Integer coefficientVersion) {
        return new MeasurementVersionSummary(
                m.versionNo(),
                m.id(),
                m.hasEnvironment() ? format(m.env().temperatureC()) : null,
                m.hasEnvironment() ? format(m.env().humidityPct()) : null,
                m.coefficientId(),
                coefficientVersion,
                format(m.rawReading()),
                format(m.computedValue()),
                format(m.compensationValue()),
                format(m.compensatedValue()),
                format(m.uncertainty()),
                m.status().name(),
                m.passedAfterComp(),
                m.createdAt());
    }

    static MeasurementResponse toResponse(Measurement m, boolean certificateRevoked, Integer coefficientVersion,
                                          List<MeasurementVersionSummary> chain,
                                          List<ReleaseRecord> releases) {
        boolean usable = m.status() == MeasurementStatus.RELEASED && !certificateRevoked;
        return new MeasurementResponse(
                m.id(),
                m.rootId(),
                m.versionNo(),
                m.measurementKey(),
                m.instrumentId(),
                m.instrumentModel(),
                m.measuredAt(),
                format(m.rawReading()),
                format(m.lowerLimit()),
                format(m.upperLimit()),
                m.hasEnvironment() ? format(m.env().temperatureC()) : null,
                m.hasEnvironment() ? format(m.env().humidityPct()) : null,
                m.coefficientId(),
                coefficientVersion,
                m.certificateId(),
                format(m.computedValue()),
                m.displayValue().toPlainString(),
                format(m.compensationValue()),
                format(m.compensatedValue()),
                format(m.uncertainty()),
                m.passed(),
                m.passedAfterComp(),
                m.status().name(),
                usable,
                m.rejectedBy(),
                m.rejectedAt(),
                m.rejectReason(),
                m.createdAt(),
                chain,
                releases.stream().map(DtoMapper::toResponse).toList());
    }

    static ReleaseRecordResponse toResponse(ReleaseRecord record) {
        return new ReleaseRecordResponse(record.batchId(), record.releasedBy(), record.releasedAt());
    }
}
