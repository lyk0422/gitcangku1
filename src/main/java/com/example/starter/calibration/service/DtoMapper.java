package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.util.List;

import com.example.starter.calibration.api.dto.CertificateResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.ReleaseRecordResponse;
import com.example.starter.calibration.model.Certificate;
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

    /**
     * @param m                  测量版本
     * @param latest             该版本是否为当前最新版本
     * @param certificateRevoked 该版本固化的证书是否已撤销
     * @param releases           该版本的放行历史
     */
    static MeasurementResponse toResponse(Measurement m, boolean latest,
                                          boolean certificateRevoked,
                                          List<ReleaseRecord> releases) {
        boolean usable = latest && m.status() == MeasurementStatus.RELEASED && !certificateRevoked;
        return new MeasurementResponse(
                m.id(),
                m.measurementKey(),
                m.revision(),
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
                latest,
                m.revisionReason(),
                m.revisedBy(),
                m.revisedAt(),
                m.createdAt(),
                releases.stream().map(DtoMapper::toResponse).toList());
    }

    static ReleaseRecordResponse toResponse(ReleaseRecord record) {
        return new ReleaseRecordResponse(record.batchId(), record.releasedBy(), record.releasedAt());
    }
}
