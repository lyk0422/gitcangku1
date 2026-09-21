package com.example.starter.calibration.service;

import com.example.starter.calibration.domain.Certificate;
import com.example.starter.calibration.domain.Measurement;
import com.example.starter.calibration.domain.MeasurementStatus;
import com.example.starter.calibration.domain.ReleaseRecord;
import com.example.starter.calibration.error.ApiException;
import com.example.starter.calibration.repository.CertificateRepository;
import com.example.starter.calibration.repository.MeasurementRepository;
import com.example.starter.calibration.repository.ReleaseRecordRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 测量服务：提交（匹配证书、精确计算与判定）、历史明细、当前可用结果查询。
 */
@Service
public class MeasurementService {

    private final MeasurementRepository measurementRepository;
    private final CertificateRepository certificateRepository;
    private final ReleaseRecordRepository releaseRecordRepository;
    private final Clock clock;

    public MeasurementService(
            MeasurementRepository measurementRepository,
            CertificateRepository certificateRepository,
            ReleaseRecordRepository releaseRecordRepository,
            Clock clock) {
        this.measurementRepository = measurementRepository;
        this.certificateRepository = certificateRepository;
        this.releaseRecordRepository = releaseRecordRepository;
        this.clock = clock;
    }

    /**
     * 提交测量。按测量时刻匹配唯一未撤销证书，无匹配返回 422；
     * measurementKey 重复返回 409；下限大于上限返回 400。
     */
    @Transactional
    public Measurement submit(
            String measurementKey,
            String instrumentId,
            Instant measuredAt,
            BigDecimal rawReading,
            BigDecimal lowerLimit,
            BigDecimal upperLimit,
            String submittedBy) {
        InputValidation.requireNonBlank("measurementKey", measurementKey);
        InputValidation.requireNonBlank("instrumentId", instrumentId);
        InputValidation.requireNonBlank("submittedBy", submittedBy);
        if (lowerLimit.compareTo(upperLimit) > 0) {
            throw ApiException.badRequest("INVALID_LIMITS", "lowerLimit 不能大于 upperLimit");
        }
        Certificate certificate = certificateRepository.findActiveCovering(instrumentId, measuredAt)
                .orElseThrow(() -> ApiException.unprocessable(
                        "NO_ACTIVE_CERTIFICATE",
                        "测量时刻 " + measuredAt + " 无有效证书覆盖仪器 " + instrumentId));

        BigDecimal computedValue = computeValue(certificate, rawReading);
        BigDecimal displayValue = computedValue.setScale(4, RoundingMode.HALF_UP);
        boolean passed = computedValue.compareTo(lowerLimit) >= 0
                && computedValue.compareTo(upperLimit) <= 0;

        Measurement measurement = new Measurement(
                0L,
                measurementKey,
                instrumentId,
                measuredAt,
                rawReading,
                lowerLimit,
                upperLimit,
                submittedBy,
                certificate.id(),
                computedValue,
                displayValue,
                passed,
                MeasurementStatus.PENDING_RELEASE,
                null,
                null,
                Instant.now(clock));
        return measurementRepository.insert(measurement);
    }

    /**
     * 精确计算 a × 读数 + b（不舍入）。
     */
    public static BigDecimal computeValue(Certificate certificate, BigDecimal rawReading) {
        return certificate.coefficientA().multiply(rawReading).add(certificate.offsetB());
    }

    /**
     * 查询测量明细及其放行历史（放行历史在证书撤销后仍保留）。不存在返回 404。
     */
    @Transactional(readOnly = true)
    public MeasurementHistory getHistory(long id) {
        Measurement measurement = measurementRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("MEASUREMENT_NOT_FOUND", "测量不存在: " + id));
        Optional<ReleaseRecord> release = releaseRecordRepository.findByMeasurementId(id);
        boolean certificateRevoked = certificateRepository.findById(measurement.certificateId())
                .map(Certificate::revoked)
                .orElse(true);
        return new MeasurementHistory(measurement, release.orElse(null), certificateRevoked);
    }

    /**
     * 查询当前可用结果：已放行且证书未被撤销。instrumentId 为 null 时不过滤。
     */
    @Transactional(readOnly = true)
    public List<Measurement> findCurrentUsable(String instrumentId) {
        return measurementRepository.findCurrentUsable(instrumentId);
    }

    /**
     * 测量明细视图：测量本体、放行记录（可无）、证书是否已撤销。
     *
     * @param measurement       测量记录
     * @param release           放行历史，未放行为 null
     * @param certificateRevoked 关联证书当前是否已撤销
     */
    public record MeasurementHistory(Measurement measurement, ReleaseRecord release, boolean certificateRevoked) {
    }
}
