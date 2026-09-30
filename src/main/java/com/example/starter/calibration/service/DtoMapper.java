package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.util.List;

import com.example.starter.calibration.api.dto.CertificateResponse;
import com.example.starter.calibration.api.dto.CoefficientVersionResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.ReleaseRecordResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.CoefficientVersion;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.ReleaseRecord;

/**
 * 领域模型到响应 DTO 的映射。
 */
final class DtoMapper {

    private DtoMapper() {
    }

    /** 十进制输出：去掉多余尾零，零统一输出 "0"；null 透传为 null。 */
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
                format(cert.tempMin()),
                format(cert.tempMax()),
                format(cert.humMin()),
                format(cert.humMax()),
                cert.revoked(),
                cert.revokedAt(),
                cert.createdAt());
    }

    static CoefficientVersionResponse toResponse(CoefficientVersion version) {
        return new CoefficientVersionResponse(
                version.id(),
                version.versionTag(),
                format(version.referenceTemperature()),
                format(version.referenceHumidity()),
                format(version.temperatureCoefficient()),
                format(version.humidityCoefficient()),
                version.effectiveFrom(),
                version.effectiveTo(),
                version.createdAt());
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
                format(m.temperature()),
                format(m.humidity()),
                format(m.compensationValue()),
                m.finalValue() == null ? null : m.finalValue().toPlainString(),
                m.coefficientVersionTag(),
                m.passed(),
                m.status().name(),
                usable,
                m.createdAt(),
                releases.stream().map(DtoMapper::toResponse).toList());
    }

    static ReleaseRecordResponse toResponse(ReleaseRecord record) {
        return new ReleaseRecordResponse(record.batchId(), record.releasedBy(), record.releasedAt());
    }
}
