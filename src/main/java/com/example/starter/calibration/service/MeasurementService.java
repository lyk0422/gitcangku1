package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.ReviseMeasurementRequest;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 测量服务：提交（匹配唯一有效证书并固化计算结果）、修订（新版本原子落库并切换最新指针）、
 * 版本历史、历史明细、当前可用结果查询。
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
     * 提交测量（首版，revision=1）。按测量时刻匹配唯一有效证书，无匹配返回 422；
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
                0L, key, 1, true, null, null, instrumentId, measuredAt, reading, lower, upper,
                submittedBy, cert.id(), computed, passed, MeasurementStatus.PENDING, Instant.now());
        try {
            measurements.insert(measurement);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY", "测量键已存在: " + key);
        }
        return toDetail(measurements.findLatestByKey(key).orElseThrow());
    }

    /**
     * 修订测量：仅原提交人（X-Actor-Id）可修订；expectedRevision 必须等于当前最新修订号，
     * 否则 409。按原测量时刻重匹配未撤销证书，无匹配返回 422。新版、最新指针切换与修订原因
     * 在同一事务原子落库；新版为 PENDING，旧版立即失去当前可用资格且不回退。
     * requestId 同键同参重放返回首次结果，改参返回 409；失败不占用 requestId。
     */
    @Transactional
    public MeasurementResponse revise(String key, ReviseMeasurementRequest request, String actor) {
        String actorId = Inputs.requireText(actor, "X-Actor-Id");
        if (request.expectedRevision() == null || request.expectedRevision() < 1) {
            throw ApiException.badRequest("expectedRevision 必须为正整数");
        }
        int expectedRevision = request.expectedRevision();
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        String reason = Inputs.requireText(request.reason(), "reason");
        BigDecimal reading = Inputs.requireDecimal(request.reading(), "reading");
        BigDecimal lower = Inputs.requireDecimal(request.lowerLimit(), "lowerLimit");
        BigDecimal upper = Inputs.requireDecimal(request.upperLimit(), "upperLimit");
        if (lower.compareTo(upper) > 0) {
            throw ApiException.badRequest("lowerLimit 不能大于 upperLimit");
        }

        // 锁定该键全部版本行，串行化同一键的并发修订：后到事务看到新修订号后按 409 裁决。
        // 注意：行锁等待结束后原查询快照不含对方事务新插入的版本行，
        // 因此加锁后在同事务内重新读取最新已提交状态再做判定。
        List<Measurement> locked = measurements.findAllByKeyForUpdate(key);
        if (locked.isEmpty()) {
            throw ApiException.notFound("测量不存在: " + key);
        }
        List<Measurement> versions = measurements.findHistory(key);
        Measurement latest = versions.stream().filter(Measurement::isLatest).findFirst().orElseThrow();

        // requestId 重放：同键同参返回首次结果（不切回旧版），改参 409
        Optional<Measurement> replayed = versions.stream()
                .filter(m -> requestId.equals(m.requestId()))
                .findFirst();
        if (replayed.isPresent()) {
            Measurement existing = replayed.get();
            boolean sameParams = existing.revision() == expectedRevision + 1
                    && existing.submittedBy().equals(actorId)
                    && existing.rawReading().compareTo(reading) == 0
                    && existing.lowerLimit().compareTo(lower) == 0
                    && existing.upperLimit().compareTo(upper) == 0
                    && reason.equals(existing.revisionReason());
            if (!sameParams) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已被不同参数的修订使用: " + requestId);
            }
            return toDetail(existing);
        }

        if (!latest.submittedBy().equals(actorId)) {
            throw ApiException.conflict("ACTOR_MISMATCH", "仅原提交人可修订该测量");
        }
        if (latest.revision() != expectedRevision) {
            throw ApiException.conflict("REVISION_MISMATCH",
                    "expectedRevision 与当前最新修订号不一致: 当前为 " + latest.revision());
        }

        Certificate cert = certificates.findMatching(latest.instrumentId(), latest.measuredAt())
                .orElseThrow(() -> ApiException.unprocessable(
                        "测量时刻无匹配的有效证书: instrument=" + latest.instrumentId()));

        BigDecimal computed = cert.a().multiply(reading).add(cert.b());
        boolean passed = computed.compareTo(lower) >= 0 && computed.compareTo(upper) <= 0;

        Measurement revision = new Measurement(
                0L, key, latest.revision() + 1, true, requestId, reason,
                latest.instrumentId(), latest.measuredAt(), reading, lower, upper,
                latest.submittedBy(), cert.id(), computed, passed, MeasurementStatus.PENDING,
                Instant.now());
        try {
            long id = measurements.insert(revision);
            measurements.markNotLatest(latest.id());
            return toDetail(measurements.findById(id).orElseThrow());
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("REVISION_CONFLICT", "修订号冲突，请重试: " + key);
        }
    }

    /**
     * 历史明细：默认返回最新版本，包含原始测量、未舍入计算值、显示值与放行历史；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public MeasurementResponse detail(String key) {
        Measurement measurement = measurements.findLatestByKey(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        return toDetail(measurement);
    }

    /**
     * 版本历史：返回该测量键全部修订版本（按修订号升序）；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<MeasurementResponse> history(String key) {
        List<Measurement> versions = measurements.findHistory(key);
        if (versions.isEmpty()) {
            throw ApiException.notFound("测量不存在: " + key);
        }
        return versions.stream().map(this::toDetail).toList();
    }

    /**
     * 当前可用结果：最新版、已放行且证书未撤销，每键至多一条。instrumentId 为 null 时返回全部仪器。
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
        return DtoMapper.toResponse(measurement, certRevoked,
                releases.findByMeasurementId(measurement.id()));
    }
}
