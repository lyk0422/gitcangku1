package com.example.starter.calibration.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.GateStatusResponse;
import com.example.starter.calibration.api.dto.PendingReviewResponse;
import com.example.starter.calibration.api.dto.ReviewResponse;
import com.example.starter.calibration.api.dto.SubmitReviewRequest;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.PeerReview;
import com.example.starter.calibration.model.ReviewConclusion;
import com.example.starter.calibration.model.ReviewState;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.PeerReviewRepository;
import com.example.starter.calibration.repo.ReviewRequestRepository;

/**
 * 同行复核服务：提交复核（含版本校验、独立性校验、结论门禁、幂等重放）、
 * 复核历史、待复核清单与放行门禁状态查询。
 */
@Service
public class ReviewService {

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final PeerReviewRepository reviews;
    private final ReviewRequestRepository requests;
    private final TransactionTemplate transactions;

    public ReviewService(MeasurementRepository measurements,
                         CertificateRepository certificates,
                         PeerReviewRepository reviews,
                         ReviewRequestRepository requests,
                         PlatformTransactionManager transactionManager) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.reviews = reviews;
        this.requests = requests;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 提交同行复核。
     *
     * <p>单事务内：先以 requestId 占用幂等请求行（并发同键按该行串行裁决），再在 head/证书行锁内
     * 重新校验测量当前版本、证书仍有效、复核人非提交人。版本变化时写入一条 STALE 复核并提交，
     * 返回 410；该 STALE 记录不得用于放行。PASS 使该版本满足复核门禁；RETURN 将测量转回待修订。
     * 同一版本最多一条有效 PASS、一条有效 RETURN，重复同类有效复核 409。
     *
     * <p>requestId 同键同参重放首次结果，异参 409；业务失败随事务回滚，连占用行一起回滚，故不占键。
     */
    public ReviewOutcome submit(SubmitReviewRequest request, String reviewerHeader, String requestIdHeader) {
        String actor = Inputs.requireText(reviewerHeader, "X-Actor-Id");
        String idemKey = Inputs.requireText(requestIdHeader, "X-Request-Id");
        String measurementKey = Inputs.requireText(request.measurementKey(), "measurementKey");
        String reviewKey = Inputs.requireText(request.reviewKey(), "reviewKey");
        if (request.version() == null || request.version() <= 0) {
            throw ApiException.badRequest("version 必须为正整数");
        }
        int clientVersion = request.version();
        ReviewConclusion conclusion;
        try {
            conclusion = ReviewConclusion.valueOf(
                    Inputs.requireText(request.conclusion(), "conclusion").toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("conclusion 必须为 PASS 或 RETURN");
        }
        String comment = Inputs.requireText(request.comment(), "comment");

        String requestHash = fingerprint(measurementKey, reviewKey, clientVersion, conclusion, comment, actor);
        return transactions.execute(status -> doSubmit(
                idemKey, requestHash, measurementKey, reviewKey, clientVersion, conclusion, comment, actor));
    }

    private ReviewOutcome doSubmit(String idemKey, String requestHash, String measurementKey, String reviewKey,
                                   int clientVersion, ReviewConclusion conclusion, String comment, String actor) {
        Instant claimedAt = Instant.now();

        // 先占用幂等请求行：并发同键在此串行；占用行随业务失败一起回滚，故失败不占键。
        if (!claimRequest(idemKey, requestHash, claimedAt)) {
            // 已有提交完成的首次请求：同参重放首次结果，异参 409。
            ReviewRequestRepository.StoredRequest first = requests.find(idemKey).orElseThrow();
            if (!first.requestHash().equals(requestHash)) {
                throw ApiException.conflict("IDEMPOTENCY_PARAM_MISMATCH",
                        "requestId 已用于不同参数的复核请求: " + idemKey);
            }
            return replay(first);
        }

        Measurement measurement = measurements.findByKeyForUpdate(measurementKey)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + measurementKey));

        // 证书行锁：证书撤销与复核按事务提交顺序裁决。
        Certificate certificate = certificates.findByIdForUpdate(measurement.certificateId())
                .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING",
                        "测量关联的证书不存在: " + measurement.certificateId()));

        Instant now = Instant.now();

        // 版本变化优先：固化一条 STALE 复核并提交，返回 410；STALE 不得用于放行。
        if (measurement.version() != clientVersion) {
            PeerReview stale = new PeerReview(
                    0L, reviewKey, measurementKey, clientVersion, measurement.id(),
                    certificate.id(), conclusion, ReviewState.STALE, actor, comment, now);
            long reviewId = insertReview(stale, null);
            requests.complete(idemKey, 410, reviewId);
            return ReviewOutcome.stale("REVIEW_STALE",
                    "测量当前版本已变化: expected=" + clientVersion + ", current=" + measurement.version());
        }

        if (measurement.submittedBy().equals(actor)) {
            throw ApiException.unprocessable("REVIEWER_IS_SUBMITTER",
                    "复核人不能是测量提交人: " + actor);
        }
        if (certificate.revoked()) {
            throw ApiException.unprocessable("CERTIFICATE_REVOKED",
                    "测量关联的证书已撤销，不能复核: " + certificate.id());
        }
        if (measurement.status() == MeasurementStatus.RELEASED) {
            throw ApiException.conflict("ALREADY_RELEASED", "测量已放行，不再受理复核: " + measurementKey);
        }

        PeerReview review = new PeerReview(
                0L, reviewKey, measurementKey, measurement.version(), measurement.id(),
                certificate.id(), conclusion, ReviewState.VALID, actor, comment, now);
        String slot = PeerReviewRepository.validSlot(measurementKey, measurement.version(), conclusion);
        long reviewId = insertReview(review, slot);

        if (conclusion == ReviewConclusion.RETURN && measurement.status() == MeasurementStatus.PENDING) {
            measurements.updateStatus(measurement.id(), MeasurementStatus.RETURNED);
        }

        requests.complete(idemKey, 201, reviewId);
        PeerReview saved = reviews.findById(reviewId).orElseThrow();
        return ReviewOutcome.created(DtoMapper.toResponse(saved));
    }

    /**
     * 占用 requestId。已有已提交占用行时返回 false；并发下若对方事务回滚（占用行消失）则重试占用。
     */
    private boolean claimRequest(String idemKey, String requestHash, Instant at) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                requests.claim(idemKey, requestHash, at);
                return true;
            } catch (DuplicateKeyException occupied) {
                var stored = requests.find(idemKey);
                if (stored.isPresent()) {
                    return false;
                }
                // 占位者回滚：重试一次占用。
            }
        }
        throw ApiException.conflict("IDEMPOTENCY_RACE", "requestId 并发占用冲突: " + idemKey);
    }

    private long insertReview(PeerReview review, String validSlot) {
        try {
            return reviews.insert(review, validSlot);
        } catch (DuplicateKeyException ex) {
            if (reviews.isValidSlotConflict(ex)) {
                throw ApiException.conflict("DUPLICATE_VALID_REVIEW",
                        "该版本已存在有效 " + review.conclusion() + " 复核");
            }
            throw ApiException.conflict("DUPLICATE_REVIEW_KEY", "复核键已存在: " + review.reviewKey());
        }
    }

    private ReviewOutcome replay(ReviewRequestRepository.StoredRequest first) {
        if (first.httpStatus() == 410) {
            return ReviewOutcome.stale("REVIEW_STALE", "测量当前版本已变化（幂等重放首次 410 结果）");
        }
        PeerReview review = reviews.findById(first.reviewId())
                .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_STATE_MISSING", "幂等记录对应的复核缺失"));
        return ReviewOutcome.created(DtoMapper.toResponse(review));
    }

    /**
     * 复核历史：某测量全部版本的复核记录（含 VALID 与 STALE），按版本与时间升序。
     */
    @Transactional(readOnly = true)
    public List<ReviewResponse> history(String measurementKey) {
        String key = Inputs.requireText(measurementKey, "measurementKey");
        if (measurements.findByKey(key).isEmpty()) {
            throw ApiException.notFound("测量不存在: " + key);
        }
        return reviews.findByMeasurementKey(key).stream().map(DtoMapper::toResponse).toList();
    }

    /**
     * 待复核清单：当前版本处于 PENDING/RETURNED 且尚无有效 PASS 复核的测量。
     */
    @Transactional(readOnly = true)
    public List<PendingReviewResponse> pending(String instrumentId) {
        String instrument = instrumentId == null || instrumentId.isBlank() ? null : instrumentId.trim();
        return measurements.findPendingReview(instrument).stream()
                .map(m -> new PendingReviewResponse(m.measurementKey(), m.version(), m.instrumentId(),
                        m.submittedBy(), m.passed(), m.status().name()))
                .toList();
    }

    /**
     * 放行门禁状态：聚合既有判定条件（待放行、合格、证书有效）与新增有效 PASS 复核门禁。
     */
    @Transactional(readOnly = true)
    public GateStatusResponse gate(String measurementKey) {
        String key = Inputs.requireText(measurementKey, "measurementKey");
        Measurement m = measurements.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        Certificate cert = certificates.findById(m.certificateId()).orElse(null);
        boolean certValid = cert != null && !cert.revoked();
        boolean validPass = reviews.findValid(key, m.version(), ReviewConclusion.PASS).isPresent();
        boolean validReturn = reviews.findValid(key, m.version(), ReviewConclusion.RETURN).isPresent();

        List<String> reasons = new ArrayList<>();
        if (m.status() == MeasurementStatus.RELEASED) {
            reasons.add("ALREADY_RELEASED");
        } else if (m.status() == MeasurementStatus.RETURNED) {
            reasons.add("RETURNED_FOR_REVISION");
        }
        if (!m.passed()) {
            reasons.add("NOT_PASSED");
        }
        if (!certValid) {
            reasons.add("CERTIFICATE_REVOKED");
        }
        if (!validPass) {
            reasons.add("REVIEW_GATE_FAILED");
        }
        boolean releasable = reasons.isEmpty();
        return new GateStatusResponse(key, m.version(), m.status().name(), m.certificateId(),
                certValid, m.passed(), validPass, validReturn, releasable, List.copyOf(reasons));
    }

    private static String fingerprint(Object... parts) {
        String joined = String.join("",
                Arrays.stream(parts).map(String::valueOf).toList());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
