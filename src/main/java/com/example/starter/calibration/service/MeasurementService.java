package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.BatchRejectedException;
import com.example.starter.calibration.api.ItemFailure;
import com.example.starter.calibration.api.dto.BatchMeasurementItem;
import com.example.starter.calibration.api.dto.BatchSubmitRequest;
import com.example.starter.calibration.api.dto.BatchSubmitResponse;
import com.example.starter.calibration.api.dto.LineageResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.MeasurementVersionRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 测量服务：提交（匹配唯一有效证书并固化计算结果）、批量提交（先按最终引用预校验）、
 * 同键重放幂等、历史明细、当前可用结果与测量血缘查询。
 */
@Service
public class MeasurementService {

    /** 单批最大条数。 */
    static final int MAX_BATCH_SIZE = 50;

    /**
     * 单条提交结果。
     *
     * @param body    测量明细
     * @param replayed 是否为同键重放（true 时接口返回 200 而非 201）
     */
    public record SubmitOutcome(MeasurementResponse body, boolean replayed) {
    }

    /**
     * 批量提交结果。
     *
     * @param body    批量提交响应
     * @param created 是否写入了新记录（全部为重放时为 false，接口返回 200）
     */
    public record BatchSubmitOutcome(BatchSubmitResponse body, boolean created) {
    }

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final MeasurementVersionRepository versions;
    private final ReleaseRepository releases;

    public MeasurementService(MeasurementRepository measurements,
                              CertificateRepository certificates,
                              MeasurementVersionRepository versions,
                              ReleaseRepository releases) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.versions = versions;
        this.releases = releases;
    }

    /**
     * 提交测量。按测量时刻匹配唯一有效证书（端点到期即无效），无匹配返回 422；
     * 使用 BigDecimal 精确计算 a×读数+b，合格判断基于未舍入值且包含端点；
     * 不确定度为 |补偿系数×读数|。measurementKey 重复返回 409。
     * referenceKey 为幂等引用键：同键同指纹重放返回已有结果（不占新键），
     * 同键不同指纹返回 409；校验失败不写入任何记录，失败不占键。
     */
    @Transactional
    public SubmitOutcome submit(SubmitMeasurementRequest request) {
        String key = Inputs.requireText(request.measurementKey(), "measurementKey");
        String referenceKey = Inputs.optionalText(request.referenceKey());
        String batchId = Inputs.optionalText(request.batchId());
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
        String fingerprint = referenceKey == null ? null
                : Fingerprints.of(1, cert.certVersion(), measuredAt,
                        instrumentId, reading, lower, upper, submittedBy, cert.id());

        if (referenceKey != null) {
            var existing = measurements.findByReferenceKey(referenceKey);
            if (existing.isPresent()) {
                return replayOrConflict(existing.get(), referenceKey, fingerprint);
            }
        }

        Measurement measurement = new Measurement(
                0L, key, batchId, referenceKey, fingerprint, instrumentId, measuredAt,
                reading, lower, upper, submittedBy,
                cert.id(), cert.certVersion(), cert.compensationCoeff(), cert.uncertaintyVersion(),
                computed(cert, reading), uncertainty(cert, reading),
                false, 1, MeasurementStatus.PENDING, Instant.now());
        measurement = withPassed(measurement);
        long id;
        try {
            id = measurements.insert(measurement);
        } catch (DuplicateKeyException ex) {
            // 并发下同键冲突：区分测量键与引用键
            if (referenceKey != null) {
                var existing = measurements.findByReferenceKey(referenceKey);
                if (existing.isPresent()) {
                    return replayOrConflict(existing.get(), referenceKey, fingerprint);
                }
            }
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY", "测量键已存在: " + key);
        }
        versions.insert(id, 1, cert, measurement.computedValue(), measurement.uncertainty(),
                measurement.passed(), measurement.createdAt());
        return new SubmitOutcome(toDetail(measurements.findByKey(key).orElseThrow()), false);
    }

    /**
     * 批量提交测量：先按最终引用预校验全部条目，任一失败则整批拒绝（422）且不写入任何记录；
     * 全部通过后原子写入。同键同指纹的条目视为重放，直接返回已有记录。
     */
    @Transactional
    public BatchSubmitOutcome batchSubmit(BatchSubmitRequest request) {
        String batchId = Inputs.requireText(request == null ? null : request.batchId(), "batchId");
        List<BatchMeasurementItem> items = request.items();
        if (items == null || items.isEmpty() || items.size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("批量提交条数必须为 1～" + MAX_BATCH_SIZE);
        }
        Set<String> seenKeys = new HashSet<>();
        Set<String> seenReferenceKeys = new HashSet<>();
        for (BatchMeasurementItem item : items) {
            String key = Inputs.requireText(item == null ? null : item.measurementKey(), "items[].measurementKey");
            if (!seenKeys.add(key)) {
                throw ApiException.badRequest("批量提交包含重复测量键: " + key);
            }
            String referenceKey = Inputs.optionalText(item.referenceKey());
            if (referenceKey != null && !seenReferenceKeys.add(referenceKey)) {
                throw ApiException.badRequest("批量提交包含重复引用键: " + referenceKey);
            }
        }

        List<ItemFailure> failures = new ArrayList<>();
        Map<String, Measurement> replayed = new HashMap<>();
        List<Measurement> toInsert = new ArrayList<>();
        Map<String, Certificate> matchedCerts = new HashMap<>();
        Instant now = Instant.now();
        for (BatchMeasurementItem item : items) {
            String key = item.measurementKey().trim();
            Measurement prepared;
            Certificate cert;
            try {
                String instrumentId = Inputs.requireText(item.instrumentId(), "instrumentId");
                Instant measuredAt = Inputs.requireInstant(item.measuredAt(), "measuredAt");
                BigDecimal reading = Inputs.requireDecimal(item.reading(), "reading");
                BigDecimal lower = Inputs.requireDecimal(item.lowerLimit(), "lowerLimit");
                BigDecimal upper = Inputs.requireDecimal(item.upperLimit(), "upperLimit");
                String submittedBy = Inputs.requireText(item.submittedBy(), "submittedBy");
                if (lower.compareTo(upper) > 0) {
                    throw ApiException.badRequest("lowerLimit 不能大于 upperLimit");
                }
                cert = certificates.findMatching(instrumentId, measuredAt).orElse(null);
                if (cert == null) {
                    failures.add(new ItemFailure(key, List.of("NO_MATCHING_CERTIFICATE")));
                    continue;
                }
                String referenceKey = Inputs.optionalText(item.referenceKey());
                String fingerprint = referenceKey == null ? null
                        : Fingerprints.of(1, cert.certVersion(), measuredAt,
                                instrumentId, reading, lower, upper, submittedBy, cert.id());
                if (referenceKey != null) {
                    var existing = measurements.findByReferenceKey(referenceKey);
                    if (existing.isPresent()) {
                        if (existing.get().referenceFingerprint().equals(fingerprint)) {
                            replayed.put(key, existing.get());
                        } else {
                            failures.add(new ItemFailure(key, List.of("REFERENCE_KEY_CONFLICT")));
                        }
                        continue;
                    }
                }
                if (measurements.findByKey(key).isPresent()) {
                    failures.add(new ItemFailure(key, List.of("DUPLICATE_MEASUREMENT_KEY")));
                    continue;
                }
                prepared = new Measurement(
                        0L, key, batchId, referenceKey, fingerprint, instrumentId, measuredAt,
                        reading, lower, upper, submittedBy,
                        cert.id(), cert.certVersion(), cert.compensationCoeff(), cert.uncertaintyVersion(),
                        computed(cert, reading), uncertainty(cert, reading),
                        false, 1, MeasurementStatus.PENDING, now);
                prepared = withPassed(prepared);
            } catch (ApiException ex) {
                failures.add(new ItemFailure(key, List.of("INVALID_INPUT")));
                continue;
            }
            toInsert.add(prepared);
            matchedCerts.put(key, cert);
        }

        if (!failures.isEmpty()) {
            throw new BatchRejectedException(failures, HttpStatus.UNPROCESSABLE_ENTITY,
                    "BATCH_SUBMIT_REJECTED", "批量测量提交被拒绝：存在不满足条件的测量，未写入任何记录");
        }

        for (Measurement measurement : toInsert) {
            long id;
            try {
                id = measurements.insert(measurement);
            } catch (DuplicateKeyException ex) {
                throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY",
                        "测量键或引用键已存在: " + measurement.measurementKey());
            }
            Certificate cert = matchedCerts.get(measurement.measurementKey());
            versions.insert(id, 1, cert, measurement.computedValue(), measurement.uncertainty(),
                    measurement.passed(), measurement.createdAt());
        }

        List<MeasurementResponse> responses = new ArrayList<>();
        for (BatchMeasurementItem item : items) {
            String key = item.measurementKey().trim();
            Measurement measurement = replayed.containsKey(key)
                    ? replayed.get(key)
                    : measurements.findByKey(key).orElseThrow();
            responses.add(toDetail(measurement));
        }
        return new BatchSubmitOutcome(new BatchSubmitResponse(batchId, responses), !toInsert.isEmpty());
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
     * 测量血缘：全部版本按版本号升序，current 标记当前有效版本；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public LineageResponse lineage(String key) {
        Measurement measurement = measurements.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        var history = versions.findByMeasurementId(measurement.id()).stream()
                .map(v -> DtoMapper.toResponse(v, measurement.version()))
                .toList();
        return new LineageResponse(measurement.measurementKey(), measurement.version(), history);
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

    /** 同键重放判定：指纹一致返回已有结果，否则 409 冲突。 */
    private SubmitOutcome replayOrConflict(Measurement existing, String referenceKey, String fingerprint) {
        if (existing.referenceFingerprint().equals(fingerprint)) {
            return new SubmitOutcome(toDetail(existing), true);
        }
        throw ApiException.conflict("REFERENCE_KEY_CONFLICT",
                "referenceKey 已被不同输入占用: " + referenceKey);
    }

    /** 未舍入计算值 a×读数+b。 */
    static BigDecimal computed(Certificate cert, BigDecimal reading) {
        return cert.a().multiply(reading).add(cert.b());
    }

    /** 不确定度 |补偿系数×读数|。 */
    static BigDecimal uncertainty(Certificate cert, BigDecimal reading) {
        return cert.compensationCoeff().abs().multiply(reading.abs());
    }

    /** 基于未舍入计算值与上下限（含端点）填充合格判定。 */
    private static Measurement withPassed(Measurement m) {
        boolean passed = m.computedValue().compareTo(m.lowerLimit()) >= 0
                && m.computedValue().compareTo(m.upperLimit()) <= 0;
        return new Measurement(m.id(), m.measurementKey(), m.batchId(), m.referenceKey(),
                m.referenceFingerprint(), m.instrumentId(), m.measuredAt(), m.rawReading(),
                m.lowerLimit(), m.upperLimit(), m.submittedBy(), m.certificateId(), m.certVersion(),
                m.compensationCoeff(), m.uncertaintyVersion(), m.computedValue(), m.uncertainty(),
                passed, m.version(), m.status(), m.createdAt());
    }

    private MeasurementResponse toDetail(Measurement measurement) {
        boolean certRevoked = certificates.findById(measurement.certificateId())
                .map(Certificate::revoked)
                .orElse(true);
        return DtoMapper.toResponse(measurement, certRevoked,
                releases.findByMeasurementId(measurement.id()));
    }
}
