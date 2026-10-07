package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.model.Branch;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.CertificateMode;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 测量服务：提交（匹配唯一有效证书并固化计算结果）、历史明细、当前可用结果查询。
 * 线性模式：y = a×读数 + b；二次模式：由信号 y 在证书单调分支内反解输入量，
 * 合格判定用端点函数值精确比较，数值根仅用于展示（夹逼区间宽度不大于 0.000001）。
 */
@Service
public class MeasurementService {

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;

    public MeasurementService(MeasurementRepository measurements,
                              CertificateRepository certificates,
                              ReleaseRepository releases) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
    }

    /**
     * 提交测量。按测量时刻匹配唯一有效证书，无匹配返回 422；
     * 线性模式用 BigDecimal 精确计算 a×读数+b，合格判断基于未舍入值且包含端点。
     * 二次模式：信号不在证书端点函数值之间返回 422；合格区间必须位于证书输入域（否则 400）；
     * 合格判定用 f(lo)、f(hi) 与信号精确比较，不拿近似根放行。
     * measurementKey 重复返回 409（幂等键冲突）。
     */
    @Transactional
    public MeasurementResponse submit(SubmitMeasurementRequest request) {
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

        Measurement measurement = switch (cert.mode()) {
            case LINEAR -> buildLinear(key, instrumentId, measuredAt, reading, lower, upper, submittedBy, cert);
            case QUADRATIC -> buildQuadratic(key, instrumentId, measuredAt, reading, lower, upper, submittedBy, cert);
        };
        try {
            measurements.insert(measurement);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY", "测量键已存在: " + key);
        }
        return toDetail(measurements.findByKey(key).orElseThrow());
    }

    /**
     * 历史明细：包含原始测量、未舍入计算值、显示值与放行历史；不存在返回 404。
     * 历史展示使用提交时冻结的数据，不随撤证重算。
     */
    @Transactional(readOnly = true)
    public MeasurementResponse detail(String key) {
        Measurement measurement = measurements.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        return toDetail(measurement);
    }

    /**
     * 当前可用结果：已放行且证书未撤销。instrumentId 为 null 时返回全部仪器。
     */
    @Transactional(readOnly = true)
    public List<MeasurementResponse> usable(String instrumentId) {
        String instrument = instrumentId == null || instrumentId.isBlank() ? null : instrumentId.trim();
        return measurements.findUsable(instrument).stream()
                .map(this::toDetail)
                .toList();
    }

    private Measurement buildLinear(String key, String instrumentId, Instant measuredAt,
                                    BigDecimal reading, BigDecimal lower, BigDecimal upper,
                                    String submittedBy, Certificate cert) {
        BigDecimal computed = cert.a().multiply(reading).add(cert.b());
        boolean passed = computed.compareTo(lower) >= 0 && computed.compareTo(upper) <= 0;
        return new Measurement(0L, key, instrumentId, measuredAt, CertificateMode.LINEAR,
                reading, lower, upper, submittedBy, cert.id(), computed, passed,
                null, null, null, null, null, MeasurementStatus.PENDING, Instant.now());
    }

    private Measurement buildQuadratic(String key, String instrumentId, Instant measuredAt,
                                       BigDecimal signal, BigDecimal lo, BigDecimal hi,
                                       String submittedBy, Certificate cert) {
        BigDecimal a = cert.a();
        BigDecimal b = cert.b();
        BigDecimal c = cert.c();
        BigDecimal domainLo = cert.domainLo();
        BigDecimal domainHi = cert.domainHi();
        if (lo.compareTo(domainLo) < 0 || hi.compareTo(domainHi) > 0) {
            throw ApiException.badRequest("期望输入合格区间必须位于证书输入闭区间 [L,U] 内");
        }
        Branch branch = Quadratic.requireMonotonic(a, b, domainLo, domainHi);

        // 信号必须位于两个端点函数值之间（含端点），否则分支内无根
        BigDecimal fLo = Quadratic.eval(a, b, c, domainLo);
        BigDecimal fHi = Quadratic.eval(a, b, c, domainHi);
        if (signal.compareTo(fLo.min(fHi)) < 0 || signal.compareTo(fLo.max(fHi)) > 0) {
            throw ApiException.unprocessable("SIGNAL_OUT_OF_RANGE",
                    "信号不在证书两个端点函数值之间: " + signal);
        }

        // 合格判定：根 ∈ [lo,hi] ⟺ 信号位于 f(lo) 与 f(hi) 之间（单调，含端点），精确比较
        BigDecimal evalLo = Quadratic.eval(a, b, c, lo);
        BigDecimal evalHi = Quadratic.eval(a, b, c, hi);
        boolean passed = signal.compareTo(evalLo.min(evalHi)) >= 0
                && signal.compareTo(evalLo.max(evalHi)) <= 0;

        // 数值反解仅用于展示：夹逼区间宽度不大于 0.000001，根显示 HALF_UP 四位
        BigDecimal[] bracket = Quadratic.bracket(a, b, c, signal, domainLo, domainHi, branch);
        BigDecimal rootLower = bracket[0].setScale(12, RoundingMode.HALF_UP);
        BigDecimal rootUpper = bracket[1].setScale(12, RoundingMode.HALF_UP);
        BigDecimal midpoint = bracket[0].add(bracket[1]).divide(BigDecimal.valueOf(2))
                .setScale(12, RoundingMode.HALF_UP);

        return new Measurement(0L, key, instrumentId, measuredAt, CertificateMode.QUADRATIC,
                signal, lo, hi, submittedBy, cert.id(), midpoint, passed,
                branch, rootLower, rootUpper, evalLo, evalHi, MeasurementStatus.PENDING, Instant.now());
    }

    private MeasurementResponse toDetail(Measurement measurement) {
        boolean certRevoked = certificates.findById(measurement.certificateId())
                .map(Certificate::revoked)
                .orElse(true);
        return DtoMapper.toResponse(measurement, certRevoked,
                releases.findByMeasurementId(measurement.id()));
    }
}
