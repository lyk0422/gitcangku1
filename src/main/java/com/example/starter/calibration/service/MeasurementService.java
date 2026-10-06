package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.ResolutionConfig;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;
import com.example.starter.calibration.repo.ResolutionConfigRepository;

/**
 * 测量服务：提交（匹配唯一有效证书并固化计算结果）、历史明细、当前可用结果查询。
 * 已配置分辨率的仪器：先算精确值 x，再按格点量化（半格取偶）得量化值，
 * 精确值与量化值均落在原合格闭区间才合格；未配置仪器沿用原规则（仅看精确值）。
 */
@Service
public class MeasurementService {

    /** 精确值可固化的最大十进制精度（computed_value 为 DECIMAL(38,12)）。 */
    private static final int MAX_COMPUTED_PRECISION = 38;

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final ResolutionConfigRepository resolutions;

    public MeasurementService(MeasurementRepository measurements,
                              CertificateRepository certificates,
                              ReleaseRepository releases,
                              ResolutionConfigRepository resolutions) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.resolutions = resolutions;
    }

    /**
     * 提交测量。按测量时刻匹配唯一有效证书，无匹配返回 422；
     * 使用 BigDecimal 精确计算 a×读数+b；已配置分辨率的仪器冻结配置版本、k 与量化值，
     * 合格判定要求精确值与量化值均在闭区间（含端点）；未配置仪器仅按精确值判定。
     * 精确值超精度（越界）时不通过：保存待审测量（值置空），放行时整批 422。
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
        Integer resolutionVersion = null;
        BigDecimal gridK = null;
        BigDecimal quantized = null;
        boolean passed;
        if (computed.precision() > MAX_COMPUTED_PRECISION) {
            // 越界（超精度）：无法固化精确值，保存待审测量，判定不通过
            computed = null;
            passed = false;
        } else {
            ResolutionConfig config = resolutions.findByInstrument(instrumentId).orElse(null);
            passed = inInterval(computed, lower, upper);
            if (config != null) {
                GridQuantizer.Result result = GridQuantizer.quantize(computed, config.origin(), config.step());
                resolutionVersion = config.version();
                gridK = new BigDecimal(result.k());
                quantized = result.quantized();
                passed = passed && inInterval(quantized, lower, upper);
            }
        }

        Measurement measurement = new Measurement(
                0L, key, instrumentId, measuredAt, reading, lower, upper, submittedBy,
                cert.id(), computed, resolutionVersion, gridK, quantized,
                passed, MeasurementStatus.PENDING, Instant.now());
        try {
            measurements.insert(measurement);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY", "测量键已存在: " + key);
        }
        return toDetail(measurements.findByKey(key).orElseThrow());
    }

    /**
     * 历史明细：包含原始测量、未舍入精确值、冻结量化结果、显示值与放行历史；不存在返回 404。
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

    /** 闭区间 [lower, upper] 包含判断（含端点）。 */
    private static boolean inInterval(BigDecimal value, BigDecimal lower, BigDecimal upper) {
        return value.compareTo(lower) >= 0 && value.compareTo(upper) <= 0;
    }

    private MeasurementResponse toDetail(Measurement measurement) {
        boolean certRevoked = certificates.findById(measurement.certificateId())
                .map(Certificate::revoked)
                .orElse(true);
        return DtoMapper.toResponse(measurement, certRevoked,
                releases.findByMeasurementId(measurement.id()));
    }
}
