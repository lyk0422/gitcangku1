package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.MeasurementHistoryResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.ReviseMeasurementRequest;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementHead;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.RevisionRequestRecord;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 测量服务：首次提交、修订（版本递增）、最新明细、版本历史、当前可用结果查询。
 * 新版本行、最新指针与修订原因在同一事务内原子落库；修订失败不增号、不切换指针。
 */
@Service
public class MeasurementService {

    /** 修订原因最大长度（与 schema 一致）。 */
    static final int MAX_REASON_LENGTH = 500;

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
     * 提交测量（第 1 版）。按测量时刻匹配唯一有效证书，无匹配返回 422；
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

        Instant now = Instant.now();
        Measurement measurement = new Measurement(
                0L, key, 1, instrumentId, measuredAt, reading, lower, upper, submittedBy,
                cert.id(), computed, passed, MeasurementStatus.PENDING, null, null, null, now);
        long measurementId;
        try {
            measurementId = measurements.insert(measurement);
            measurements.insertHead(key, 1, measurementId, now, now);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY", "测量键已存在: " + key);
        }
        return toResponse(measurements.findByKeyAndRevision(key, 1).orElseThrow(), true);
    }

    /**
     * 修订测量。仅原提交人可发起；expectedRevision 须等于当前最新版本。
     * 仪器与测量 UTC 时刻沿用第 1 版，按原测量时刻重新匹配未撤销证书；无匹配 422。
     * 新版本为 PENDING，旧版原始值、计算结果与放行历史保留；
     * 新版本提交即把旧版排除出当前可用集合（指针原子切换），不因新版未放行而回退。
     * 同 (key, requestId) 同参重放返回首次结果；改参 409；失败不占 requestId。
     */
    @Transactional
    public MeasurementResponse revise(ReviseMeasurementRequest request, String actorHeader) {
        String key = Inputs.requireText(request.measurementKey(), "measurementKey");
        int expectedRevision = Inputs.requirePositive(request.expectedRevision(), "expectedRevision");
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        String reason = Inputs.requireReason(request.reason(), MAX_REASON_LENGTH, "reason");
        BigDecimal reading = Inputs.requireDecimal(request.reading(), "reading");
        BigDecimal lower = Inputs.requireDecimal(request.lowerLimit(), "lowerLimit");
        BigDecimal upper = Inputs.requireDecimal(request.upperLimit(), "upperLimit");
        String actor = Inputs.requireText(actorHeader, "X-Actor-Id");
        if (lower.compareTo(upper) > 0) {
            throw ApiException.badRequest("lowerLimit 不能大于 upperLimit");
        }

        // 最新指针行锁串行化同键并发修订：两次同 expectedRevision 并发修订最多一次成功。
        MeasurementHead head = measurements.findHeadForUpdate(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));

        // 持锁后再查幂等记录，保证并发重放也能命中首次结果。
        Optional<RevisionRequestRecord> replay = measurements.findRevisionRequest(key, requestId);
        if (replay.isPresent()) {
            RevisionRequestRecord first = replay.get();
            if (!sameParameters(first, expectedRevision, requestId, reason, reading, lower, upper, actor)) {
                throw ApiException.conflict("IDEMPOTENCY_PARAM_MISMATCH",
                        "requestId 已用于不同参数的修订: " + requestId);
            }
            Measurement result = measurements.findByKeyAndRevision(key, first.resultingRevision())
                    .orElseThrow();
            // 旧响应重放只返回首次结果，绝不切回旧版：latest 按当前指针判定。
            return toResponse(result, head.latestRevision() == result.revision());
        }

        Measurement base = measurements.findByKeyAndRevision(key, 1)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        if (!base.submittedBy().equals(actor)) {
            throw ApiException.conflict("NOT_SUBMITTER",
                    "仅原提交人可以修订该测量: " + key);
        }
        if (head.latestRevision() != expectedRevision) {
            throw ApiException.conflict("REVISION_CONFLICT",
                    "期望版本 " + expectedRevision + " 不是最新版本，当前最新版本为 "
                            + head.latestRevision());
        }

        // 仪器与测量时刻不变，按原测量时刻重新匹配未撤销证书。
        Certificate cert = certificates.findMatching(base.instrumentId(), base.measuredAt())
                .orElseThrow(() -> ApiException.unprocessable(
                        "测量时刻无匹配的有效证书: instrument=" + base.instrumentId()));

        BigDecimal computed = cert.a().multiply(reading).add(cert.b());
        boolean passed = computed.compareTo(lower) >= 0 && computed.compareTo(upper) <= 0;

        int newRevision = expectedRevision + 1;
        Instant now = Instant.now();
        Measurement revised = new Measurement(
                0L, key, newRevision, base.instrumentId(), base.measuredAt(), reading, lower, upper,
                base.submittedBy(), cert.id(), computed, passed, MeasurementStatus.PENDING,
                reason, actor, now, now);
        long newMeasurementId;
        try {
            newMeasurementId = measurements.insert(revised);
            measurements.updateHead(key, newRevision, newMeasurementId, now);
            measurements.insertRevisionRequest(new RevisionRequestRecord(
                    0L, key, requestId, expectedRevision, reading, lower, upper, reason, actor,
                    newRevision, now));
        } catch (DuplicateKeyException ex) {
            // 并发布局极端冲突（版本号或 requestId 唯一约束）：整笔回滚，不增号不切指针。
            throw ApiException.conflict("REVISION_CONFLICT", "修订与并发请求冲突，请重试");
        }
        return toResponse(measurements.findByKeyAndRevision(key, newRevision).orElseThrow(), true);
    }

    /**
     * 历史明细（默认最新版本）：包含原始测量、未舍入计算值、显示值与该版本放行历史；不存在 404。
     */
    @Transactional(readOnly = true)
    public MeasurementResponse detail(String key) {
        MeasurementHead head = measurements.findHead(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        Measurement latest = measurements.findByKeyAndRevision(key, head.latestRevision()).orElseThrow();
        return toResponse(latest, true);
    }

    /**
     * 指定版本明细；键或版本不存在返回 404。
     */
    @Transactional(readOnly = true)
    public MeasurementResponse detailRevision(String key, int revision) {
        MeasurementHead head = measurements.findHead(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        Measurement measurement = measurements.findByKeyAndRevision(key, revision)
                .orElseThrow(() -> ApiException.notFound("测量版本不存在: " + key + "#" + revision));
        return toResponse(measurement, head.latestRevision() == revision);
    }

    /**
     * 版本历史：全部版本按版本号升序返回；不存在 404。
     */
    @Transactional(readOnly = true)
    public MeasurementHistoryResponse history(String key) {
        MeasurementHead head = measurements.findHead(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        List<Measurement> all = measurements.findAllByKey(key);
        List<MeasurementResponse> revisions = all.stream()
                .map(m -> toResponse(m, m.revision() == head.latestRevision()))
                .toList();
        return new MeasurementHistoryResponse(key, head.latestRevision(), revisions);
    }

    /**
     * 当前可用结果：每键至多一行最新版本、已放行且证书未撤销。instrumentId 为 null 时返回全部仪器。
     */
    @Transactional(readOnly = true)
    public List<MeasurementResponse> usable(String instrumentId) {
        String instrument = instrumentId == null || instrumentId.isBlank() ? null : instrumentId.trim();
        return measurements.findUsable(instrument).stream()
                .map(m -> toResponse(m, true))
                .toList();
    }

    /**
     * 组装单个版本的明细响应；latest 由调用方按当前指针判定。
     */
    private MeasurementResponse toResponse(Measurement measurement, boolean latest) {
        boolean certRevoked = certificates.findById(measurement.certificateId())
                .map(Certificate::revoked)
                .orElse(true);
        return DtoMapper.toResponse(measurement, latest, certRevoked,
                releases.findByMeasurementId(measurement.id()));
    }

    private boolean sameParameters(RevisionRequestRecord first, int expectedRevision, String requestId,
                                   String reason, BigDecimal reading, BigDecimal lower,
                                   BigDecimal upper, String actor) {
        return first.expectedRevision() == expectedRevision
                && first.requestId().equals(requestId)
                && first.reason().equals(reason)
                && first.actor().equals(actor)
                && first.rawReading().compareTo(reading) == 0
                && first.lowerLimit().compareTo(lower) == 0
                && first.upperLimit().compareTo(upper) == 0;
    }
}
