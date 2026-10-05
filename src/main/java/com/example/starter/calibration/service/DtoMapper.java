package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.util.List;

import com.example.starter.calibration.api.dto.CertificateResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.NodeResponse;
import com.example.starter.calibration.api.dto.ReleaseRecordResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.CertificateNode;
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

    static CertificateResponse toResponse(Certificate cert, List<CertificateNode> nodes) {
        return new CertificateResponse(
                cert.id(),
                cert.instrumentId(),
                cert.certType().name(),
                cert.validFrom(),
                cert.validTo(),
                cert.a() == null ? null : format(cert.a()),
                cert.b() == null ? null : format(cert.b()),
                nodes == null ? null : nodes.stream().map(DtoMapper::toResponse).toList(),
                cert.revoked(),
                cert.revokedAt(),
                cert.createdAt());
    }

    static NodeResponse toResponse(CertificateNode node) {
        return new NodeResponse(format(node.raw()), format(node.corrected()));
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
                m.computed().toExactString(),
                m.displayValue().toPlainString(),
                m.passed(),
                m.status().name(),
                usable,
                m.createdAt(),
                m.nodeLoIndex(),
                m.nodeHiIndex(),
                releases.stream().map(DtoMapper::toResponse).toList());
    }

    static ReleaseRecordResponse toResponse(ReleaseRecord record) {
        return new ReleaseRecordResponse(record.batchId(), record.releasedBy(), record.releasedAt());
    }
}
