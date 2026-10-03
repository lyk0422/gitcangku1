package com.example.starter.calibration.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.ReviewResponse;
import com.example.starter.calibration.api.dto.SubmitReviewRequest;
import com.example.starter.calibration.model.Batch;
import com.example.starter.calibration.model.BatchReading;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.OutlierRecord;
import com.example.starter.calibration.model.OutlierStatus;
import com.example.starter.calibration.model.ReviewDecision;
import com.example.starter.calibration.model.ReviewRecord;
import com.example.starter.calibration.model.ReviewStatus;
import com.example.starter.calibration.repo.BatchRepository;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.OutlierRepository;
import com.example.starter.calibration.repo.ReadingRepository;
import com.example.starter.calibration.repo.ReviewRepository;

/**
 * 离群复核服务：每条离群记录须由至少两名不同于读数提交人的复核角色给出 ACCEPT 或 REJECT；
 * 两名结论相同才形成有效裁决，结论相反保持待仲裁。复核引用版本过期或证书失效时
 * 记录 STALE 复核并返回 410。REJECT 裁决在同一事务内排除读数并重算批次统计。
 */
@Service
public class OutlierReviewService {

    /** 形成有效裁决所需的一致结论复核人数。 */
    static final int QUORUM = 2;

    /**
     * 复核结果。staleMessage 非 null 表示复核已标记 STALE，应返回 410。
     *
     * @param response     复核响应；STALE 场景为 null
     * @param staleMessage STALE 原因（含引用版本与当前版本的实际值）；非 STALE 为 null
     */
    public record ReviewOutcome(ReviewResponse response, String staleMessage) {

        public boolean stale() {
            return staleMessage != null;
        }
    }

    private final OutlierRepository outliers;
    private final ReviewRepository reviews;
    private final ReadingRepository readings;
    private final BatchRepository batches;
    private final CertificateRepository certificates;
    private final BatchService batchService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public OutlierReviewService(OutlierRepository outliers, ReviewRepository reviews,
                                ReadingRepository readings, BatchRepository batches,
                                CertificateRepository certificates, BatchService batchService,
                                IdempotencyService idempotency, Clock clock) {
        this.outliers = outliers;
        this.reviews = reviews;
        this.readings = readings;
        this.batches = batches;
        this.certificates = certificates;
        this.batchService = batchService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 提交复核。版本过期或证书失效返回 410 并记录 STALE 复核（不占用 requestId）；
     * 离群记录不存在 404；已裁决 409；复核人为读数提交人 409；同一复核人重复有效复核 409。
     */
    @Transactional
    public ReviewOutcome review(long outlierId, SubmitReviewRequest request) {
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
        int batchRevision = request.batchRevision();
        int certificateVersion = request.certificateVersion();
        String fingerprint = String.join("|", String.valueOf(outlierId), reviewer, decision.name(),
                String.valueOf(reason), String.valueOf(batchRevision), String.valueOf(certificateVersion));

        AtomicReference<String> staleMessage = new AtomicReference<>();
        ReviewResponse response = idempotency.execute(request.requestId(), fingerprint,
                ReviewResponse.class, () -> {
                    ReviewOutcome outcome = doReview(outlierId, reviewer, decision, reason,
                            batchRevision, certificateVersion);
                    if (outcome.stale()) {
                        staleMessage.set(outcome.staleMessage());
                        return null;
                    }
                    return outcome.response();
                });
        if (response == null) {
            return new ReviewOutcome(null, staleMessage.get());
        }
        return new ReviewOutcome(response, null);
    }

    private ReviewOutcome doReview(long outlierId, String reviewer, ReviewDecision decision,
                                   String reason, int batchRevision, int certificateVersion) {
        Instant now = Instant.now(clock);
        OutlierRecord outlier = outliers.findById(outlierId)
                .orElseThrow(() -> ApiException.notFound("离群记录不存在: " + outlierId));
        BatchReading reading = readings.findById(outlier.readingId())
                .orElseThrow(() -> ApiException.notFound("离群读数不存在: " + outlier.readingId()));
        Batch batch = batches.findByIdForUpdate(outlier.batchId())
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + outlier.batchId()));
        Certificate cert = certificates.findById(reading.certificateId())
                .orElseThrow(() -> ApiException.notFound("证书不存在: " + reading.certificateId()));

        List<String> staleReasons = new ArrayList<>();
        if (batchRevision != batch.revision()) {
            staleReasons.add("测量修订版本已变更: 引用 " + batchRevision + "，当前 " + batch.revision());
        }
        if (certificateVersion != cert.version()) {
            staleReasons.add("证书版本已变更: 引用 " + certificateVersion + "，当前 " + cert.version());
        }
        if (cert.revoked()) {
            staleReasons.add("证书已失效（已撤销）: " + cert.id());
        }
        if (!staleReasons.isEmpty()) {
            reviews.insertReview(new ReviewRecord(0L, outlierId, reviewer, decision, reason,
                    batchRevision, cert.id(), certificateVersion, ReviewStatus.STALE, now));
            return new ReviewOutcome(null, String.join("；", staleReasons));
        }

        if (outlier.status() != OutlierStatus.PENDING) {
            throw ApiException.conflict("OUTLIER_NOT_PENDING", "离群记录已裁决: " + outlierId);
        }
        if (reading.submittedBy().equals(reviewer)) {
            throw ApiException.conflict("REVIEWER_IS_SUBMITTER", "复核人不能是读数提交人: " + reviewer);
        }
        List<ReviewRecord> validReviews = reviews.findValidReviewsByOutlierId(outlierId);
        if (validReviews.stream().anyMatch(r -> r.reviewer().equals(reviewer))) {
            throw ApiException.conflict("DUPLICATE_REVIEW", "复核人已提交过有效复核: " + reviewer);
        }

        ReviewRecord review = new ReviewRecord(0L, outlierId, reviewer, decision, reason,
                batchRevision, cert.id(), certificateVersion, ReviewStatus.VALID, now);
        long reviewId = reviews.insertReview(review);

        List<ReviewRecord> all = new ArrayList<>(validReviews);
        all.add(review);
        long accepts = all.stream().filter(r -> r.decision() == ReviewDecision.ACCEPT).count();
        long rejects = all.stream().filter(r -> r.decision() == ReviewDecision.REJECT).count();
        ReviewDecision adjudicated = null;
        if (accepts >= QUORUM) {
            adjudicated = ReviewDecision.ACCEPT;
        } else if (rejects >= QUORUM) {
            adjudicated = ReviewDecision.REJECT;
        }

        OutlierStatus newStatus = outlier.status();
        if (adjudicated != null) {
            newStatus = adjudicated == ReviewDecision.ACCEPT
                    ? OutlierStatus.ACCEPTED : OutlierStatus.REJECTED;
            outliers.markAdjudicated(outlierId, newStatus, now);
            reviews.insertAdjudication(outlierId, adjudicated, now);
            if (adjudicated == ReviewDecision.REJECT) {
                readings.markExcluded(reading.id());
                int newRevision = batch.revision() + 1;
                batches.bumpRevision(batch.id(), newRevision);
                batchService.recordStats(batch.id(), newRevision, "ADJUDICATION");
            }
        }

        ReviewResponse response = new ReviewResponse(reviewId, outlierId, reviewer, decision.name(),
                reason, batchRevision, certificateVersion, ReviewStatus.VALID.name(), newStatus.name(),
                adjudicated == null ? null : adjudicated.name(), now);
        return new ReviewOutcome(response, null);
    }

    private static ReviewDecision parseDecision(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("decision 不能为空，必须为 ACCEPT 或 REJECT");
        }
        try {
            return ReviewDecision.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("decision 必须为 ACCEPT 或 REJECT");
        }
    }
}