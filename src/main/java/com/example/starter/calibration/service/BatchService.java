package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.BatchDetailResponse;
import com.example.starter.calibration.api.dto.BatchReleaseResponse;
import com.example.starter.calibration.api.dto.BatchResponse;
import com.example.starter.calibration.api.dto.CreateBatchRequest;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.OutlierResponse;
import com.example.starter.calibration.api.dto.ReviewOutlierRequest;
import com.example.starter.calibration.api.dto.RevisionResponse;
import com.example.starter.calibration.api.dto.ReviseBatchRequest;
import com.example.starter.calibration.api.dto.SubmitBatchReadingRequest;
import com.example.starter.calibration.model.BatchEventType;
import com.example.starter.calibration.model.BatchStatus;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementBatch;
import com.example.starter.calibration.model.MeasurementRevision;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.OutlierRecord;
import com.example.starter.calibration.model.OutlierReview;
import com.example.starter.calibration.model.OutlierStatus;
import com.example.starter.calibration.model.ReviewDecision;
import com.example.starter.calibration.repo.BatchRepository;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.OutlierRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 测量批次服务：批次/修订创建、批次读数提交与自动离群检测、两人复核裁决、
 * REJECT 同事务排除与统计重算、一致读放行、明细/历史/诊断查询。
 *
 * <p>并发口径：所有裁决类操作先锁批次行（FOR UPDATE），复核额外先锁离群行，
 * 放行在批次锁下对当前修订活动读数与证书加行锁，保证“放行读取一致的离群与复核集合”。
 */
@Service
public class BatchService {

    private final BatchRepository batches;
    private final MeasurementRepository measurements;
    private final OutlierRepository outliers;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final CalibrationClock clock;

    public BatchService(BatchRepository batches,
                        MeasurementRepository measurements,
                        OutlierRepository outliers,
                        CertificateRepository certificates,
                        ReleaseRepository releases,
                        CalibrationClock clock) {
        this.batches = batches;
        this.measurements = measurements;
        this.outliers = outliers;
        this.certificates = certificates;
        this.releases = releases;
        this.clock = clock;
    }

    /** 业务动作的 HTTP 结果（允许 410 STALE 作为“已落盘但语义失效”的正常提交结果）。 */
    public record ActionOutcome(int statusCode, Object body) {

        static ActionOutcome ok(Object body) {
            return new ActionOutcome(HttpStatus.OK.value(), body);
        }

        static ActionOutcome created(Object body) {
            return new ActionOutcome(HttpStatus.CREATED.value(), body);
        }

        static ActionOutcome gone(Object body) {
            return new ActionOutcome(HttpStatus.GONE.value(), body);
        }
    }

    /**
     * 创建批次及首版测量修订（目标值/允许偏差）。
     */
    @Transactional
    public ActionOutcome createBatch(CreateBatchRequest request) {
        String batchKey = Inputs.requireText(request.batchKey(), "batchKey");
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        BigDecimal target = Inputs.requireDecimal(request.targetValue(), "targetValue");
        BigDecimal tolerance = requireTolerance(request.tolerance());
        String createdBy = Inputs.requireText(request.createdBy(), "createdBy");
        Instant now = clock.now();

        long batchId;
        try {
            batchId = batches.insertBatch(batchKey, instrumentId, createdBy, now);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_BATCH_KEY", "批次键已存在: " + batchKey);
        }
        MeasurementRevision revision = new MeasurementRevision(0L, batchId, 1, target, tolerance,
                null, createdBy, now, null);
        batches.insertRevision(batchId, 1, target, tolerance, null, createdBy, now);
        batches.appendEvent(batchId, 1, BatchEventType.REVISION_CREATED.name(), createdBy,
                EventPayloads.toJson(EventPayloads.revisionCreated(revision)), now);
        return ActionOutcome.created(toBatchResponse(
                new MeasurementBatch(batchId, batchKey, instrumentId, 1, BatchStatus.OPEN, createdBy, now)));
    }

    /**
     * 向当前修订提交读数。当前修订已放行时拒绝（须先开后续修订）；
     * 读数（按未舍入计算值）超出 [目标-偏差, 目标+偏差] 时同事务生成离群记录并写检测事件。
     */
    @Transactional
    public MeasurementResponse submitReading(String batchKey, SubmitBatchReadingRequest request) {
        String key = Inputs.requireText(request.measurementKey(), "measurementKey");
        Instant measuredAt = Inputs.requireInstant(request.measuredAt(), "measuredAt");
        BigDecimal reading = Inputs.requireDecimal(request.reading(), "reading");
        String submittedBy = Inputs.requireText(request.submittedBy(), "submittedBy");

        MeasurementBatch batch = batches.findByKeyForUpdate(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        if (batch.status() == BatchStatus.RELEASED) {
            throw ApiException.conflict("BATCH_REVISION_RELEASED",
                    "当前修订已放行，不能再写入读数；请先创建后续修订版本: " + batchKey);
        }
        MeasurementRevision revision = batches.findRevisionByNo(batch.id(), batch.currentRevisionNo())
                .orElseThrow(() -> ApiException.notFound("当前修订不存在"));

        Certificate cert = certificates.findMatching(batch.instrumentId(), measuredAt)
                .orElseThrow(() -> ApiException.unprocessable(
                        "测量时刻无匹配的有效证书: instrument=" + batch.instrumentId()));

        BigDecimal computed = cert.a().multiply(reading).add(cert.b());
        BigDecimal lower = revision.lowerLimit();
        BigDecimal upper = revision.upperLimit();
        boolean within = computed.compareTo(lower) >= 0 && computed.compareTo(upper) <= 0;
        Instant now = clock.now();

        Measurement measurement = new Measurement(
                0L, key, batch.instrumentId(), measuredAt, reading, lower, upper, submittedBy,
                cert.id(), computed, within, MeasurementStatus.PENDING,
                batch.id(), revision.revisionNo(), false, null, null, now);
        long measurementId;
        try {
            measurementId = measurements.insert(measurement);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY", "测量键已存在: " + key);
        }

        if (!within) {
            BigDecimal deviation = computed.subtract(revision.targetValue());
            BigDecimal excess = deviation.abs().subtract(revision.tolerance());
            String outlierKey = "O-" + UUID.randomUUID().toString().replace("-", "");
            OutlierRecord outlier = new OutlierRecord(
                    0L, outlierKey, batch.id(), revision.revisionNo(), measurementId,
                    revision.targetValue(), revision.tolerance(), lower, upper,
                    reading, computed, deviation, excess, submittedBy, OutlierStatus.PENDING, now, null);
            outliers.insert(outlier);
            batches.appendEvent(batch.id(), revision.revisionNo(),
                    BatchEventType.OUTLIER_DETECTED.name(), submittedBy,
                    EventPayloads.toJson(EventPayloads.outlierDetected(outlier, key)), now);
        }
        return toMeasurementResponse(measurements.findByKey(key).orElseThrow());
    }

    /**
     * 创建后续测量修订。仅当前修订已放行时允许；旧修订与放行快照冻结不改写。
     */
    @Transactional
    public RevisionResponse revise(String batchKey, ReviseBatchRequest request) {
        BigDecimal target = Inputs.requireDecimal(request.targetValue(), "targetValue");
        BigDecimal tolerance = requireTolerance(request.tolerance());
        String reason = Inputs.requireText(request.reason(), "reason");
        String createdBy = Inputs.requireText(request.createdBy(), "createdBy");

        MeasurementBatch batch = batches.findByKeyForUpdate(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        if (batch.status() != BatchStatus.RELEASED) {
            throw ApiException.conflict("BATCH_NOT_RELEASED",
                    "仅已放行修订之后允许创建后续修订；当前修订尚未放行: " + batchKey);
        }
        int newRevisionNo = batch.currentRevisionNo() + 1;
        Instant now = clock.now();
        MeasurementRevision revision = new MeasurementRevision(0L, batch.id(), newRevisionNo,
                target, tolerance, reason, createdBy, now, null);
        batches.insertRevision(batch.id(), newRevisionNo, target, tolerance, reason, createdBy, now);
        batches.advanceRevision(batch.id(), newRevisionNo);
        batches.appendEvent(batch.id(), newRevisionNo, BatchEventType.REVISION_CREATED.name(), createdBy,
                EventPayloads.toJson(EventPayloads.revisionCreated(revision)), now);
        return DtoMapper.toResponse(revision);
    }

    /**
     * 提交一条离群复核。
     * 复核必须引用当前测量修订版本与有效证书版本；版本已变更或证书失效时复核落盘为 STALE，
     * 返回 410 且不计入裁决。两人同议成裁；REJECT 成裁的同一事务排除读数并重算统计。
     */
    @Transactional
    public ActionOutcome review(String outlierKey, String actorHeader, ReviewOutlierRequest request) {
        String reviewer = Inputs.requireText(actorHeader, "X-Actor-Id");
        ReviewDecision decision = requireDecision(request.decision());
        String reason = Inputs.requireText(request.reason(), "reason");

        // 统一加锁顺序：先批次行，再离群行/读数行/证书行，避免与放行等操作形成死锁环。
        OutlierRecord lookedUp = outliers.findByKey(outlierKey)
                .orElseThrow(() -> ApiException.notFound("离群记录不存在: " + outlierKey));
        MeasurementBatch batch = batches.findByIdForUpdate(lookedUp.batchId()).orElseThrow();
        OutlierRecord outlier = outliers.findByKeyForUpdate(outlierKey)
                .orElseThrow(() -> ApiException.notFound("离群记录不存在: " + outlierKey));
        Measurement measurement = measurements.findByIdForUpdate(outlier.measurementId()).orElseThrow();
        Certificate cert = certificates.findByIdForUpdate(measurement.certificateId())
                .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING",
                        "读数绑定的证书不存在: " + measurement.certificateId()));

        if (outlier.status() != OutlierStatus.PENDING) {
            throw ApiException.conflict("OUTLIER_ALREADY_DECIDED",
                    "离群记录已形成有效裁决，不再接受复核: " + outlierKey);
        }
        if (reviewer.equals(outlier.submittedBy())) {
            throw ApiException.conflict("REVIEWER_IS_SUBMITTER",
                    "复核人不得是该读数的提交人: " + reviewer);
        }
        // 同一复核人只能给出一条“有效”复核；STALE 复核不占名额，允许其用当前版本重新表态。
        for (OutlierReview existing : outliers.findReviews(outlier.id())) {
            if (!existing.stale() && existing.reviewer().equals(reviewer)) {
                throw ApiException.conflict("DUPLICATE_REVIEWER",
                        "同一复核人对同一离群记录只能有效复核一次: " + reviewer);
            }
        }

        boolean revisionChanged = outlier.revisionNo() != batch.currentRevisionNo()
                || (request.measurementRevision() != null
                && request.measurementRevision() != batch.currentRevisionNo());
        boolean certificateInvalid = cert.revoked()
                || (request.certificateId() != null
                && request.certificateId() != measurement.certificateId());
        Instant now = clock.now();

        if (revisionChanged || certificateInvalid) {
            int referencedRevision = request.measurementRevision() != null
                    ? request.measurementRevision() : batch.currentRevisionNo();
            long referencedCert = request.certificateId() != null
                    ? request.certificateId() : measurement.certificateId();
            OutlierReview staleReview = new OutlierReview(
                    0L, outlier.id(), reviewer, decision, reason,
                    referencedRevision, referencedCert, true, now);
            outliers.insertReview(staleReview);
            batches.appendEvent(batch.id(), outlier.revisionNo(), BatchEventType.REVIEW.name(), reviewer,
                    EventPayloads.toJson(EventPayloads.review(outlier, staleReview)), now);
            List<String> staleReasons = new ArrayList<>();
            if (revisionChanged) {
                staleReasons.add("MEASUREMENT_REVISION_CHANGED");
            }
            if (certificateInvalid) {
                staleReasons.add("CERTIFICATE_REVOKED_OR_CHANGED");
            }
            OutlierResponse body = toOutlierResponse(outlier, batch.batchKey(),
                    measurement.measurementKey(), staleReasons);
            return ActionOutcome.gone(body);
        }

        OutlierReview review = new OutlierReview(
                0L, outlier.id(), reviewer, decision, reason,
                batch.currentRevisionNo(), measurement.certificateId(), false, now);
        outliers.insertReview(review);
        batches.appendEvent(batch.id(), outlier.revisionNo(), BatchEventType.REVIEW.name(), reviewer,
                EventPayloads.toJson(EventPayloads.review(outlier, review)), now);

        List<OutlierReview> allReviews = outliers.findReviewsForUpdate(outlier.id());
        OutlierStatus decided = QuorumRules.evaluate(allReviews);
        if (decided != OutlierStatus.PENDING) {
            OutlierRecord decidedOutlier = new OutlierRecord(
                    outlier.id(), outlier.outlierKey(), outlier.batchId(), outlier.revisionNo(),
                    outlier.measurementId(), outlier.targetValue(), outlier.tolerance(),
                    outlier.lowerLimit(), outlier.upperLimit(), outlier.rawReading(),
                    outlier.computedValue(), outlier.deviation(), outlier.excess(),
                    outlier.submittedBy(), decided, outlier.detectedAt(), now);
            outliers.markVerdict(outlier.id(), decided, now);
            List<OutlierReview> active = allReviews.stream().filter(r -> !r.stale()).toList();
            batches.appendEvent(batch.id(), outlier.revisionNo(), BatchEventType.VERDICT.name(), reviewer,
                    EventPayloads.toJson(EventPayloads.verdict(decidedOutlier, active)), now);

            if (decided == OutlierStatus.REJECTED) {
                measurements.markExcluded(measurement.id(), outlier.id(), now);
                Measurement excluded = measurements.findById(measurement.id()).orElseThrow();
                BatchStatistics stats = recomputeLocked(batch, outlier.revisionNo());
                batches.appendEvent(batch.id(), outlier.revisionNo(),
                        BatchEventType.RECOMPUTE.name(), reviewer,
                        EventPayloads.toJson(EventPayloads.recompute(
                                outlier.revisionNo(), stats, List.of(excluded.measurementKey()), now)), now);
            }
        }
        OutlierRecord refreshed = outliers.findById(outlier.id()).orElseThrow();
        return ActionOutcome.ok(toOutlierResponse(refreshed, batch.batchKey(),
                measurement.measurementKey(), List.of()));
    }

    /**
     * 放行批次当前修订。读取一致的读数/离群/复核集合：任一离群未裁决（含待仲裁）则 422 阻断；
     * REJECT 读数已在裁决事务排除；ACCEPT 读数保留并随批放行。
     */
    @Transactional
    public BatchReleaseResponse release(String batchKey, String actorHeader,
                                        List<String> requestedKeys) {
        String releaser = Inputs.requireText(actorHeader, "X-Actor-Id");
        MeasurementBatch batch = batches.findByKeyForUpdate(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        int revisionNo = batch.currentRevisionNo();
        MeasurementRevision revision = batches.findRevisionByNoForUpdate(batch.id(), revisionNo)
                .orElseThrow(() -> ApiException.notFound("当前修订不存在"));
        if (batch.status() == BatchStatus.RELEASED) {
            throw ApiException.conflict("ALREADY_RELEASED", "批次当前修订已放行: " + batchKey);
        }

        List<Measurement> active = measurements.findActiveByBatchRevisionForUpdate(batch.id(), revisionNo);
        List<OutlierRecord> outlierList = outliers.findByBatchRevision(batch.id(), revisionNo);
        BatchStatistics stats = BatchStatistics.compute(
                measurements.findByBatchRevision(batch.id(), revisionNo), outlierList);

        if (active.isEmpty()) {
            throw ApiException.unprocessable("NO_ACTIVE_READINGS",
                    "当前修订没有参与放行的读数（实际=0，要求>=1）: " + batchKey);
        }

        // 证书版本失效与角色冲突先于离群裁决前提报告，保证阻断原因可定位；上述集合均在锁内一致读取。
        Map<Long, Certificate> certCache = new LinkedHashMap<>();
        List<String> certReasons = new ArrayList<>();
        List<String> actorReasons = new ArrayList<>();
        for (Measurement m : active) {
            Certificate cert = certCache.computeIfAbsent(m.certificateId(),
                    id -> certificates.findByIdForUpdate(id).orElse(null));
            if (cert == null || cert.revoked()) {
                certReasons.add(m.measurementKey());
            }
            if (m.submittedBy().equals(releaser)) {
                actorReasons.add(m.measurementKey());
            }
        }
        if (!certReasons.isEmpty()) {
            throw ApiException.unprocessable("CERTIFICATE_REVOKED",
                    "存在绑定证书已撤销的读数（实际=%d，要求=0）: %s".formatted(certReasons.size(), certReasons));
        }
        if (!actorReasons.isEmpty()) {
            throw ApiException.unprocessable("RELEASER_IS_SUBMITTER",
                    "放行人不得是批次读数的提交人（实际冲突=%d，要求=0）: %s"
                            .formatted(actorReasons.size(), actorReasons));
        }

        if (!stats.isQuorumSatisfied()) {
            throw ApiException.unprocessable("OUTLIERS_UNRESOLVED",
                    "批次仍有未裁决离群（实际=%d，要求=%d，差额=%d），放行被阻断: %s"
                            .formatted(stats.pendingOutlierCount(),
                                    BatchStatistics.REQUIRED_PENDING_OUTLIERS,
                                    stats.pendingOutlierGap(), batchKey));
        }

        List<String> activeKeys = active.stream().map(Measurement::measurementKey).toList();
        List<String> orderedKeys = resolveRequestedKeys(requestedKeys, activeKeys);

        String releaseBatchId = UUID.randomUUID().toString();
        Instant releasedAt = clock.now();
        List<Measurement> toRelease = orderedKeys.stream()
                .map(key -> active.stream()
                        .filter(m -> m.measurementKey().equals(key)).findFirst().orElseThrow())
                .toList();
        for (Measurement m : toRelease) {
            measurements.markReleased(m.id());
            releases.insert(releaseBatchId, m.id(), releaser, releasedAt);
        }
        batches.markRevisionReleased(batch.id(), revisionNo, releasedAt);
        batches.markReleased(batch.id());
        batches.appendEvent(batch.id(), revisionNo, BatchEventType.RELEASE.name(), releaser,
                EventPayloads.toJson(EventPayloads.release(
                        revisionNo, releaseBatchId, releaser, releasedAt, toRelease, stats)), releasedAt);

        return new BatchReleaseResponse(batchKey, revisionNo, releaseBatchId, releaser, releasedAt,
                orderedKeys, stats.activeCount());
    }

    /**
     * 批次明细/诊断：当前修订配置、读数（含已排除）、离群与复核、统计（实际/要求/差额）、
     * 全部修订与不可变事件流水。只读，不改变状态。
     */
    @Transactional(readOnly = true)
    public BatchDetailResponse detail(String batchKey) {
        MeasurementBatch batch = batches.findByKey(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        List<MeasurementRevision> revisions = batches.findRevisions(batch.id());
        MeasurementRevision current = revisions.stream()
                .filter(r -> r.revisionNo() == batch.currentRevisionNo()).findFirst().orElseThrow();

        List<Measurement> all = measurements.findByBatchRevision(batch.id(), batch.currentRevisionNo());
        List<OutlierRecord> outlierList = outliers.findByBatchRevision(batch.id(), batch.currentRevisionNo());
        BatchStatistics stats = BatchStatistics.compute(all, outlierList);

        List<MeasurementResponse> readingResponses = all.stream().map(this::toMeasurementResponse).toList();
        List<OutlierResponse> outlierResponses = outlierList.stream()
                .map(o -> toOutlierResponse(o, batch.batchKey(),
                        measurements.findById(o.measurementId()).map(Measurement::measurementKey).orElse(null),
                        List.of()))
                .toList();
        List<BatchDetailResponse.BatchEventResponse> events = batches.findEvents(batch.id()).stream()
                .map(DtoMapper::toResponse).toList();

        return new BatchDetailResponse(
                toBatchResponse(batch),
                DtoMapper.toResponse(current),
                revisions.stream().map(DtoMapper::toResponse).toList(),
                readingResponses,
                outlierResponses,
                DtoMapper.toResponse(stats, clock.now()),
                events);
    }

    /**
     * 离群明细（含全部复核与有效同议计数），只读。
     */
    @Transactional(readOnly = true)
    public OutlierResponse outlierDetail(String outlierKey) {
        OutlierRecord outlier = outliers.findByKey(outlierKey)
                .orElseThrow(() -> ApiException.notFound("离群记录不存在: " + outlierKey));
        MeasurementBatch batch = batches.findById(outlier.batchId()).orElseThrow();
        String measurementKey = measurements.findById(outlier.measurementId())
                .map(Measurement::measurementKey).orElse(null);
        return toOutlierResponse(outlier, batch.batchKey(), measurementKey, List.of());
    }

    // ------------------------------------------------------------------
    // 内部辅助（调用方须已持有批次行锁）
    // ------------------------------------------------------------------

    private BatchStatistics recomputeLocked(MeasurementBatch batch, int revisionNo) {
        return BatchStatistics.compute(
                measurements.findByBatchRevision(batch.id(), revisionNo),
                outliers.findByBatchRevision(batch.id(), revisionNo));
    }

    private List<String> resolveRequestedKeys(List<String> requestedKeys, List<String> activeKeys) {
        if (requestedKeys == null || requestedKeys.isEmpty()) {
            return activeKeys;
        }
        List<String> requested = requestedKeys.stream()
                .map(k -> Inputs.requireText(k, "measurementKeys[]")).toList();
        Set<String> requestedSet = new HashSet<>(requested);
        if (requestedSet.size() != requested.size()) {
            throw ApiException.badRequest("放行请求包含重复测量键");
        }
        Set<String> activeSet = new HashSet<>(activeKeys);
        if (!requestedSet.equals(activeSet)) {
            List<String> unknown = requested.stream().filter(k -> !activeSet.contains(k)).toList();
            List<String> missing = activeKeys.stream().filter(k -> !requestedSet.contains(k)).toList();
            throw ApiException.badRequest(
                    "放行键集合必须等于当前修订全部活动读数（未知/已排除=%s，遗漏=%s，实际活动=%d）"
                            .formatted(unknown, missing, activeKeys.size()));
        }
        return activeKeys;
    }

    private BigDecimal requireTolerance(String value) {
        BigDecimal tolerance = Inputs.requireDecimal(value, "tolerance");
        if (tolerance.signum() < 0) {
            throw ApiException.badRequest("tolerance 不能为负数");
        }
        return tolerance;
    }

    private ReviewDecision requireDecision(String value) {
        String text = Inputs.requireText(value, "decision").toUpperCase(java.util.Locale.ROOT);
        try {
            return ReviewDecision.valueOf(text);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("decision 必须为 ACCEPT 或 REJECT");
        }
    }

    private BatchResponse toBatchResponse(MeasurementBatch batch) {
        return DtoMapper.toResponse(batch);
    }

    private MeasurementResponse toMeasurementResponse(Measurement measurement) {
        boolean certRevoked = certificates.findById(measurement.certificateId())
                .map(Certificate::revoked).orElse(true);
        return DtoMapper.toResponse(measurement, certRevoked,
                releases.findByMeasurementId(measurement.id()));
    }

    private OutlierResponse toOutlierResponse(OutlierRecord outlier, String batchKey,
                                              String measurementKey, List<String> staleReasons) {
        List<OutlierReview> reviews = outliers.findReviews(outlier.id());
        return DtoMapper.toResponse(outlier, batchKey, measurementKey, staleReasons, reviews);
    }
}
