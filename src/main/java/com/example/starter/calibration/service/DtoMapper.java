package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.util.List;

import com.example.starter.calibration.api.dto.CertificateResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.ReleaseRecordResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.CertificateType;
import com.example.starter.calibration.model.CurveNode;
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

    static CertificateResponse toResponse(Certificate cert, List<CurveNode> nodes) {
        List<CertificateResponse.NodeResponse> nodeResponses = null;
        if (cert.certType() == CertificateType.CURVE) {
            nodeResponses = nodes.stream()
                    .map(n -> new CertificateResponse.NodeResponse(format(n.raw()), format(n.corrected())))
                    .toList();
        }
        return new CertificateResponse(
                cert.id(),
                cert.instrumentId(),
                cert.validFrom(),
                cert.validTo(),
                cert.certType().name(),
                cert.a() == null ? null : format(cert.a()),
                cert.b() == null ? null : format(cert.b()),
                nodeResponses,
                cert.revoked(),
                cert.revokedAt(),
                cert.createdAt());
    }

    static MeasurementResponse toResponse(Measurement m, boolean certificateRevoked,
                                          List<ReleaseRecord> releases) {
        boolean usable = m.status() == MeasurementStatus.RELEASED && !certificateRevoked;
        String computed;
        MeasurementResponse.FrozenInterval interval = null;
        if (m.certType() == CertificateType.CURVE) {
            computed = m.computedNum() + "/" + m.computedDen();
            interval = new MeasurementResponse.FrozenInterval(
                    format(m.nodeLoRaw()), format(m.nodeLoCorrected()),
                    format(m.nodeHiRaw()), format(m.nodeHiCorrected()));
        } else {
            computed = format(m.computedValue());
        }
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
                m.certType().name(),
                computed,
                m.displayValue().toPlainString(),
                interval,
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
