package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.MeasurementVersionResponse;
import com.example.starter.calibration.api.dto.ReviseMeasurementRequest;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.UncertaintyComponent;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;
import com.example.starter.calibration.repo.UncertaintyBudgetRepository;
import com.example.starter.calibration.repo.UncertaintyComponentRepository;

/**
 * 测量服务：提交（匹配唯一有效证书并固化计算结果）、修订（版本链）、历史明细、版本历史与当前可用结果。
 */
@Service
public class MeasurementService {

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final UncertaintyBudgetRepository budgets;
    private final UncertaintyComponentRepository components;
    private final BusinessClock clock;

    public MeasurementService(MeasurementRepository measurements,
                              CertificateRepository certificates,
                              ReleaseRepository releases,
                              UncertaintyBudgetRepository budgets,
                              UncertaintyComponentRepository components,
                              BusinessClock clock) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.budgets = budgets;
        this.components = components;
        this.clock = clock;
    }

    /**
     * 提交测量（版本 1）。按测量时刻匹配唯一有效证书，无匹配返回 422；
     * 使用 BigDecimal 精确计算 a×读数+b，合格判断基于未舍入值且包含端点。
     * measurementKey 重复返回 409（修订请走修订接口）。
     */
    @Transactional
    public MeasurementResponse submit(SubmitMeasurementRequest request) {
        String key = Inputs.requireText(request.measurementKey(), "measurementKey");
        ParsedMeasurement parsed = parse(
                request.instrumentId(), request.measuredAt(), request.reading(),
                request.lowerLimit(), request.upperLimit(), request.submittedBy());

        if (measurements.existsByKey(key)) {
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY",
                    "测量键已存在，修订请使用修订接口: " + key);
        }

        Measurement measurement = insertVersion(key, 1, parsed, MeasurementStatus.PENDING);
        return toDetail(measurement);
    }

    /**
     * 修订测量：以同一 measurementKey 提交完整后态，生成下一版本。
     * 对当前最新版本行加锁串行化修订/放行；expectedVersion 过期返回 410；
     * 旧的待放行版本置为 SUPERSEDED，已放行版本保留 RELEASED 历史，旧证据不回写。
     * 新版本初始 PENDING 且无不确定度预算（预算版本从 0 开始重新登记）。
     */
    @Transactional
    public MeasurementResponse revise(ReviseMeasurementRequest request) {
        String key = Inputs.requireText(request.measurementKey(), "measurementKey");
        Integer expectedVersion = request.expectedVersion();
        if (expectedVersion == null || expectedVersion <= 0) {
            throw ApiException.badRequest("expectedVersion 必须为正整数");
        }
        ParsedMeasurement parsed = parse(
                request.instrumentId(), request.measuredAt(), request.reading(),
                request.lowerLimit(), request.upperLimit(), request.submittedBy());

        Measurement latest = measurements.findLatestByKeyForUpdate(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在，无法修订: " + key));
        if (latest.version() != expectedVersion) {
            throw ApiException.gone("STALE_MEASUREMENT_VERSION",
                    "测量版本已过期：期望基准版本 " + expectedVersion
                            + "，当前最新版本 " + latest.version());
        }

        int nextVersion = latest.version() + 1;
        Measurement newVersion = insertVersion(key, nextVersion, parsed, MeasurementStatus.PENDING);
        if (latest.status() == MeasurementStatus.PENDING) {
            measurements.markSuperseded(latest.id());
        }
        return toDetail(newVersion);
    }

    /**
     * 历史明细（当前最新版本）：原始测量、未舍入计算值、显示值、当前预算与放行历史；不存在 404。
     */
    @Transactional(readOnly = true)
    public MeasurementResponse detail(String key) {
        Measurement measurement = measurements.findLatestByKey(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        return toDetail(measurement);
    }

    /**
     * 版本历史：按版本号升序返回某业务键全部版本；不存在 404。查询不修改状态。
     */
    @Transactional(readOnly = true)
    public List<MeasurementVersionResponse> versions(String key) {
        if (!measurements.existsByKey(Inputs.requireText(key, "measurementKey"))) {
            throw ApiException.notFound("测量不存在: " + key);
        }
        return measurements.findVersionsByKey(key).stream()
                .map(DtoMapper::toVersionResponse)
                .toList();
    }

    /**
     * 当前可用结果：每个业务键仅最新版本、已放行且证书未撤销。instrumentId 为 null 时返回全部仪器。
     */
    @Transactional(readOnly = true)
    public List<MeasurementResponse> usable(String instrumentId) {
        String instrument = instrumentId == null || instrumentId.isBlank() ? null : instrumentId.trim();
        return measurements.findUsable(instrument).stream()
                .map(this::toDetail)
                .toList();
    }

    private Measurement insertVersion(String key, int version, ParsedMeasurement parsed,
                                      MeasurementStatus status) {
        BigDecimal computed = parsed.certificate().a().multiply(parsed.reading()).add(parsed.certificate().b());
        boolean passed = computed.compareTo(parsed.lowerLimit()) >= 0
                && computed.compareTo(parsed.upperLimit()) <= 0;
        Measurement measurement = new Measurement(
                0L, key, version, parsed.instrumentId(), parsed.measuredAt(), parsed.reading(),
                parsed.lowerLimit(), parsed.upperLimit(), parsed.submittedBy(),
                parsed.certificate().id(), computed, passed, status, clock.now());
        try {
            long id = measurements.insert(measurement);
            return measurements.findById(id).orElseThrow();
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY", "测量键/版本冲突: " + key);
        }
    }

    private ParsedMeasurement parse(String instrumentIdRaw, String measuredAtRaw, String readingRaw,
                                    String lowerRaw, String upperRaw, String submittedByRaw) {
        String instrumentId = Inputs.requireText(instrumentIdRaw, "instrumentId");
        Instant measuredAt = Inputs.requireInstant(measuredAtRaw, "measuredAt");
        BigDecimal reading = Inputs.requireDecimal(readingRaw, "reading");
        BigDecimal lower = Inputs.requireDecimal(lowerRaw, "lowerLimit");
        BigDecimal upper = Inputs.requireDecimal(upperRaw, "upperLimit");
        String submittedBy = Inputs.requireText(submittedByRaw, "submittedBy");
        if (lower.compareTo(upper) > 0) {
            throw ApiException.badRequest("lowerLimit 不能大于 upperLimit");
        }
        Certificate cert = certificates.findMatching(instrumentId, measuredAt)
                .orElseThrow(() -> ApiException.unprocessable(
                        "测量时刻无匹配的有效证书: instrument=" + instrumentId));
        return new ParsedMeasurement(instrumentId, measuredAt, reading, lower, upper, submittedBy, cert);
    }

    private MeasurementResponse toDetail(Measurement measurement) {
        boolean certRevoked = certificates.findById(measurement.certificateId())
                .map(Certificate::revoked)
                .orElse(true);
        int budgetVersion = budgets.maxBudgetVersion(measurement.id());
        List<UncertaintyComponent> budget = budgetVersion == 0
                ? List.of()
                : components.findByMeasurementAndVersion(measurement.id(), budgetVersion);
        return DtoMapper.toResponse(measurement, certRevoked,
                releases.findByMeasurementId(measurement.id()), budget, budgetVersion);
    }

    /** 解析后的测量入参与匹配证书。 */
    private record ParsedMeasurement(
            String instrumentId,
            Instant measuredAt,
            BigDecimal reading,
            BigDecimal lowerLimit,
            BigDecimal upperLimit,
            String submittedBy,
            Certificate certificate) {
    }
}
