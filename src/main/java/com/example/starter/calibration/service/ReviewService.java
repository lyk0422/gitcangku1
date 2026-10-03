package com.example.starter.calibration.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.ErrorResponse;
import com.example.starter.calibration.api.dto.ReviewResponse;
import com.example.starter.calibration.api.dto.SubmitReviewRequest;
import com.example.starter.calibration.model.AuditEventType;
import com.example.starter.calibration.model.BatchReading;
import com.example.starter.calibration.model.CalibrationBatch;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.OutlierAudit;
import com.example.starter.calibration.model.OutlierRecord;
import com.example.starter.calibration.model.OutlierReview;
import com.example.starter.calibration.model.OutlierStatus;
import com.example.starter.calibration.model.ReviewDecision;
import com.example.starter.calibration.model.ReviewStatus;
import com.example.starter.calibration.model.StatsTrigger;
import com.example.starter.calibration.repo.BatchRepository;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.EvidenceRepository;
import com.example.starter.calibration.repo.OutlierRepository;
import com.example.starter.calibration.repo.ReadingRepository;

/**
 * 离群复核服务：每条离群记录须至少两名不同于读数提交人的复核人给出一致结论才形成裁决；
 * 结论相反时保持待仲裁。复核引用测量修订版本与证书版本，版本变更或证书失效时落库 STALE 并返回 410。
 * REJECT 裁决在同一事务内排除读数并重算批次统计。
 */
@Service
public class ReviewService {

    /** 形成裁决所需的一致结论数。 */
    static final int REQUIRED_QUORUM = 2;

    private final OutlierRepository outliers;
    private final BatchRepository batches;
    private final ReadingRepository readings;
    private final CertificateRepository certificates;
    private final EvidenceRepository evidence;
    private final StatsService stats;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public ReviewService(OutlierRepository outliers, BatchRepository batches, ReadingRepository readings,
                         CertificateRepository certificates, EvidenceRepository evidence, StatsService stats,
                         IdempotencyService idempotency, Clock clock) {
        this.outliers = outliers;
        this.batches = batches;
        this.readings = readings;
        this.certificates = certificates;
        this.evidence = evidence;
        this.stats = stats;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 提交复核。版本失效或证书失效时落库 STALE 复核并返回 410（结果正常提交，占幂等键）；
     * 离群已裁决 409；复核人即读数提交人 409；同一复核人重复有效复核 409。
     */
    @Transactional
    public ReviewOutcome submit(long outlierId, SubmitReviewRequest request) {
        String reviewer = Inputs.requireText(request.reviewer(), "reviewer");
        ReviewDecision decision = parseDecision(request.decision());
        String reason = request.reason() == null || request.reason().isBlank()
                ? null : request.reason().trim();
        if (decision == ReviewDecision.ACCEPT && reason == null) {
            throw ApiException.badRequest("ACCEPT 复核必须填写理由 reason");
        }
        if (request.batchRevision() == null) {
            throw ApiException.badRequest("batchRevision 不能为空");
        }
        if (request.certificateVersion() == null) {
            throw ApiException.badRequest("certificateVersion 不能为空");
        }
        String requestId = idempotency.begin(request.requestId(), "SUBMIT_REVIEW",
                String.join("|", String.valueOf(outlierId), reviewer, decision.name(),
                        String.valueOf(reason), String.valueOf(request.batchRevision()),
                        String.valueOf(request.certificateVersion())));

        OutlierRecord outlier = outliers.findOutlierById(outlierId)
                .orElseThrow(() -> ApiException.notFound("离群记录不存在: " + outlierId));
        // 批次行锁：复核与读数提交、修订、放行按提交顺序串行化
        CalibrationBatch batch = batches.findByIdForUpdate(outlier.batchId())
                .orElseThrow(() -> ApiException.notFound("离群记录所属批次不存在: " + outlier.batchId()));
        BatchReading reading = readings.findById(outlier.readingId())
                .orElseThrow(() -> ApiException.notFound("离群记录所属读数不存在: " + outlier.readingId()));
        Certificate cert = certificates.findByIdForUpdate(reading.certificateId())
                .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING",
                        "读数关联的证书不存在: " + reading.certificateId()));

        Instant now = Instant.now(clock);
        boolean stale = request.batchRevision() != batch.revision()
                || cert.revoked()
                || request.certificateVersion() != cert.version();
        if (stale) {
            long staleId = outliers.insertReview(new OutlierReview(0L, outlierId, reviewer, decision,
                    reason, request.batchRevision(), reading.certificateId(), request.certificateVersion(),
                    ReviewStatus.STALE, now));
            evidence.insertAudit(new OutlierAudit(0L, batch.id(), outlierId, AuditEventType.REVIEW_STALE,
                    batch.revision(), outlier.targetValue(), outlier.allowedDeviation(),
                    outlier.readingValue(), reviewer, decision.name(),
                    "复核引用的测量修订版本或证书版本已失效", now));
            ErrorResponse error = new ErrorResponse("STALE_REVIEW",
                    "复核引用的测量修订版本已变更或证书已失效，复核已标记为 STALE: id=" + staleId);
            idempotency.complete(requestId, 410, error);
            return ReviewOutcome.stale(error);
        }

        if (outlier.status() != OutlierStatus.PENDING) {
            throw ApiException.conflict("OUTLIER_ALREADY_DECIDED", "离群记录已裁决: " + outlierId);
        }
        if (reviewer.equals(reading.submittedBy())) {
            throw ApiException.conflict("REVIEWER_IS_SUBMITTER", "复核人不能与读数提交人相同: " + reviewer);
        }
        if (outliers.existsValidReviewByReviewer(outlierId, reviewer)) {
            throw ApiException.conflict("DUPLICATE_REVIEW", "复核人已提交过有效复核: " + reviewer);
        }

        long reviewId = outliers.insertReview(new OutlierReview(0L, outlierId, reviewer, decision,
                reason, request.batchRevision(), reading.certificateId(), request.certificateVersion(),
                ReviewStatus.VALID, now));
        evidence.insertAudit(new OutlierAudit(0L, batch.id(), outlierId, AuditEventType.REVIEW_SUBMITTED,
                batch.revision(), outlier.targetValue(), outlier.allowedDeviation(),
                outlier.readingValue(), reviewer, decision.name(), reason, now));

        int acceptCount = outliers.countValidReviews(outlierId, ReviewDecision.ACCEPT);
        int rejectCount = outliers.countValidReviews(outlierId, ReviewDecision.REJECT);
        OutlierStatus outlierStatus = outlier.status();
        if (acceptCount >= REQUIRED_QUORUM) {
            outlierStatus = OutlierStatus.ACCEPTED;
            outliers.updateOutlierStatus(outlierId, OutlierStatus.ACCEPTED, now);
            evidence.insertAudit(new OutlierAudit(0L, batch.id(), outlierId, AuditEventType.VERDICT_ACCEPT,
                    batch.revision(), outlier.targetValue(), outlier.allowedDeviation(),
                    outlier.readingValue(), reviewer, ReviewDecision.ACCEPT.name(),
                    "两名复核人一致 ACCEPT，读数保留", now));
        } else if (rejectCount >= REQUIRED_QUORUM) {
            // REJECT 裁决：同一事务内排除读数并重算批次统计
            outlierStatus = OutlierStatus.REJECTED;
            outliers.updateOutlierStatus(outlierId, OutlierStatus.REJECTED, now);
            readings.markExcluded(reading.id(), now);
            stats.recompute(batch.id(), batch.revision(), StatsTrigger.VERDICT_REJECT);
            evidence.insertAudit(new OutlierAudit(0L, batch.id(), outlierId, AuditEventType.VERDICT_REJECT,
                    batch.revision(), outlier.targetValue(), outlier.allowedDeviation(),
                    outlier.readingValue(), reviewer, ReviewDecision.REJECT.name(),
                    "两名复核人一致 REJECT，读数已排除并重算统计", now));
        }

        ReviewResponse response = new ReviewResponse(reviewId, outlierId, reviewer, decision.name(),
                reason, request.batchRevision(), reading.certificateId(), request.certificateVersion(),
                ReviewStatus.VALID.name(), acceptCount, rejectCount, REQUIRED_QUORUM,
                outlierStatus.name(), now);
        idempotency.complete(requestId, 201, response);
        return ReviewOutcome.ok(response);
    }

    private static ReviewDecision parseDecision(String value) {
        String text = Inputs.requireText(value, "decision");
        try {
            return ReviewDecision.valueOf(text.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("decision 必须为 ACCEPT 或 REJECT");
        }
    }
}