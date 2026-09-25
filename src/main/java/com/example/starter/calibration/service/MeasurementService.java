package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.MeasurementVersionResponse;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.model.BatchSubmit;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.MeasurementVersion;
import com.example.starter.calibration.repo.BatchSubmitRepository;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.MeasurementVersionRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 测量服务：版本化提交（显式/自动引用、到期与撤销门禁、不确定度与 referenceKey 指纹）、
 * 批量提交（先按最终引用预校验、整批原子、batchId 幂等重放）、
 * 未放行测量替换标准器重算（事务原子，失败保留旧版本）、血缘与可用查询。
 */
@Service
public class MeasurementService {

    /** 单批最大条数。 */
    static final int MAX_BATCH_SIZE = 50;

    private final MeasurementRepository measurements;
    private final MeasurementVersionRepository versions;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final BatchSubmitRepository batchSubmits;
    private final CertificateResolver resolver;
    private final Clock clock;

    public MeasurementService(MeasurementRepository measurements,
                              MeasurementVersionRepository versions,
                              CertificateRepository certificates,
                              ReleaseRepository releases,
                              BatchSubmitRepository batchSubmits,
                              CertificateResolver resolver,
                              Clock clock) {
        this.measurements = measurements;
        this.versions = versions;
        this.certificates = certificates;
        this.releases = releases;
        this.batchSubmits = batchSubmits;
        this.resolver = resolver;
        this.clock = clock;
    }

    /**
     * 提交单条测量（版本 1）。引用证书必须在测量时刻有效（左闭右开，端点到期即无效）且未撤销，
     * 否则返回可区分的 422（CERTIFICATE_NOT_FOUND/CERTIFICATE_REVOKED/CERTIFICATE_EXPIRED/
     * CERTIFICATE_NOT_YET_VALID/NO_MATCHING_CERTIFICATE）。
     * measurementKey 重复返回 409；失败不写入任何版本。
     */
    @Transactional
    public MeasurementResponse submit(SubmitMeasurementRequest request) {
        ParsedMeasurement parsed = parse(request, request.submittedBy());
        if (measurements.findByKey(parsed.measurementKey()).isPresent()) {
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY",
                    "测量键已存在: " + parsed.measurementKey());
        }
        Measurement created = persistNew(parsed, clock.instant());
        return toDetail(measurements.findByKey(created.measurementKey()).orElseThrow());
    }

    /**
     * 批量提交测量。先按最终引用对全部条目预校验（不落库），全部可解析后整批原子写入；
     * 任一条失败整批拒绝且不留半成品。相同 batchId 且载荷一致重放返回首次结果；
     * 相同 batchId 载荷不一致返回 409；失败不占用 batchId。
     */
    @Transactional
    public com.example.starter.calibration.api.dto.BatchSubmitMeasurementResponse submitBatch(
            com.example.starter.calibration.api.dto.BatchSubmitMeasurementRequest request) {
        String batchId = Inputs.requireText(request.batchId(), "batchId");
        String defaultSubmitter = Inputs.requireText(request.submittedBy(), "submittedBy");
        List<SubmitMeasurementRequest> items = request.items();
        if (items == null || items.isEmpty() || items.size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("批量测量提交条数必须为 1～" + MAX_BATCH_SIZE);
        }

        // 预校验：规范化全部条目（含最终引用解析），任一失败即整体拒绝，此时尚未写库
        List<ParsedMeasurement> parsedList = new ArrayList<>(items.size());
        Set<String> seenKeys = new HashSet<>();
        List<String> canonicalItems = new ArrayList<>(items.size());
        for (SubmitMeasurementRequest item : items) {
            ParsedMeasurement parsed = parse(item, defaultSubmitter);
            if (!seenKeys.add(parsed.measurementKey())) {
                throw ApiException.badRequest("批量提交包含重复测量键: " + parsed.measurementKey());
            }
            parsedList.add(parsed);
            canonicalItems.add(Calcs.batchItemCanonical(
                    parsed.measurementKey(), parsed.certificate().standardId(),
                    parsed.certificate().version(), parsed.measuredAt(), parsed.instrumentId(),
                    parsed.reading(), parsed.lowerLimit(), parsed.upperLimit(), parsed.submittedBy()));
        }
        String fingerprint = Calcs.batchFingerprint(canonicalItems);

        // 幂等重放优先：台账已存在则直接按首次结果裁决（并发下后到的同载荷请求也走此路径）
        BatchSubmit existingLedger = batchSubmits.findByBatchId(batchId).orElse(null);
        if (existingLedger != null) {
            return replayOrConflict(existingLedger, fingerprint, parsedList);
        }

        // 预校验：测量键不得已存在，避免整批写入中途唯一键冲突造成回滚歧义
        for (ParsedMeasurement parsed : parsedList) {
            if (measurements.findByKey(parsed.measurementKey()).isPresent()) {
                throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY",
                        "测量键已存在: " + parsed.measurementKey());
            }
        }

        Instant now = clock.instant();
        try {
            // 先写幂等台账：并发下相同 batchId 仅一个事务能插入，败者转重放/冲突裁决；
            // 台账与测量在同一事务，后续任一写入失败整笔回滚，因此失败不会占用 batchId。
            batchSubmits.insert(new BatchSubmit(batchId, defaultSubmitter, parsedList.size(), fingerprint, now));
            for (ParsedMeasurement parsed : parsedList) {
                persistNew(parsed, now);
            }
        } catch (DuplicateKeyException ex) {
            if (batchSubmits.isBatchIdDuplicate(ex)) {
                // 获胜事务可能仍在提交：有界等待其台账可见后按重放/冲突裁决
                BatchSubmit winner = awaitLedger(batchId);
                if (winner != null) {
                    return replayOrConflict(winner, fingerprint, parsedList);
                }
            }
            // 其他唯一键冲突（如并发的测量键）按测量键冲突返回
            throw ApiException.conflict("BATCH_SUBMIT_CONFLICT",
                    "批量提交并发冲突或测量键已存在，请重试: " + batchId);
        }

        List<MeasurementResponse> responses = loadResponses(parsedList);
        return new com.example.starter.calibration.api.dto.BatchSubmitMeasurementResponse(
                batchId, false, defaultSubmitter, now, responses);
    }

    /**
     * 对未放行测量替换标准器：解析新证书、生成新测量版本并重算全部补偿值与不确定度。
     * 整个过程在一个事务内；任一步失败回滚，旧版本仍是当前有效版本。
     * 目标证书与当前引用相同视为同请求重放，幂等返回当前版本，不重复生成版本。
     */
    @Transactional
    public MeasurementResponse recalculate(String measurementKey,
                                           com.example.starter.calibration.api.dto.RecalculateRequest request) {
        String key = Inputs.requireText(measurementKey, "measurementKey");
        String standardId = Inputs.requireText(request.standardId(), "standardId");
        String certificateVersion = Inputs.requireText(request.certificateVersion(), "certificateVersion");

        Measurement current = measurements.findByKeyForUpdate(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        if (current.status() != MeasurementStatus.PENDING) {
            throw ApiException.conflict("ALREADY_RELEASED",
                    "已放行测量不允许替换标准器: " + key);
        }
        Certificate newCert = resolver.resolve(
                current.instrumentId(), standardId, certificateVersion, current.measuredAt());

        // 幂等：目标引用与当前版本一致，直接返回当前版本
        if (newCert.id() == current.certificateId()) {
            return toDetail(current);
        }

        int newVersionNo = current.versionNo() + 1;
        BigDecimal computed = Calcs.computedValue(newCert.a(), newCert.b(), current.rawReading());
        BigDecimal expanded = Calcs.expandedUncertainty(newCert.uncertainty());
        boolean passed = Calcs.isPassed(computed, current.lowerLimit(), current.upperLimit());
        String referenceKey = Calcs.referenceKey(newVersionNo, newCert.standardId(), newCert.version(),
                current.measuredAt(), current.instrumentId(), current.rawReading(),
                current.lowerLimit(), current.upperLimit(), current.submittedBy());

        Instant now = clock.instant();
        MeasurementVersion snapshot = new MeasurementVersion(
                0L, current.id(), newVersionNo, newCert.id(), newCert.standardId(), newCert.version(),
                newCert.a(), newCert.b(), newCert.uncertainty(), newCert.uncertaintyVersion(),
                computed, expanded, passed, referenceKey, now);
        try {
            versions.append(snapshot);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("MEASUREMENT_VERSION_EXISTS",
                    "测量版本已存在（相同引用重算被拒绝）: " + key + "#v" + newVersionNo);
        }
        measurements.switchVersion(current.id(), newCert.id(), newCert.standardId(), newCert.version(),
                newVersionNo, computed, expanded, newCert.uncertaintyVersion(), referenceKey, passed);
        return toDetail(measurements.findByKey(key).orElseThrow());
    }

    /**
     * 历史明细：包含原始测量、当前版本计算值/不确定度/指纹、显示值与放行历史；不存在 404。
     */
    @Transactional(readOnly = true)
    public MeasurementResponse detail(String key) {
        Measurement measurement = measurements.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        return toDetail(measurement);
    }

    /**
     * 测量血缘：当前明细 + 全部版本快照（标记当前版本）。
     */
    @Transactional(readOnly = true)
    public com.example.starter.calibration.api.dto.MeasurementLineageResponse lineage(String key) {
        Measurement measurement = measurements.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        List<MeasurementVersionResponse> versionResponses = versions
                .findByMeasurementId(measurement.id()).stream()
                .map(v -> toVersionResponse(v, v.versionNo() == measurement.versionNo()))
                .toList();
        return new com.example.starter.calibration.api.dto.MeasurementLineageResponse(
                toDetail(measurement), versionResponses);
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

    // ---- 内部辅助 ----

    /**
     * 规范化并预校验单条提交输入，按最终引用解析出测量时刻有效的证书。
     */
    private ParsedMeasurement parse(SubmitMeasurementRequest request, String defaultSubmitter) {
        String key = Inputs.requireText(request.measurementKey(), "measurementKey");
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        String standardId = Inputs.optionalText(request.standardId());
        String certificateVersion = Inputs.optionalText(request.certificateVersion());
        if (standardId == null ^ certificateVersion == null) {
            throw ApiException.badRequest("standardId 与 certificateVersion 必须同时提供或同时省略");
        }
        Instant measuredAt = Inputs.requireInstant(request.measuredAt(), "measuredAt");
        BigDecimal reading = Inputs.requireDecimal(request.reading(), "reading");
        BigDecimal lower = Inputs.requireDecimal(request.lowerLimit(), "lowerLimit");
        BigDecimal upper = Inputs.requireDecimal(request.upperLimit(), "upperLimit");
        String submitter = Inputs.optionalText(request.submittedBy());
        if (submitter == null) {
            submitter = Inputs.requireText(defaultSubmitter, "submittedBy");
        }
        if (lower.compareTo(upper) > 0) {
            throw ApiException.badRequest("lowerLimit 不能大于 upperLimit");
        }
        Certificate cert = resolver.resolve(instrumentId, standardId, certificateVersion, measuredAt);
        return new ParsedMeasurement(key, instrumentId, measuredAt, reading, lower, upper, submitter, cert);
    }

    /**
     * 在当前事务内写入一条新测量的当前记录与版本 1 快照。
     */
    private Measurement persistNew(ParsedMeasurement parsed, Instant now) {
        Certificate cert = parsed.certificate();
        BigDecimal computed = Calcs.computedValue(cert.a(), cert.b(), parsed.reading());
        BigDecimal expanded = Calcs.expandedUncertainty(cert.uncertainty());
        boolean passed = Calcs.isPassed(computed, parsed.lowerLimit(), parsed.upperLimit());
        String referenceKey = Calcs.referenceKey(1, cert.standardId(), cert.version(),
                parsed.measuredAt(), parsed.instrumentId(), parsed.reading(),
                parsed.lowerLimit(), parsed.upperLimit(), parsed.submittedBy());

        Measurement measurement = new Measurement(
                0L, parsed.measurementKey(), parsed.instrumentId(), cert.standardId(),
                parsed.measuredAt(), parsed.reading(), parsed.lowerLimit(), parsed.upperLimit(),
                parsed.submittedBy(), cert.id(), cert.version(), 1, computed, expanded,
                cert.uncertaintyVersion(), referenceKey, passed, MeasurementStatus.PENDING, now);
        long id;
        try {
            id = measurements.insert(measurement);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY",
                    "测量键已存在: " + parsed.measurementKey());
        }
        MeasurementVersion snapshot = new MeasurementVersion(
                0L, id, 1, cert.id(), cert.standardId(), cert.version(),
                cert.a(), cert.b(), cert.uncertainty(), cert.uncertaintyVersion(),
                computed, expanded, passed, referenceKey, now);
        versions.append(snapshot);
        return new Measurement(id, measurement.measurementKey(), measurement.instrumentId(),
                measurement.standardId(), measurement.measuredAt(), measurement.rawReading(),
                measurement.lowerLimit(), measurement.upperLimit(), measurement.submittedBy(),
                measurement.certificateId(), measurement.certificateVersion(), measurement.versionNo(),
                measurement.computedValue(), measurement.expandedUncertainty(),
                measurement.uncertaintyVersion(), measurement.referenceKey(), measurement.passed(),
                measurement.status(), measurement.createdAt());
    }

    /**
     * 相同 batchId 的幂等裁决：载荷一致则重放首次结果，否则 409。
     */
    private com.example.starter.calibration.api.dto.BatchSubmitMeasurementResponse replayOrConflict(
            BatchSubmit ledger, String fingerprint, List<ParsedMeasurement> parsedList) {
        if (!ledger.fingerprint().equals(fingerprint) || ledger.itemCount() != parsedList.size()) {
            throw ApiException.conflict("BATCH_SUBMIT_CONFLICT",
                    "batchId 已被不同载荷的提交占用: " + ledger.batchId());
        }
        List<MeasurementResponse> responses = loadResponses(parsedList);
        return new com.example.starter.calibration.api.dto.BatchSubmitMeasurementResponse(
                ledger.batchId(), true, ledger.submittedBy(), ledger.createdAt(), responses);
    }

    /**
     * 台账唯一键冲突后，有界等待获胜并发事务提交其台账（最多约 2 秒），超时返回 null。
     */
    private BatchSubmit awaitLedger(String batchId) {
        for (int i = 0; i < 40; i++) {
            BatchSubmit ledger = batchSubmits.findByBatchId(batchId).orElse(null);
            if (ledger != null) {
                return ledger;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private List<MeasurementResponse> loadResponses(List<ParsedMeasurement> parsedList) {
        List<MeasurementResponse> responses = new ArrayList<>(parsedList.size());
        for (ParsedMeasurement parsed : parsedList) {
            Measurement stored = measurements.findByKey(parsed.measurementKey())
                    .orElseThrow(() -> ApiException.notFound("测量不存在: " + parsed.measurementKey()));
            responses.add(toDetail(stored));
        }
        return responses;
    }

    private MeasurementResponse toDetail(Measurement measurement) {
        boolean certRevoked = certificates.findById(measurement.certificateId())
                .map(Certificate::revoked)
                .orElse(true);
        return DtoMapper.toResponse(measurement, certRevoked,
                releases.findByMeasurementId(measurement.id()));
    }

    private MeasurementVersionResponse toVersionResponse(MeasurementVersion v, boolean current) {
        return new MeasurementVersionResponse(
                v.versionNo(), current, v.standardId(), v.certificateId(), v.certificateVersion(),
                DtoMapper.format(v.a()), DtoMapper.format(v.b()), DtoMapper.format(v.uncertainty()),
                v.uncertaintyVersion(), DtoMapper.format(v.computedValue()),
                DtoMapper.format(v.expandedUncertainty()), v.passed(), v.referenceKey(), v.createdAt());
    }
}
