package com.example.starter.calibration.service;

import java.util.List;

import com.example.starter.calibration.api.dto.BudgetResponse;
import com.example.starter.calibration.api.dto.CertificateResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse.BudgetView;
import com.example.starter.calibration.api.dto.ReleaseRecordResponse;
import com.example.starter.calibration.api.dto.RevisionResponse;
import com.example.starter.calibration.api.dto.ThresholdResponse;
import com.example.starter.calibration.api.dto.UncertaintyComponentResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementRevision;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.ReleaseRecord;
import com.example.starter.calibration.model.ThresholdVersion;
import com.example.starter.calibration.model.UncertaintyComponent;

/**
 * 领域模型到响应 DTO 的映射。
 */
final class DtoMapper {

    private DtoMapper() {
    }

    /** 十进制输出：去掉多余尾零，零统一输出 "0"。 */
    static String format(java.math.BigDecimal value) {
        java.math.BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.compareTo(java.math.BigDecimal.ZERO) == 0) {
            return "0";
        }
        return stripped.toPlainString();
    }

    /** 固定 4 位小数输出（合成不确定度），如 0.0000、0.5000。 */
    static String format4(java.math.BigDecimal value) {
        return value.setScale(UncertaintyMath.SCALE, java.math.RoundingMode.HALF_UP).toPlainString();
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
                                          List<ReleaseRecord> releases,
                                          List<UncertaintyComponent> currentComponents,
                                          long budgetVersion,
                                          List<MeasurementRevision> revisions) {
        boolean usable = m.status() == MeasurementStatus.RELEASED && !certificateRevoked;
        BudgetView budget = new BudgetView(
                budgetVersion,
                currentComponents.stream()
                        .map(c -> new UncertaintyComponentResponse(c.name(), format(c.magnitude())))
                        .toList(),
                format4(UncertaintyMath.combined(currentComponents)),
                ReleaseRecord.METHOD_RSS_HALF_UP_4);
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
                m.version(),
                budget,
                revisions.stream().map(DtoMapper::toResponse).toList(),
                m.createdAt(),
                releases.stream().map(DtoMapper::toResponse).toList());
    }

    static ReleaseRecordResponse toResponse(ReleaseRecord record) {
        return new ReleaseRecordResponse(
                record.batchId(),
                record.releasedBy(),
                record.releasedAt(),
                record.budgetVersion(),
                format4(record.combinedUncertainty()),
                record.thresholdVersionId(),
                format(record.thresholdValue()),
                record.calculationMethod());
    }

    static RevisionResponse toResponse(MeasurementRevision r) {
        return new RevisionResponse(
                r.id(),
                r.fromVersion(),
                r.toVersion(),
                r.oldMeasuredAt(),
                r.newMeasuredAt(),
                format(r.oldRawReading()),
                format(r.newRawReading()),
                format(r.oldLowerLimit()),
                format(r.newLowerLimit()),
                format(r.oldUpperLimit()),
                format(r.newUpperLimit()),
                r.oldCertificateId(),
                r.newCertificateId(),
                format(r.oldComputedValue()),
                format(r.newComputedValue()),
                r.oldPassed(),
                r.newPassed(),
                r.revisedBy(),
                r.revisedAt());
    }

    static ThresholdResponse toResponse(ThresholdVersion t) {
        return new ThresholdResponse(
                t.id(),
                t.instrumentId(),
                t.version(),
                t.validFrom(),
                t.validTo(),
                format(t.maxUncertainty()),
                t.createdAt());
    }

    static BudgetResponse toBudgetResponse(Measurement m, List<UncertaintyComponent> components,
                                           long budgetVersion, java.time.Instant updatedAt) {
        return new BudgetResponse(
                m.measurementKey(),
                budgetVersion,
                components.stream()
                        .map(c -> new UncertaintyComponentResponse(c.name(), format(c.magnitude())))
                        .toList(),
                format4(UncertaintyMath.combined(components)),
                ReleaseRecord.METHOD_RSS_HALF_UP_4,
                m.version(),
                updatedAt);
    }
}
