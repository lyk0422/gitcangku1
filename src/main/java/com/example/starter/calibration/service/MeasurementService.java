package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.RevisionRequest;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;
import com.example.starter.calibration.repo.RevisionRequestRepository;

/**
 * 测量服务：首次提交（第 1 版）、修订（版本递增）、版本历史、明细、当前可用结果查询。
 * 新版本、最新指针与修订幂等记录在同一事务内原子落库；旧版本原样保留。
 */
@Service
public class MeasurementService {

    /** 修订原因最大长度。 */
    static final int MAX_REASON_LENGTH = 255;

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final RevisionRequestRepository revisionRequests;

    public MeasurementService(MeasurementRepository measurements,
                              CertificateRepository certificates,
                              ReleaseRepository releases,
                              RevisionRequestRepository revisionRequests) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.revisionRequests = revisionRequests;
    }

    /**
     * 首次提交测量（第 1 版）。按测量时刻匹配唯一有效证书，无匹配返回 422；
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
        // 先占最新指针行：键冲突说明该 measurementKey 已提交过，整笔事务回滚。
        try {
            measurements.insertLatest(key, 0L, 1, now);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY", "测量键已存在: " + key);
        }
        Measurement measurement = new Measurement(
                0L, key, 1, instrumentId, measuredAt, reading, lower, upper, submittedBy,
                cert.id(), computed, passed, MeasurementStatus.PENDING, null, null, null, null, now);
        long id = measurements.insert(measurement);
        measurements.updateLatest(key, id, 1, now);
        return toDetail(measurements.findById(id).orElseThrow());
    }

    /**
     * 修订测量。仅原提交人（X-Actor-Id）可发起；仪器与测量UTC时刻沿用最新版本，不可修改；
     * 修订读数与合格上下限，按原测量时刻重新匹配未撤销证书，新版本状态 PENDING。
     * 最新指针行锁串行化同键并发修订：相同 expectedRevision 的两次并发修订最多一次成功。
     */
    @Transactional
    public MeasurementResponse revise(String key, ReviseMeasurementRequest request, String actor) {
        String measurementKey = Inputs.requireText(key, "measurementKey");
        String actorId = Inputs.requireText(actor, "X-Actor-Id");
        if (request == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        Integer expectedRevision = request.expectedRevision();
        if (expectedRevision == null || expectedRevision < 1) {
            throw ApiException.badRequest("expectedRevision 必须为不小于 1 的整数");
        }
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        String reason = Inputs.requireText(request.reason(), "reason");
        if (reason.length() > MAX_REASON_LENGTH) {
            throw ApiException.badRequest("reason 长度不能超过 " + MAX_REASON_LENGTH);
        }
        BigDecimal reading = Inputs.requireDecimal(request.reading(), "reading");
        BigDecimal lower = Inputs.requireDecimal(request.lowerLimit(), "lowerLimit");
        BigDecimal upper = Inputs.requireDecimal(request.upperLimit(), "upperLimit");
        if (lower.compareTo(upper) > 0) {
            throw ApiException.badRequest("lowerLimit 不能大于 upperLimit");
        }

        // 锁定最新指针行，串行化同键修订/放行；其保护下“查重—校验—落库”无需额外防并发。
        Measurement latest = measurements.findLatestByKeyForUpdate(measurementKey)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + measurementKey));

        Instant now = Instant.now();
        if (!latest.submittedBy().equals(actorId)) {
            throw ApiException.conflict("FORBIDDEN_ACTOR",
                    "仅原提交人可以修订该测量: " + measurementKey);
        }

        // 幂等重放：同键同 requestId 同参返回首次结果，改参 409；失败请求不落库、不占用 requestId。
        var existingRequest = revisionRequests.find(measurementKey, requestId);
        if (existingRequest.isPresent()) {
            RevisionRequest existing = existingRequest.get();
            if (!sameParams(existing, expectedRevision, reading, lower, upper, reason)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已用于参数不同的修订请求: " + requestId);
            }
            // 同参重放：返回首次创建的版本，绝不重新增号或切换指针。
            return toDetail(measurements.findById(existing.measurementId()).orElseThrow());
        }

        if (latest.revision() != expectedRevision) {
            throw ApiException.conflict("REVISION_MISMATCH",
                    "expectedRevision=" + expectedRevision + " 不是最新版本，当前最新版本: "
                            + latest.revision());
        }

        // 按原测量时刻重新匹配未撤销证书；无匹配 422，失败不增号、不切换指针。
        Certificate cert = certificates.findMatching(latest.instrumentId(), latest.measuredAt())
                .orElseThrow(() -> ApiException.unprocessable(
                        "测量时刻无匹配的有效证书: instrument=" + latest.instrumentId()));

        BigDecimal computed = cert.a().multiply(reading).add(cert.b());
        boolean passed = computed.compareTo(lower) >= 0 && computed.compareTo(upper) <= 0;
        int newRevision = latest.revision() + 1;

        Measurement revised = new Measurement(
                0L, measurementKey, newRevision, latest.instrumentId(), latest.measuredAt(),
                reading, lower, upper, latest.submittedBy(), cert.id(), computed, passed,
                MeasurementStatus.PENDING, reason, requestId, actorId, now, now);
        long id;
        try {
            id = measurements.insert(revised);
        } catch (DuplicateKeyException ex) {
            // 理论上被指针行锁排除；保留唯一约束兜底。
            throw ApiException.conflict("REVISION_CONFLICT", "修订版本号冲突: " + measurementKey);
        }
        measurements.updateLatest(measurementKey, id, newRevision, now);
        revisionRequests.insert(new RevisionRequest(
                0L, measurementKey, requestId, expectedRevision, reading, lower, upper, reason, id, now));
        return toDetail(measurements.findById(id).orElseThrow());
    }

    /**
     * 历史明细：默认返回最新版本；指定 revision 时返回该具体版本。
     * 键或版本不存在返回 404。
     */
    @Transactional(readOnly = true)
    public MeasurementResponse detail(String key, Integer revision) {
        String measurementKey = Inputs.requireText(key, "measurementKey");
        Measurement latest = measurements.findLatestByKey(measurementKey)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + measurementKey));
        Measurement measurement;
        if (revision == null) {
            measurement = latest;
        } else {
            if (revision < 1) {
                throw ApiException.badRequest("revision 必须为不小于 1 的整数");
            }
            measurement = measurements.findByKeyAndRevision(measurementKey, revision)
                    .orElseThrow(() -> ApiException.notFound(
                            "测量版本不存在: " + measurementKey + "#" + revision));
        }
        return toDetail(measurement, latest.revision());
    }

    /**
     * 版本历史：按版本号升序返回某测量键的全部不可变版本；键不存在返回 404。
     */
    @Transactional(readOnly = true)
    public MeasurementHistoryResponse history(String key) {
        String measurementKey = Inputs.requireText(key, "measurementKey");
        List<Measurement> revisions = measurements.findRevisions(measurementKey);
        if (revisions.isEmpty()) {
            throw ApiException.notFound("测量不存在: " + measurementKey);
        }
        int latestRevision = revisions.get(revisions.size() - 1).revision();
        return new MeasurementHistoryResponse(measurementKey, latestRevision,
                revisions.stream().map(m -> toDetail(m, latestRevision)).toList());
    }

    /**
     * 当前可用结果：每键至多一条最新版本，且已放行、证书未撤销。instrumentId 为 null 时返回全部仪器。
     */
    @Transactional(readOnly = true)
    public List<MeasurementResponse> usable(String instrumentId) {
        String instrument = instrumentId == null || instrumentId.isBlank() ? null : instrumentId.trim();
        return measurements.findUsable(instrument).stream()
                .map(this::toDetail)
                .toList();
    }

    private boolean sameParams(RevisionRequest recorded, int expectedRevision, BigDecimal reading,
                               BigDecimal lower, BigDecimal upper, String reason) {
        return recorded.expectedRevision() == expectedRevision
                && recorded.rawReading().compareTo(reading) == 0
                && recorded.lowerLimit().compareTo(lower) == 0
                && recorded.upperLimit().compareTo(upper) == 0
                && recorded.reason().equals(reason);
    }

    private MeasurementResponse toDetail(Measurement measurement) {
        Measurement latest = measurements.findLatestByKey(measurement.measurementKey()).orElseThrow();
        return toDetail(measurement, latest.revision());
    }

    private MeasurementResponse toDetail(Measurement measurement, int latestRevision) {
        boolean certRevoked = certificates.findById(measurement.certificateId())
                .map(Certificate::revoked)
                .orElse(true);
        boolean latest = measurement.revision() == latestRevision;
        return DtoMapper.toResponse(measurement, latest, latestRevision, certRevoked,
                releases.findByMeasurementId(measurement.id()));
    }
}
