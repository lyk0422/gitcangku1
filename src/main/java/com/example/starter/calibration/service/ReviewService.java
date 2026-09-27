package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.StaleReviewException;
import com.example.starter.calibration.api.dto.ReviewRequest;
import com.example.starter.calibration.api.dto.ReviewResponse;
import com.example.starter.calibration.model.BatchReading;
import com.example.starter.calibration.model.BatchStatsRecord;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.MeasurementBatch;
import com.example.starter.calibration.model.OutlierRecord;
import com.example.starter.calibration.model.OutlierReview;
import com.example.starter.calibration.model.OutlierStatus;
import com.example.starter.calibration.model.ReviewDecision;
import com.example.starter.calibration.repo.BatchRepository;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.OutlierRepository;
import com.example.starter.calibration.repo.ReadingRepository;
import com.example.starter.calibration.repo.ReviewRepository;
import com.example.starter.calibration.repo.StatsRepository;
import com.example.starter.calibration.repo.VerdictRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 离群复核服务：法定人数裁决（至少两名不同于提交人的复核人结论一致）、
 * 版本失效留痕（410/STALE）、REJECT 同事务排除读数并重算统计。
 */
@Service
public class ReviewService {

    /** 幂等作用域：离群复核。 */
    static final String SCOPE = "review";

    /** 裁决法定人数：同一结论达到该数量即形成有效裁决。 */
    static final int QUORUM = 2;

    private final OutlierRepository outliers;
    private final ReviewRepository reviews;
    private final VerdictRepository verdicts;
    private final ReadingRepository readings;
    private final BatchRepository batches;
    private final CertificateRepository certificates;
    private final StatsRepository stats;
    private final IdempotencyService idempotency;
    private final StaleReviewRecorder staleRecorder;
    private final ObjectMapper objectMapper;

    public ReviewService(OutlierRepository outliers, ReviewRepository reviews,
                         VerdictRepository verdicts, ReadingRepository readings,
                         BatchRepository batches, CertificateRepository certificates,
                         StatsRepository stats, IdempotencyService idempotency,
                         StaleReviewRecorder staleRecorder, ObjectMapper objectMapper) {
        this.outliers = outliers;
        this.reviews = reviews;
        this.verdicts = verdicts;
        this.readings = readings;
        this.batches = batches;
        this.certificates = certificates;
        this.stats = stats;
        this.idempotency = idempotency;
        this.staleRecorder = staleRecorder;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交复核。版本已变更或证书失效时写入 STALE 留痕并返回 410；
     * 同一结论达到法定人数即形成裁决；REJECT 裁决在同一事务内排除读数并重算统计。
     */
    @Transactional
    public IdempotentResponse review(long outlierId, ReviewRequest request) {
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        String reviewer = Inputs.requireText(request.reviewer(), "reviewer");
        ReviewDecision decision = parseDecision(request.decision());
        String reason = Inputs.requireText(request.reason(), "reason");
        if (request.batchRevision() == null) {
            throw ApiException.badRequest("batchRevision 不能为空");
        }
        if (request.certificateId() == null) {
            throw ApiException.badRequest("certificateId 不能为空");
        }
        int batchRevision = request.batchRevision();
        long certificateId = request.certificateId();
        String fingerprint = String.join("|", reviewer, decision.name(), reason,
                String.valueOf(batchRevision), String.valueOf(certificateId));

        OutlierRecord lockedOutlier = outliers.findById(outlierId)
                .orElseThrow(() -> ApiException.notFound("离群记录不存在: " + outlierId));
        MeasurementBatch batch = batches.findByIdForUpdate(lockedOutlier.batchId())
                .orElseThrow(() -> ApiException.notFound("离群记录所属批次不存在: " + lockedOutlier.batchId()));
        // 批次行锁串行化全部复核：锁获取后重新读取，确保基于前序事务已提交的最新裁决状态判断。
        OutlierRecord outlier = outliers.findById(outlierId).orElseThrow();

        var replay = idempotency.replay(SCOPE, requestId, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        BatchReading reading = readings.findById(outlier.readingId())
                .orElseThrow(() -> ApiException.notFound("离群读数不存在: " + outlier.readingId()));

        String staleCause = staleCause(batch, reading, batchRevision, certificateId);
        if (staleCause != null) {
            staleRecorder.record(outlierId, requestId, reviewer, decision, reason,
                    batchRevision, certificateId);
            throw new StaleReviewException(staleCause);
        }

        if (reading.submittedBy().equals(reviewer)) {
            throw ApiException.forbidden("REVIEWER_IS_SUBMITTER", "复核人不能与读数提交人相同: " + reviewer);
        }
        if (outlier.status() != OutlierStatus.PENDING) {
            throw ApiException.conflict("VERDICT_FINAL", "离群记录已完成裁决，复核不可再变更: " + outlierId);
        }
        boolean alreadyReviewed = reviews.findValidByOutlierId(outlierId).stream()
                .anyMatch(r -> r.reviewer().equals(reviewer));
        if (alreadyReviewed) {
            throw ApiException.conflict("DUPLICATE_REVIEW", "复核人已提交过有效复核: " + reviewer);
        }

        Instant now = Instant.now();
        long reviewId = reviews.insert(new OutlierReview(0L, outlierId, requestId, reviewer,
                decision, reason, batchRevision, certificateId, false, now));

        OutlierStatus newStatus = decide(outlierId);
        if (newStatus != OutlierStatus.PENDING) {
            verdicts.insert(outlierId,
                    newStatus == OutlierStatus.ACCEPTED ? ReviewDecision.ACCEPT : ReviewDecision.REJECT, now);
            outliers.applyVerdict(outlierId, newStatus, now);
            if (newStatus == OutlierStatus.REJECTED) {
                readings.markExcluded(reading.id());
                BatchStats.Values current = BatchStats.compute(
                        readings.findByBatchAndRevision(batch.id(), batch.revision()));
                stats.insert(new BatchStatsRecord(0L, batch.id(), batch.revision(), "READING_EXCLUDED",
                        current.includedCount(), current.excludedCount(),
                        current.mean(), current.min(), current.max(), now));
            }
        }

        ReviewResponse response = new ReviewResponse(reviewId, outlierId, requestId, reviewer,
                decision.name(), reason, batchRevision, certificateId, false, newStatus.name(), now);
        return idempotency.record(SCOPE, requestId, fingerprint, 201, writeJson(response));
    }

    /**
     * 失效原因：修订版本不一致、证书版本不一致或证书已撤销/不存在；未失效返回 null。
     */
    private String staleCause(MeasurementBatch batch, BatchReading reading,
                              int batchRevision, long certificateId) {
        if (batch.revision() != batchRevision) {
            return "复核引用的测量修订版本已变更: 引用=" + batchRevision + ", 当前=" + batch.revision();
        }
        if (reading.certificateId() != certificateId) {
            return "复核引用的证书版本已变更: 引用=" + certificateId + ", 当前=" + reading.certificateId();
        }
        Certificate cert = certificates.findById(certificateId).orElse(null);
        if (cert == null || cert.revoked()) {
            return "复核引用的证书已失效: " + certificateId;
        }
        return null;
    }

    /**
     * 裁决：统计有效复核，任一结论达到法定人数即形成对应裁决，否则保持待仲裁。
     */
    private OutlierStatus decide(long outlierId) {
        List<OutlierReview> valid = reviews.findValidByOutlierId(outlierId);
        long accepts = valid.stream().filter(r -> r.decision() == ReviewDecision.ACCEPT).count();
        long rejects = valid.size() - accepts;
        if (accepts >= QUORUM) {
            return OutlierStatus.ACCEPTED;
        }
        if (rejects >= QUORUM) {
            return OutlierStatus.REJECTED;
        }
        return OutlierStatus.PENDING;
    }

    private static ReviewDecision parseDecision(String value) {
        String text = Inputs.requireText(value, "decision");
        try {
            return ReviewDecision.valueOf(text.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("decision 必须为 ACCEPT 或 REJECT");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("响应序列化失败", ex);
        }
    }
}
