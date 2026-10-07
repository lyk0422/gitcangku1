package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.SubmitInverseMeasurementRequest;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.CertificateType;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 测量服务：提交（匹配唯一有效证书并固化计算结果）、历史明细、当前可用结果查询。
 * 二次反解模式：信号 y 在证书单调分支内反解唯一根；合格判定只用端点函数值的精确比较，
 * 不拿近似根放行；提交时冻结函数、分支、信号与精确合格依据。
 */
@Service
public class MeasurementService {

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final IdempotencyService idempotency;

    public MeasurementService(MeasurementRepository measurements,
                              CertificateRepository certificates,
                              ReleaseRepository releases,
                              IdempotencyService idempotency) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.idempotency = idempotency;
    }

    /**
     * 提交线性测量。按测量时刻匹配唯一有效证书，无匹配返回 422；
     * 使用 BigDecimal 精确计算 a×读数+b，合格判断基于未舍入值且包含端点。
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

        BigDecimal computed = cert.a().multiply(reading).add(cert.b());
        boolean passed = computed.compareTo(lower) >= 0 && computed.compareTo(upper) <= 0;

        Measurement measurement = new Measurement(
                0L, key, instrumentId, measuredAt, reading, lower, upper, submittedBy,
                cert.id(), computed, passed, MeasurementStatus.PENDING,
                null, null, null, null, Instant.now());
        try {
            measurements.insert(measurement);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY", "测量键已存在: " + key);
        }
        return toDetail(measurements.findByKey(key).orElseThrow());
    }

    /**
     * 提交二次反解测量（幂等）：信号 y 在证书单调分支 [L, U] 内反解唯一根。
     * 期望输入合格区间 [lo, hi] 必须位于证书输入域；信号不在两端点函数值之间返回 422。
     * 合格判定用端点函数值精确比较，不使用近似根；根以宽度 ≤ 0.000001 的夹逼区间给出。
     */
    public IdemOutcome submitInverse(SubmitInverseMeasurementRequest request) {
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        String submittedBy = Inputs.requireText(request.submittedBy(), "submittedBy");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("measurementKey", request.measurementKey());
        params.put("instrumentId", request.instrumentId());
        params.put("measuredAt", request.measuredAt());
        params.put("signal", request.signal() == null ? null : tryDecimal(request.signal()));
        params.put("lowerLimit", request.lowerLimit() == null ? null : tryDecimal(request.lowerLimit()));
        params.put("upperLimit", request.upperLimit() == null ? null : tryDecimal(request.upperLimit()));
        params.put("submittedBy", request.submittedBy());
        params.put("expectedVersion", request.expectedVersion());
        return idempotency.execute(requestId, "MEASUREMENT_SUBMIT_INVERSE", submittedBy,
                request.measurementKey() == null ? "" : request.measurementKey(), params,
                () -> new IdempotencyService.IdemResponse(201, doSubmitInverse(request)));
    }

    /**
     * 二次反解提交的业务主体（在幂等事务内执行，失败整体回滚不占键）。
     */
    @Transactional
    public MeasurementResponse doSubmitInverse(SubmitInverseMeasurementRequest request) {
        String key = Inputs.requireText(request.measurementKey(), "measurementKey");
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        Instant measuredAt = Inputs.requireInstant(request.measuredAt(), "measuredAt");
        BigDecimal signal = Inputs.requireDecimal(request.signal(), "signal");
        BigDecimal lo = Inputs.requireDecimal(request.lowerLimit(), "lowerLimit");
        BigDecimal hi = Inputs.requireDecimal(request.upperLimit(), "upperLimit");
        String submittedBy = Inputs.requireText(request.submittedBy(), "submittedBy");
        if (lo.compareTo(hi) > 0) {
            throw ApiException.badRequest("lowerLimit 不能大于 upperLimit");
        }

        Certificate cert = certificates.findMatching(instrumentId, measuredAt)
                .orElseThrow(() -> ApiException.unprocessable(
                        "测量时刻无匹配的有效证书: instrument=" + instrumentId));
        if (cert.certType() != CertificateType.QUADRATIC) {
            throw ApiException.badRequest("CERT_TYPE_MISMATCH",
                    "匹配到的证书不是二次证书，无法反解: certificateId=" + cert.id());
        }
        if (lo.compareTo(cert.domainLo()) < 0 || hi.compareTo(cert.domainHi()) > 0) {
            throw ApiException.badRequest("期望输入合格区间 [lowerLimit, upperLimit] 必须位于证书输入域内");
        }

        BigDecimal a = cert.a();
        BigDecimal b = cert.b();
        BigDecimal c = cert.c();
        boolean increasing = Quadratics.increasing(a, b, cert.domainLo());

        // 信号必须位于两个端点函数值之间（含端点），否则分支内无根
        BigDecimal domainLoValue = Quadratics.eval(a, b, c, cert.domainLo());
        BigDecimal domainHiValue = Quadratics.eval(a, b, c, cert.domainHi());
        if (!between(domainLoValue, domainHiValue, signal, increasing)) {
            throw ApiException.unprocessable("SIGNAL_OUT_OF_RANGE",
                    "信号不在证书输入域两端点函数值之间，分支内无根: signal=" + signal);
        }

        // 精确合格依据：根 ∈ [lo, hi] ⟺ 信号位于 f(lo) 与 f(hi) 之间（端点函数值精确比较）
        BigDecimal evalLo = Quadratics.eval(a, b, c, lo);
        BigDecimal evalHi = Quadratics.eval(a, b, c, hi);
        boolean passed = between(evalLo, evalHi, signal, increasing);

        // 近似根仅用于展示与存储：二分夹逼，区间宽度 ≤ 0.000001
        BigDecimal[] bracket = InverseSolver.bracket(a, b, c, cert.domainLo(), cert.domainHi(), signal);
        BigDecimal root = bracket[0].add(bracket[1]).divide(new BigDecimal("2"));

        Measurement measurement = new Measurement(
                0L, key, instrumentId, measuredAt, signal, lo, hi, submittedBy,
                cert.id(), root, passed, MeasurementStatus.PENDING,
                evalLo, evalHi, bracket[0], bracket[1], Instant.now());
        try {
            measurements.insert(measurement);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY", "测量键已存在: " + key);
        }
        return toDetail(measurements.findByKey(key).orElseThrow());
    }

    /**
     * 历史明细：包含原始测量、未舍入计算值、显示值与放行历史；不存在返回 404。
     * 历史展示使用提交时冻结的数值，不随撤证重算。
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

    /** 单调函数下 signal 是否位于端点函数值 fLow/fHigh 之间（含端点）。 */
    private static boolean between(BigDecimal fLow, BigDecimal fHigh, BigDecimal signal,
                                   boolean increasing) {
        BigDecimal min = increasing ? fLow : fHigh;
        BigDecimal max = increasing ? fHigh : fLow;
        return signal.compareTo(min) >= 0 && signal.compareTo(max) <= 0;
    }

    private MeasurementResponse toDetail(Measurement measurement) {
        Certificate cert = certificates.findById(measurement.certificateId()).orElse(null);
        boolean certRevoked = cert == null || cert.revoked();
        String certType = cert == null ? null : cert.certType().name();
        return DtoMapper.toResponse(measurement, certRevoked, certType,
                releases.findByMeasurementId(measurement.id()));
    }

    /** 幂等指纹用：可解析的十进制按值归一，无法解析时按原文本（后续校验会拒绝）。 */
    private static Object tryDecimal(String raw) {
        String trimmed = raw.trim();
        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException ex) {
            return raw;
        }
    }
}
