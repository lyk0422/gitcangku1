package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.SubmitDriftMeasurementRequest;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.DriftArchive;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementMode;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.DriftArchiveRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 测量服务：普通提交（匹配唯一有效证书并固化计算结果）、参考漂移提交
 * （证书值减两端档案线性时间插值偏移，保存精确有理数）、历史明细、当前可用结果查询。
 */
@Service
public class MeasurementService {

    /** 参考漂移两端档案的最大间隔：1 小时。 */
    private static final Duration MAX_BRACKET = Duration.ofHours(1);
    private static final BigInteger TEN6 = BigInteger.valueOf(1_000_000L);

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final DriftArchiveRepository archives;
    private final ReleaseRepository releases;
    private final RequestDedupService dedup;

    public MeasurementService(MeasurementRepository measurements,
                              CertificateRepository certificates,
                              DriftArchiveRepository archives,
                              ReleaseRepository releases,
                              RequestDedupService dedup) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.archives = archives;
        this.releases = releases;
        this.dedup = dedup;
    }

    /**
     * 提交普通测量。按测量时刻匹配唯一有效证书，无匹配返回 422；
     * 使用 BigDecimal 精确计算 a×读数+b，合格判断基于未舍入值且包含端点。
     * measurementKey 重复返回 409（幂等键冲突）。
     */
    @Transactional
    public MeasurementResponse submit(SubmitMeasurementRequest request) {
        return dedup.execute(request.requestId(), "SUBMIT_MEASUREMENT", "measurement",
                request.submittedBy(), request.expectedVersion(),
                List.of(IdentityHash.text(request.measurementKey()),
                        IdentityHash.text(request.instrumentId()),
                        IdentityHash.text(request.measuredAt()),
                        IdentityHash.decimal(request.reading()),
                        IdentityHash.decimal(request.lowerLimit()),
                        IdentityHash.decimal(request.upperLimit()),
                        IdentityHash.text(request.submittedBy())),
                MeasurementResponse.class,
                () -> doSubmit(request));
    }

    private MeasurementResponse doSubmit(SubmitMeasurementRequest request) {
        String key = Inputs.requireText(request.measurementKey(), "measurementKey");
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        Instant measuredAt = Inputs.requireInstant(request.measuredAt(), "measuredAt");
        BigDecimal reading = Inputs.requireDecimal(request.reading(), "reading");
        BigDecimal lower = Inputs.requireDecimal(request.lowerLimit(), "lowerLimit");
        BigDecimal upper = Inputs.requireDecimal(request.upperLimit(), "upperLimit");
        String submittedBy = Inputs.requireText(request.submittedBy(), "submittedBy");
        if (lower.compareTo(upper) > 0) {
            throw ApiException.badRequest("lowerLimit 不能大于 upperLimit");
        }

        Certificate cert = certificates.findMatching(instrumentId, measuredAt)
                .orElseThrow(() -> ApiException.unprocessable(
                        "测量时刻无匹配的有效证书: instrument=" + instrumentId));

        BigDecimal computed = cert.a().multiply(reading).add(cert.b());
        boolean passed = computed.compareTo(lower) >= 0 && computed.compareTo(upper) <= 0;

        Measurement measurement = new Measurement(
                0L, key, MeasurementMode.STANDARD, instrumentId, measuredAt, reading, lower, upper,
                submittedBy, cert.id(), computed, null, null, null, null,
                passed, MeasurementStatus.PENDING, Instant.now());
        insert(measurement, key);
        return toDetail(measurements.findByKey(key).orElseThrow());
    }

    /**
     * 提交参考漂移模式测量。必须显式引用两个当前未撤销档案：t0&lt;t1、间隔不超过 1 小时、
     * 测量时刻 t∈[t0,t1]；校正偏移为线性时间插值。先用证书求值再减插值偏移，
     * 最终值保存为精确有理数，合格判断用未舍入分数。证据无效 422，不补查最近档案代替。
     */
    @Transactional
    public MeasurementResponse submitDrift(SubmitDriftMeasurementRequest request) {
        return dedup.execute(request.requestId(), "SUBMIT_DRIFT_MEASUREMENT", "measurement",
                request.submittedBy(), request.expectedVersion(),
                List.of(IdentityHash.text(request.measurementKey()),
                        IdentityHash.text(request.instrumentId()),
                        IdentityHash.text(request.measuredAt()),
                        IdentityHash.decimal(request.reading()),
                        IdentityHash.decimal(request.lowerLimit()),
                        IdentityHash.decimal(request.upperLimit()),
                        IdentityHash.text(request.submittedBy()),
                        String.valueOf(request.archiveId0()),
                        String.valueOf(request.archiveId1())),
                MeasurementResponse.class,
                () -> doSubmitDrift(request));
    }

    private MeasurementResponse doSubmitDrift(SubmitDriftMeasurementRequest request) {
        String key = Inputs.requireText(request.measurementKey(), "measurementKey");
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        Instant measuredAt = Inputs.requireInstant(request.measuredAt(), "measuredAt");
        Inputs.requireMillisPrecision(measuredAt, "measuredAt");
        BigDecimal reading = Inputs.requireDecimal(request.reading(), "reading");
        BigDecimal lower = Inputs.requireDecimal(request.lowerLimit(), "lowerLimit");
        BigDecimal upper = Inputs.requireDecimal(request.upperLimit(), "upperLimit");
        String submittedBy = Inputs.requireText(request.submittedBy(), "submittedBy");
        if (lower.compareTo(upper) > 0) {
            throw ApiException.badRequest("lowerLimit 不能大于 upperLimit");
        }
        if (request.archiveId0() == null || request.archiveId1() == null) {
            throw ApiException.badRequest("archiveId0/archiveId1 不能为空");
        }

        DriftArchive a0 = archives.findById(request.archiveId0())
                .orElseThrow(() -> driftInvalid("下端漂移档案不存在: " + request.archiveId0()));
        DriftArchive a1 = archives.findById(request.archiveId1())
                .orElseThrow(() -> driftInvalid("上端漂移档案不存在: " + request.archiveId1()));
        if (a0.revoked() || a1.revoked()) {
            throw driftInvalid("参考漂移档案已撤销");
        }
        if (!a0.instrumentId().equals(instrumentId) || !a1.instrumentId().equals(instrumentId)) {
            throw driftInvalid("参考漂移档案与仪器不符: " + instrumentId);
        }
        Instant t0 = a0.referencedAt();
        Instant t1 = a1.referencedAt();
        if (!t0.isBefore(t1)) {
            throw driftInvalid("下端档案时刻 t0 必须小于上端档案时刻 t1");
        }
        if (Duration.between(t0, t1).compareTo(MAX_BRACKET) > 0) {
            throw driftInvalid("两端档案间隔超过 1 小时");
        }
        if (measuredAt.isBefore(t0) || measuredAt.isAfter(t1)) {
            throw driftInvalid("测量时刻不在 [t0,t1] 区间内");
        }

        Certificate cert = certificates.findMatching(instrumentId, measuredAt)
                .orElseThrow(() -> ApiException.unprocessable(
                        "测量时刻无匹配的有效证书: instrument=" + instrumentId));

        BigDecimal certValue = cert.a().multiply(reading).add(cert.b());
        BigInteger[] fraction = correctedFraction(certValue, a0, a1, t0, t1, measuredAt);
        BigInteger num = fraction[0];
        BigInteger den = fraction[1];
        BigDecimal numBd = new BigDecimal(num);
        BigDecimal denBd = new BigDecimal(den);
        boolean passed = numBd.compareTo(lower.multiply(denBd)) >= 0
                && numBd.compareTo(upper.multiply(denBd)) <= 0;

        Measurement measurement = new Measurement(
                0L, key, MeasurementMode.DRIFT_REF, instrumentId, measuredAt, reading, lower, upper,
                submittedBy, cert.id(), certValue, a0.id(), a1.id(), numBd, denBd,
                passed, MeasurementStatus.PENDING, Instant.now());
        insert(measurement, key);
        return toDetail(measurements.findByKey(key).orElseThrow());
    }

    /**
     * 校正值精确有理数：证书值 − (o0×(t1−t) + o1×(t−t0)) / (t1−t0)，按毫秒整数运算后约分。
     * 返回 [分子, 分母]，分母恒为正。
     */
    private static BigInteger[] correctedFraction(BigDecimal certValue, DriftArchive a0, DriftArchive a1,
                                                  Instant t0, Instant t1, Instant t) {
        BigInteger t0Ms = BigInteger.valueOf(t0.toEpochMilli());
        BigInteger t1Ms = BigInteger.valueOf(t1.toEpochMilli());
        BigInteger tMs = BigInteger.valueOf(t.toEpochMilli());
        BigInteger span = t1Ms.subtract(t0Ms);
        BigInteger o0 = a0.offset().movePointRight(6).toBigIntegerExact();
        BigInteger o1 = a1.offset().movePointRight(6).toBigIntegerExact();
        // 插值偏移 = (o0×(t1−t) + o1×(t−t0)) / (10^6×span)
        BigInteger offNum = o0.multiply(t1Ms.subtract(tMs)).add(o1.multiply(tMs.subtract(t0Ms)));
        BigInteger offDen = TEN6.multiply(span);
        // 校正值 = 证书值 − 插值偏移，通分后约分
        BigInteger cvNum = certValue.unscaledValue();
        BigInteger cvDen = BigInteger.TEN.pow(certValue.scale());
        BigInteger num = cvNum.multiply(offDen).subtract(offNum.multiply(cvDen));
        BigInteger den = cvDen.multiply(offDen);
        BigInteger gcd = num.gcd(den);
        return new BigInteger[]{num.divide(gcd), den.divide(gcd)};
    }

    private static ApiException driftInvalid(String message) {
        return ApiException.unprocessable("DRIFT_REFERENCE_INVALID", message);
    }

    private void insert(Measurement measurement, String key) {
        try {
            measurements.insert(measurement);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY", "测量键已存在: " + key);
        }
    }

    /**
     * 历史明细：包含原始测量、未舍入计算值、显示值与放行历史；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public MeasurementResponse detail(String key) {
        Measurement measurement = measurements.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        return toDetail(measurement);
    }

    /**
     * 当前可用结果：已放行且证书与相关档案均未撤销。instrumentId 为 null 时返回全部仪器。
     */
    @Transactional(readOnly = true)
    public List<MeasurementResponse> usable(String instrumentId) {
        String instrument = instrumentId == null || instrumentId.isBlank() ? null : instrumentId.trim();
        return measurements.findUsable(instrument).stream()
                .map(this::toDetail)
                .toList();
    }

    private MeasurementResponse toDetail(Measurement measurement) {
        boolean certRevoked = certificates.findById(measurement.certificateId())
                .map(Certificate::revoked)
                .orElse(true);
        boolean refsRevoked = false;
        if (measurement.mode() == MeasurementMode.DRIFT_REF) {
            boolean a0Revoked = archives.findById(measurement.refArchiveId0())
                    .map(DriftArchive::revoked).orElse(true);
            boolean a1Revoked = archives.findById(measurement.refArchiveId1())
                    .map(DriftArchive::revoked).orElse(true);
            refsRevoked = a0Revoked || a1Revoked;
        }
        return DtoMapper.toResponse(measurement, certRevoked, refsRevoked,
                releases.findByMeasurementId(measurement.id()));
    }
}
