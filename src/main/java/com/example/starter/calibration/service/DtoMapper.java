package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.example.starter.calibration.api.dto.CertificateResponse;
import com.example.starter.calibration.api.dto.CompensationProfileResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.MeasurementVersionResponse;
import com.example.starter.calibration.api.dto.RejectRecordResponse;
import com.example.starter.calibration.api.dto.ReleaseRecordResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.CompensationProfile;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.MeasurementVersion;
import com.example.starter.calibration.model.RejectRecord;
import com.example.starter.calibration.model.ReleaseRecord;

/**
 * 领域模型到响应 DTO 的映射。
 */
final class DtoMapper {

    private DtoMapper() {
    }

    /** 十进制输出：去掉多余尾零，零统一输出 "0"；null 原样返回 null。 */
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

    /** 补偿值固定输出六位小数（HALF_UP），null 原样返回 null；题干要求补偿值计算到六位小数。 */
    static String formatCompensated(BigDecimal value) {
        return value == null ? null : value.setScale(6, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    static CertificateResponse toResponse(Certificate cert) {        return new CertificateResponse(
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

    static CompensationProfileResponse toResponse(CompensationProfile p) {
        return new CompensationProfileResponse(
                p.id(),
                p.instrumentModel(),
                p.versionNo(),
                format(p.tempCoeff()),
                format(p.humidityCoeff()),
                format(p.tempMin()),
                format(p.tempMax()),
                format(p.humidityMin()),
                format(p.humidityMax()),
                p.active(),
                p.createdAt());
    }

    /**
     * 测量明细映射。
     *
     * @param profileVersionById 测量引用的补偿系数版本 ID 到型号内版本号的映射
     */
    static MeasurementResponse toResponse(Measurement m, boolean certificateRevoked,
                                          List<ReleaseRecord> releases, List<RejectRecord> rejects,
                                          List<MeasurementVersion> versions,
                                          Map<Long, Integer> profileVersionById) {
        boolean usable = m.status() == MeasurementStatus.RELEASED && !certificateRevoked;
        return new MeasurementResponse(
                m.id(),
                m.measurementKey(),
                m.instrumentId(),
                m.instrumentModel(),
                m.measuredAt(),
                format(m.rawReading()),
                format(m.lowerLimit()),
                format(m.upperLimit()),
                format(m.temperature()),
                format(m.humidity()),
                format(m.uncertainty()),
                m.submittedBy(),
                m.certificateId(),
                format(m.computedValue()),
                m.displayValue().toPlainString(),
                m.passed(),
                m.compensationProfileId(),
                m.compensationProfileId() == null ? null : profileVersionById.get(m.compensationProfileId()),
                formatCompensated(m.compensatedValue()),
                m.compensatedPassed(),
                m.currentVersion(),
                m.status().name(),
                usable,
                m.createdAt(),
                versions.stream().map(v -> toVersionResponse(v, profileVersionById)).toList(),
                releases.stream().map(DtoMapper::toResponse).toList(),
                rejects.stream().map(DtoMapper::toResponse).toList());
    }

    private static MeasurementVersionResponse toVersionResponse(MeasurementVersion v,
                                                                 Map<Long, Integer> profileVersionById) {
        // 重算链严格线性：v1 无父版本，其后每版父版本号即上一版（versionNo - 1）。
        Integer parentVersionNo = v.versionNo() <= 1 ? null : v.versionNo() - 1;
        return new MeasurementVersionResponse(
                v.versionNo(),
                format(v.temperature()),
                format(v.humidity()),
                format(v.uncertainty()),
                v.certificateId(),
                format(v.computedValue()),
                v.compensationProfileId(),
                v.compensationProfileId() == null ? null : profileVersionById.get(v.compensationProfileId()),
                formatCompensated(v.compensatedValue()),
                v.passed(),
                v.compensatedPassed(),
                parentVersionNo,
                v.calcKey(),
                v.createdAt());
    }

    static ReleaseRecordResponse toResponse(ReleaseRecord record) {
        return new ReleaseRecordResponse(record.batchId(), record.releasedBy(), record.releasedAt());
    }

    static RejectRecordResponse toResponse(RejectRecord record) {
        return new RejectRecordResponse(record.rejectedBy(), record.reason(), record.rejectedAt());
    }
}
