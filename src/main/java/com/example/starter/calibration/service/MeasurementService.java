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
import com.example.starter.calibration.api.dto.ReviseMeasurementRequest;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementRevision;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.UncertaintyComponent;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.MeasurementRevisionRepository;
import com.example.starter.calibration.repo.ReleaseRepository;
import com.example.starter.calibration.repo.UncertaintyComponentRepository;

/**
 * 测量服务：提交（匹配唯一有效证书并固化计算结果）、测量修订、历史明细、当前可用结果查询。
 * 修订采用版本号乐观并发：expectedVersion 过期返回 410；修订证据只增不回写。
 */
@Service
public class MeasurementService {

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final UncertaintyComponentRepository components;
    private final MeasurementRevisionRepository revisions;
    private final IdempotencyService idempotency;

    public MeasurementService(MeasurementRepository measurements,
                              CertificateRepository certificates,
                              ReleaseRepository releases,
                              UncertaintyComponentRepository components,
                              MeasurementRevisionRepository revisions,
                              IdempotencyService idempotency) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.components = components;
        this.revisions = revisions;
        this.idempotency = idempotency;
    }

    /**
     * 提交测量。按测量时刻匹配唯一有效证书，无匹配返回 422；
     * 使用 BigDecimal 精确计算 a×读数+b，合格判断基于未舍入值且包含端点。
     * measurementKey 重复返回 409（幂等键冲突）。
     */
    @Transactional
    public MeasurementResponse submit(SubmitMeasurementRequest request) {
        String key = Inputs.requireText(request.measurementKey(), "measurementKey");
        ParsedFields fields = parseFields(request.instrumentId(), request.measuredAt(),
                request.reading(), request.lowerLimit(), request.upperLimit());
        String submittedBy = Inputs.requireText(request.submittedBy(), "submittedBy");

        Certificate cert = certificates.findMatching(fields.instrumentId(), fields.measuredAt())
                .orElseThrow(() -> ApiException.unprocessable(
                        "测量时刻无匹配的有效证书: instrument=" + fields.instrumentId()));

        BigDecimal computed = cert.a().multiply(fields.reading()).add(cert.b());
        boolean passed = isPassed(computed, fields.lowerLimit(), fields.upperLimit());

        Measurement measurement = new Measurement(
                0L, key, fields.instrumentId(), fields.measuredAt(), fields.reading(),
                fields.lowerLimit(), fields.upperLimit(), submittedBy,
                cert.id(), computed, passed, MeasurementStatus.PENDING, 1L, Instant.now());
        try {
            measurements.insert(measurement);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY", "测量键已存在: " + key);
        }
        return toDetail(measurements.findByKey(key).orElseThrow());
    }

    /**
     * 测量修订。基于 expectedVersion 乐观并发（不匹配 410）；修订后重新匹配测量时刻证书
     * （无匹配 422）、重新固化计算值与合格判定，测量版本号自增，并写入完整修订历史。
     * 修订后当前版本回到待放行，需要按当前预算与阈值重新裁决放行；
     * 旧放行结论作为不可变历史保留（不回写为从未放行）。
     */
    @Transactional
    public MeasurementResponse revise(String measurementKey, ReviseMeasurementRequest request,
                                      String actor, String requestId) {
        String operator = Inputs.requireText(actor, "X-Actor-Id");
        String key = Inputs.requireText(measurementKey, "measurementKey");
        if (request == null || request.expectedVersion() == null) {
            throw ApiException.badRequest("expectedVersion 不能为空");
        }
        long expectedVersion = request.expectedVersion();
        ParsedFields fields = parseFields(null, request.measuredAt(), request.reading(),
                request.lowerLimit(), request.upperLimit());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("measurementKey", key);
        params.put("expectedVersion", expectedVersion);
        params.put("measuredAt", fields.measuredAt().toString());
        params.put("reading", fields.reading().toPlainString());
        params.put("lowerLimit", fields.lowerLimit().toPlainString());
        params.put("upperLimit", fields.upperLimit().toPlainString());
        var gate = idempotency.begin(requestId, "REVISION:" + key, operator, "REVISE_MEASUREMENT", params);

        Measurement current = measurements.findByKeyForUpdate(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        if (current.version() != expectedVersion) {
            throw ApiException.gone("MEASUREMENT_VERSION_STALE",
                    "测量版本已过期：要求 " + expectedVersion + "，当前 " + current.version());
        }

        Certificate cert = certificates.findMatching(current.instrumentId(), fields.measuredAt())
                .orElseThrow(() -> ApiException.unprocessable("NO_MATCHING_CERTIFICATE",
                        "修订后的测量时刻无匹配的有效证书: instrument=" + current.instrumentId()));
        BigDecimal computed = cert.a().multiply(fields.reading()).add(cert.b());
        boolean passed = isPassed(computed, fields.lowerLimit(), fields.upperLimit());

        MeasurementRevision revision = new MeasurementRevision(
                0L, current.id(), current.version(), current.version() + 1,
                current.measuredAt(), fields.measuredAt(),
                current.rawReading(), fields.reading(),
                current.lowerLimit(), fields.lowerLimit(),
                current.upperLimit(), fields.upperLimit(),
                current.certificateId(), cert.id(),
                current.computedValue(), computed,
                current.passed(), passed,
                operator, Instant.now());
        revisions.insert(revision);

        // 修订产生新版本：当前版本回到待放行，需要按新字段与当前预算/阈值重新裁决；
        // 旧放行结论保留在 release_record 中不回写。
        Measurement updated = new Measurement(
                current.id(), current.measurementKey(), current.instrumentId(),
                fields.measuredAt(), fields.reading(), fields.lowerLimit(), fields.upperLimit(),
                current.submittedBy(), cert.id(), computed, passed,
                MeasurementStatus.PENDING, current.version() + 1, current.createdAt());
        measurements.applyRevision(updated);
        MeasurementResponse response = toDetail(measurements.findByKey(key).orElseThrow());
        idempotency.recordSuccess(gate, 200, response);
        return response;
    }

    /**
     * 历史明细：包含原始测量、未舍入计算值、显示值、不确定度预算、修订历史与放行历史；
     * 不存在返回 404。只读。
     */
    @Transactional(readOnly = true)
    public MeasurementResponse detail(String key) {
        Measurement measurement = measurements.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        return toDetail(measurement);
    }

    /**
     * 当前可用结果：已放行且证书未撤销。instrumentId 为 null 时返回全部仪器。只读。
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
        long budgetVersion = components.currentBudgetVersion(measurement.id());
        List<UncertaintyComponent> budget = budgetVersion == 0
                ? List.of()
                : components.findByBudgetVersion(measurement.id(), budgetVersion);
        List<MeasurementRevision> history = revisions.findByMeasurementId(measurement.id());
        return DtoMapper.toResponse(measurement, certRevoked,
                releases.findByMeasurementId(measurement.id()), budget, budgetVersion, history);
    }

    private static boolean isPassed(BigDecimal computed, BigDecimal lower, BigDecimal upper) {
        return computed.compareTo(lower) >= 0 && computed.compareTo(upper) <= 0;
    }

    /**
     * 解析并校验测量字段（提交与修订共用）。instrumentId 为 null 时表示沿用既有值，不解析。
     */
    private ParsedFields parseFields(String instrumentId, String measuredAt, String reading,
                                    String lowerLimit, String upperLimit) {
        String instrument = instrumentId == null ? null
                : Inputs.requireText(instrumentId, "instrumentId");
        Instant at = Inputs.requireInstant(measuredAt, "measuredAt");
        BigDecimal lower = Inputs.requireDecimal(lowerLimit, "lowerLimit");
        BigDecimal upper = Inputs.requireDecimal(upperLimit, "upperLimit");
        if (lower.compareTo(upper) > 0) {
            throw ApiException.badRequest("lowerLimit 不能大于 upperLimit");
        }
        return new ParsedFields(instrument, at,
                Inputs.requireDecimal(reading, "reading"), lower, upper);
    }

    private record ParsedFields(String instrumentId, Instant measuredAt, BigDecimal reading,
                               BigDecimal lowerLimit, BigDecimal upperLimit) {
    }
}
