package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.util.List;

import com.example.starter.calibration.api.dto.BudgetResponse;
import com.example.starter.calibration.api.dto.CertificateResponse;
import com.example.starter.calibration.api.dto.ComponentResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.ReleaseRecordResponse;
import com.example.starter.calibration.api.dto.ThresholdResponse;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.ReleaseRecord;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.UncertaintyBudget;
import com.example.starter.calibration.model.UncertaintyComponent;
import com.example.starter.calibration.model.UncertaintyThreshold;

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

    /** 不确定度公开口径输出：HALF_UP 四位小数字符串。 */
    static String format4(BigDecimal value) {
        return UncertaintyCalculator.toDisplay(value).toPlainString();
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
                                          List<ReleaseRecord> releases, UncertaintyBudget currentBudget) {
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
                releases.stream().map(DtoMapper::toResponse).toList(),
                m.version(),
                currentBudget == null ? null : toBudgetResponse(m.measurementKey(), currentBudget));
    }

    static ThresholdResponse toResponse(UncertaintyThreshold threshold) {
        return new ThresholdResponse(
                threshold.id(),
                threshold.instrumentId(),
                threshold.version(),
                format(threshold.maxUncertainty()),
                threshold.createdBy(),
                threshold.createdAt());
    }

    static BudgetResponse toBudgetResponse(String measurementKey, UncertaintyBudget budget) {
        BigDecimal actual = budget.combinedUncertainty();
        BigDecimal required = budget.thresholdValue();
        boolean within = UncertaintyCalculator.withinThreshold(actual, required);
        return new BudgetResponse(
                budget.id(),
                measurementKey,
                budget.budgetVersion(),
                budget.method().name(),
                format4(actual),
                budget.thresholdId(),
                budget.thresholdVersion(),
                format(required),
                within,
                format4(actual),
                format(required),
                format(UncertaintyCalculator.margin(actual, required)),
                budget.createdBy(),
                budget.createdAt(),
                budget.components().stream().map(DtoMapper::toResponse).toList());
    }

    static ComponentResponse toResponse(UncertaintyComponent component) {
        return new ComponentResponse(
                component.name(),
                format(component.value()),
                component.ordinal(),
                component.createdAt());
    }

    static ReleaseRecordResponse toResponse(ReleaseRecord record) {
        return new ReleaseRecordResponse(
                record.batchId(),
                record.releasedBy(),
                record.releasedAt(),
                record.budgetId(),
                record.method() == null ? null : record.method().name(),
                record.combinedUncertainty() == null ? null : format4(record.combinedUncertainty()),
                record.thresholdId(),
                record.thresholdVersion(),
                record.thresholdValue() == null ? null : format(record.thresholdValue()));
    }
}
