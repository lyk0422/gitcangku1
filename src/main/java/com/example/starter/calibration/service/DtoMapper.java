package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.util.List;

import com.example.starter.calibration.api.dto.BudgetResponse;
import com.example.starter.calibration.api.dto.CertificateResponse;
import com.example.starter.calibration.api.dto.ComponentResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.MeasurementVersionResponse;
import com.example.starter.calibration.api.dto.ReleaseRecordResponse;
import com.example.starter.calibration.api.dto.ReleaseSnapshotResponse;
import com.example.starter.calibration.api.dto.ThresholdResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.ReleaseRecord;
import com.example.starter.calibration.model.ReleaseSnapshot;
import com.example.starter.calibration.model.UncertaintyBudget;
import com.example.starter.calibration.model.UncertaintyComponent;
import com.example.starter.calibration.model.UncertaintyThreshold;

/**
 * 领域模型到响应 DTO 的映射。
 */
public final class DtoMapper {

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

    static ComponentResponse toResponse(UncertaintyComponent component) {
        return new ComponentResponse(component.name(), format(component.magnitude()));
    }

    static MeasurementResponse toResponse(Measurement m, boolean certificateRevoked,
                                          List<ReleaseRecord> releases,
                                          List<UncertaintyComponent> budget,
                                          int budgetVersion) {
        boolean usable = m.status() == MeasurementStatus.RELEASED && !certificateRevoked;
        BigDecimal combined = UncertaintyCalculator.combineComponents(budget);
        return new MeasurementResponse(
                m.id(),
                m.measurementKey(),
                m.version(),
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
                budgetVersion,
                combined.toPlainString(),
                UncertaintyCalculator.FORMULA,
                budget.stream().map(DtoMapper::toResponse).toList());
    }

    static ReleaseRecordResponse toResponse(ReleaseRecord record) {
        return new ReleaseRecordResponse(record.batchId(), record.releasedBy(), record.releasedAt());
    }

    static BudgetResponse toBudgetResponse(Measurement measurement, int budgetVersion,
                                           UncertaintyBudget header,
                                           List<UncertaintyComponent> components) {
        BigDecimal combined = UncertaintyCalculator.combineComponents(components);
        return new BudgetResponse(
                measurement.measurementKey(),
                measurement.version(),
                budgetVersion,
                header == null ? null : header.updatedBy(),
                combined.toPlainString(),
                UncertaintyCalculator.FORMULA,
                components.stream().map(DtoMapper::toResponse).toList(),
                header == null ? null : header.createdAt());
    }

    static ThresholdResponse toResponse(UncertaintyThreshold threshold) {
        return new ThresholdResponse(
                threshold.id(),
                threshold.instrumentId(),
                threshold.version(),
                format(threshold.thresholdValue()),
                threshold.validFrom(),
                threshold.revoked(),
                threshold.revokedAt(),
                threshold.createdAt());
    }

    /** 放行快照到响应 DTO（诊断/历史接口共用）。 */
    public static ReleaseSnapshotResponse toResponse(ReleaseSnapshot snapshot) {
        // 合成不确定度对外恒为 RSS 四位小数（DECIMAL(38,6) 读回后重新归一到 4 位，语义不变）
        String combined = snapshot.combinedUncertainty()
                .setScale(UncertaintyCalculator.SCALE, java.math.RoundingMode.HALF_UP).toPlainString();
        return new ReleaseSnapshotResponse(
                snapshot.batchId(),
                snapshot.measurementId(),
                snapshot.measurementKey(),
                snapshot.measurementVersion(),
                format(snapshot.computedValue()),
                snapshot.passedBias(),
                snapshot.budgetVersion(),
                combined,
                snapshot.formula(),
                snapshot.thresholdId(),
                snapshot.thresholdVersion(),
                format(snapshot.thresholdValue()),
                snapshot.uncertaintyOk(),
                snapshot.decision(),
                snapshot.releasedBy(),
                snapshot.releasedAt(),
                snapshot.components().stream().map(DtoMapper::toResponse).toList());
    }

    static MeasurementVersionResponse toVersionResponse(Measurement measurement) {
        return new MeasurementVersionResponse(
                measurement.id(),
                measurement.version(),
                measurement.status().name(),
                measurement.measuredAt(),
                format(measurement.computedValue()),
                measurement.passed(),
                measurement.createdAt());
    }
}
