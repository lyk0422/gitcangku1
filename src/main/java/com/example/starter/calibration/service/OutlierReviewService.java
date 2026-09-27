package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.OutlierResponse;
import com.example.starter.calibration.api.dto.ReviewResponse;
import com.example.starter.calibration.api.dto.SubmitReviewRequest;
import com.example.starter.calibration.model.BatchStats;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.MeasurementBatch;
import com.example.starter.calibration.model.OutlierRecord;
import com.example.starter.calibration.model.OutlierReview;
import com.example.starter.calibration.model.OutlierStatus;
import com.example.starter.calibration.model.ReviewDecision;
import com.example.starter.calibration.model.ReviewStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.OutlierBatchRepository;
import com.example.starter.calibration.repo.OutlierRepository;

/**
 * 离群复核服务：法定人数裁决（两名不同于提交人的复核人结论一致方形成有效裁决）、
 * 版本失效复核标记 STALE（410）、REJECT 裁决同事务排除读数并重算统计、离群明细查询。
 */
@Service
public class OutlierReviewService {

    /** 形成有效裁决所需的相同结论复核票数（法定人数）。 */
    static final int REQUIRED_QUORUM = 2;

    private final OutlierRepository outliers;
    private final OutlierBatchRepository batches;
    private final CertificateRepository certificates;
    private final BatchStatsService stats;
    private final IdempotencyService idempotency;

    public OutlierReviewService(OutlierRepository outliers,
                                OutlierBatchRepository batches,
                                CertificateRepository certificates,
                                BatchStatsService stats,
                                IdempotencyService idempotency) {
        this.outliers = outliers;
        this.batches = batches;
        this.certificates = certificates;
        this.stats = stats;
        this.idempotency = idempotency;
    }

    /**
     * 提交复核：201；参数非法 400；离群记录不存在 404；
     * 复核人=提交人、重复复核、已裁决 409；引用版本已变更或证书失效 410（记录 STALE 复核）。
     */
    public IdempotencyService.StoredResponse review(long outlierId, SubmitReviewRequest request) {
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        return idempotency.execute("OUTLIER_REVIEW", requestId, List.of(outlierId, request),
                () -> doReview(outlierId, request));
    }

    /**
     * 离群记录明细：封存证据与复核列表；不存在 404。只读。
     */
    @Transactional(readOnly = true)
    public OutlierResponse detail(long outlierId) {
        OutlierRecord outlier = outliers.findOutlierById(outlierId)
                .orElseThrow(() -> ApiException.notFound("离群记录不存在: " + outlierId));
        return toResponse(outlier);
    }

    /**
     * 批次离群记录列表（按检测顺序升序）；批次不存在 404。只读。
     */
    @Transactional(readOnly = true)
    public List<OutlierResponse> listByBatch(String batchKey) {
        MeasurementBatch batch = batches.findBatchByKey(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return outliers.findOutliersByBatch(batch.id()).stream()
                .map(this::toResponse)
                .toList();
    }

    private IdempotencyService.StoredResponse doReview(long outlierId, SubmitReviewRequest request) {
        String reviewer = Inputs.requireText(request.reviewer(), "reviewer");
        ReviewDecision decision = parseDecision(request.decision());
        String reason = request.reason() == null || request.reason().isBlank()
                ? null : request.reason().trim();
        if (decision == ReviewDecision.ACCEPT && reason == null) {
            throw ApiException.badRequest("ACCEPT 复核必须记录理由 reason");
        }
        if (request.batchRevision() == null) {
            throw ApiException.badRequest("batchRevision 不能为空");
        }
        if (request.certificateId() == null) {
            throw ApiException.badRequest("certificateId 不能为空");
        }

        OutlierRecord outlier = outliers.findOutlierById(outlierId)
                .orElseThrow(() -> ApiException.notFound("离群记录不存在: " + outlierId));
        MeasurementBatch batch = batches.findBatchByIdForUpdate(outlier.batchId())
                .orElseThrow(() -> ApiException.conflict("BATCH_MISSING",
                        "离群记录所属批次不存在: " + outlier.batchId()));
        Certificate cert = certificates.findByIdForUpdate(batch.certificateId())
                .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING",
                        "批次绑定的证书不存在: " + batch.certificateId()));

        boolean stale = batch.revision() != request.batchRevision()
                || batch.certificateId() != request.certificateId()
                || cert.revoked();
        if (stale) {
            // 版本已变更或证书失效：记录 STALE 复核（不可变证据，不计入法定人数），返回 410
            OutlierReview review = new OutlierReview(0L, outlierId, reviewer, decision, reason,
                    request.batchRevision(), request.certificateId(), ReviewStatus.STALE, Instant.now());
            long reviewId = outliers.insertReview(review);
            ReviewResponse response = toReviewResponse(reviewId, outlier, review);
            return new IdempotencyService.StoredResponse(410, idempotency.toJson(response));
        }

        if (outlier.status() != OutlierStatus.PENDING) {
            throw ApiException.conflict("OUTLIER_RESOLVED", "离群记录已裁决: " + outlierId);
        }
        if (outlier.submittedBy().equals(reviewer)) {
            throw ApiException.conflict("REVIEWER_IS_SUBMITTER", "复核人不能与读数提交人相同");
        }
        if (outliers.countEffectiveReviewsBy(outlierId, reviewer) > 0) {
            throw ApiException.conflict("DUPLICATE_REVIEW", "复核人已提交有效复核: " + reviewer);
        }

        OutlierReview review = new OutlierReview(0L, outlierId, reviewer, decision, reason,
                request.batchRevision(), request.certificateId(), ReviewStatus.EFFECTIVE, Instant.now());
        long reviewId = outliers.insertReview(review);

        // 法定人数裁决：任一结论有效票达到 REQUIRED_QUORUM 即形成有效裁决
        int accepts = outliers.countEffectiveReviewsByDecision(outlierId, ReviewDecision.ACCEPT);
        int rejects = outliers.countEffectiveReviewsByDecision(outlierId, ReviewDecision.REJECT);
        OutlierRecord current = outlier;
        if (accepts >= REQUIRED_QUORUM) {
            // ACCEPT：保留读数并记录理由
            outliers.markResolved(outlierId, OutlierStatus.ACCEPTED, ReviewDecision.ACCEPT, Instant.now());
            current = outliers.findOutlierById(outlierId).orElseThrow();
        } else if (rejects >= REQUIRED_QUORUM) {
            // REJECT：同一事务内排除读数并重算批次统计
            Instant resolvedAt = Instant.now();
            outliers.markResolved(outlierId, OutlierStatus.REJECTED, ReviewDecision.REJECT, resolvedAt);
            batches.markReadingExcluded(outlier.readingId(), resolvedAt);
            stats.recomputeAndRecord(batch, BatchStatsService.REASON_EXCLUDE);
            current = outliers.findOutlierById(outlierId).orElseThrow();
        }

        ReviewResponse response = toReviewResponse(reviewId, current, review);
        return new IdempotencyService.StoredResponse(201, idempotency.toJson(response));
    }

    private ReviewResponse toReviewResponse(long reviewId, OutlierRecord outlier, OutlierReview review) {
        int accepts = outliers.countEffectiveReviewsByDecision(outlier.id(), ReviewDecision.ACCEPT);
        int rejects = outliers.countEffectiveReviewsByDecision(outlier.id(), ReviewDecision.REJECT);
        return new ReviewResponse(reviewId, outlier.id(), review.reviewer(), review.decision().name(),
                review.reason(), review.status().name(), review.batchRevision(), review.certificateId(),
                accepts, rejects, REQUIRED_QUORUM,
                outlier.verdict() == null ? null : outlier.verdict().name(),
                outlier.status().name(), review.createdAt());
    }

    private OutlierResponse toResponse(OutlierRecord outlier) {
        String batchKey = batchKeyOf(outlier.batchId());
        List<OutlierReview> reviews = outliers.findReviewsByOutlier(outlier.id());
        int accepts = outliers.countEffectiveReviewsByDecision(outlier.id(), ReviewDecision.ACCEPT);
        int rejects = outliers.countEffectiveReviewsByDecision(outlier.id(), ReviewDecision.REJECT);
        return new OutlierResponse(outlier.id(), batchKey, outlier.readingId(), outlier.batchRevision(),
                outlier.certificateId(), DtoMapper.format(outlier.targetValue()),
                DtoMapper.format(outlier.allowedDeviation()), DtoMapper.format(outlier.readingValue()),
                DtoMapper.format(outlier.deviation()), outlier.submittedBy(), outlier.status().name(),
                outlier.verdict() == null ? null : outlier.verdict().name(),
                accepts, rejects, REQUIRED_QUORUM, outlier.createdAt(), outlier.resolvedAt(),
                reviews.stream()
                        .map(r -> new OutlierResponse.ReviewItem(r.reviewer(), r.decision().name(),
                                r.reason(), r.status().name(), r.batchRevision(), r.certificateId(),
                                r.createdAt()))
                        .toList());
    }

    private String batchKeyOf(long batchId) {
        return batches.findBatchById(batchId)
                .map(MeasurementBatch::batchKey)
                .orElseThrow(() -> ApiException.conflict("BATCH_MISSING", "批次不存在: " + batchId));
    }

    private static ReviewDecision parseDecision(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("decision 不能为空");
        }
        try {
            return ReviewDecision.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("decision 必须为 ACCEPT 或 REJECT");
        }
    }
}
