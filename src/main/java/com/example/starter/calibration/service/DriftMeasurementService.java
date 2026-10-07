package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.SubmitDriftMeasurementRequest;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.DriftProfile;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementMode;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.Rational;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.DriftProfileRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 参考漂移模式测量提交：两份未撤销漂移档案夹住测量时刻（不外推），
 * 先用有效线性证书求值，再减线性时间插值偏移；校正值保存为精确有理数。
 */
@Service
public class DriftMeasurementService {

    /** 两档案时刻最大间隔：1 小时。 */
    static final Duration MAX_BRACKET = Duration.ofHours(1);

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final DriftProfileRepository profiles;
    private final ReleaseRepository releases;
    private final IdempotencyExecutor idempotency;

    public DriftMeasurementService(MeasurementRepository measurements,
                                   CertificateRepository certificates,
                                   DriftProfileRepository profiles,
                                   ReleaseRepository releases,
                                   IdempotencyExecutor idempotency) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.profiles = profiles;
        this.releases = releases;
        this.idempotency = idempotency;
    }

    /**
     * 提交漂移模式测量。任一输入超精度 400 且不产生测量；
     * 仪器不符、档案缺失或撤销、区间非法（t0≥t1、间隔超 1 小时、t 越界）、无有效证书均 422。
     * 两档案行锁使撤销与提交按事务提交顺序互斥。
     */
    @Transactional
    public MeasurementResponse submit(SubmitDriftMeasurementRequest request) {
        String operator = Inputs.requireText(request.operator(), "operator");
        String key = Inputs.requireText(request.measurementKey(), "measurementKey");
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        Instant measuredAt = Inputs.requireMillisInstant(request.measuredAt(), "measuredAt");
        BigDecimal reading = Inputs.requireDecimal(request.reading(), "reading");
        BigDecimal lower = Inputs.requireDecimal(request.lowerLimit(), "lowerLimit");
        BigDecimal upper = Inputs.requireDecimal(request.upperLimit(), "upperLimit");
        long t0Id = Inputs.requireId(request.profileT0Id(), "profileT0Id");
        long t1Id = Inputs.requireId(request.profileT1Id(), "profileT1Id");
        if (lower.compareTo(upper) > 0) {
            throw ApiException.badRequest("lowerLimit 不能大于 upperLimit");
        }

        String fingerprint = Fingerprints.of("SUBMIT_DRIFT_MEASUREMENT", key, operator,
                        request.expectedVersion())
                .text("instrumentId", instrumentId)
                .instant("measuredAt", measuredAt)
                .decimal("reading", reading)
                .decimal("lowerLimit", lower)
                .decimal("upperLimit", upper)
                .id("profileT0Id", t0Id)
                .id("profileT1Id", t1Id)
                .build();
        return idempotency.execute(request.requestId(), "SUBMIT_DRIFT_MEASUREMENT", fingerprint,
                MeasurementResponse.class, () -> doSubmit(key, instrumentId, measuredAt, reading,
                        lower, upper, operator, t0Id, t1Id));
    }

    private MeasurementResponse doSubmit(String key, String instrumentId, Instant measuredAt,
                                         BigDecimal reading, BigDecimal lower, BigDecimal upper,
                                         String operator, long t0Id, long t1Id) {
        // 先锁小号 ID 再锁大号 ID，避免与撤销、放行并发时死锁
        long firstId = Math.min(t0Id, t1Id);
        long secondId = Math.max(t0Id, t1Id);
        DriftProfile first = profiles.findByIdForUpdate(firstId)
                .orElseThrow(() -> ApiException.unprocessable("漂移档案不存在: " + firstId));
        DriftProfile second = profiles.findByIdForUpdate(secondId)
                .orElseThrow(() -> ApiException.unprocessable("漂移档案不存在: " + secondId));
        DriftProfile p0 = first.id() == t0Id ? first : second;
        DriftProfile p1 = first.id() == t1Id ? first : second;

        if (p0.revoked() || p1.revoked()) {
            throw ApiException.unprocessable("引用的漂移档案已撤销");
        }
        if (!p0.instrumentId().equals(instrumentId) || !p1.instrumentId().equals(instrumentId)) {
            throw ApiException.unprocessable("漂移档案仪器与测量仪器不符: " + instrumentId);
        }
        Instant t0 = p0.profiledAt();
        Instant t1 = p1.profiledAt();
        if (!t0.isBefore(t1)) {
            throw ApiException.unprocessable("漂移档案时刻必须满足 t0 早于 t1");
        }
        if (Duration.between(t0, t1).compareTo(MAX_BRACKET) > 0) {
            throw ApiException.unprocessable("漂移档案间隔超过 1 小时");
        }
        if (measuredAt.isBefore(t0) || measuredAt.isAfter(t1)) {
            throw ApiException.unprocessable("测量时刻不在 [t0, t1] 区间内");
        }

        Certificate cert = certificates.findMatching(instrumentId, measuredAt)
                .orElseThrow(() -> ApiException.unprocessable(
                        "测量时刻无匹配的有效证书: instrument=" + instrumentId));

        // 先用原有效线性证书求值，再减线性时间插值偏移；全程精确有理数
        Rational certValue = Rational.of(cert.a().multiply(reading).add(cert.b()));
        Rational offset = interpolate(Rational.of(p0.referenceOffset()), Rational.of(p1.referenceOffset()),
                t0.toEpochMilli(), t1.toEpochMilli(), measuredAt.toEpochMilli());
        Rational corrected = certValue.subtract(offset);
        boolean passed = corrected.within(lower, upper);

        Measurement measurement = new Measurement(
                0L, key, instrumentId, measuredAt, reading, lower, upper, operator,
                cert.id(), MeasurementMode.DRIFT, t0Id, t1Id, null,
                corrected.numerator(), corrected.denominator(),
                passed, MeasurementStatus.PENDING, Instant.now());
        try {
            measurements.insert(measurement);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY", "测量键已存在: " + key);
        }
        Measurement saved = measurements.findByKey(key).orElseThrow();
        boolean evidenceRevoked = certificates.findById(saved.certificateId())
                .map(Certificate::revoked).orElse(true);
        return DtoMapper.toResponse(saved, evidenceRevoked,
                releases.findByMeasurementId(saved.id()));
    }

    /**
     * 线性时间插值：offset(t) = o0 + (o1 - o0) × (t - t0) / (t1 - t0)，全程毫秒整数运算。
     */
    static Rational interpolate(Rational o0, Rational o1, long t0, long t1, long t) {
        Rational span = Rational.of(BigInteger.valueOf(t1 - t0));
        Rational elapsed = Rational.of(BigInteger.valueOf(t - t0));
        return o0.add(o1.subtract(o0).multiply(elapsed).divide(span));
    }
}
