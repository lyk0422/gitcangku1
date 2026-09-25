package com.example.starter.calibration.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.GateStatusResponse;
import com.example.starter.calibration.api.dto.PendingReviewItem;
import com.example.starter.calibration.api.dto.ReviewResponse;
import com.example.starter.calibration.api.dto.SubmitReviewRequest;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementReview;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.ReviewConclusion;
import com.example.starter.calibration.model.ReviewStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReviewRepository;
import com.example.starter.calibration.repo.ReviewRequestRepository;

/**
 * 同行复核服务：提交复核（含 requestId 幂等）、复核历史、待复核清单与放行门禁状态查询。
 * 复核记录不可变；测量修订后旧版本复核仅保留历史，不自动迁移。
 */
@Service
public class ReviewService {

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReviewRepository reviews;
    private final ReviewRequestRepository reviewRequests;

    public ReviewService(MeasurementRepository measurements,
                         CertificateRepository certificates,
                         ReviewRepository reviews,
                         ReviewRequestRepository reviewRequests) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.reviews = reviews;
        this.reviewRequests = reviewRequests;
    }

    /** 复核提交结果：response 为复核记录；stale 为 true 表示版本已过期（对应 410）。 */
    public record ReviewOutcome(ReviewResponse response, boolean stale) {
    }

    /**
     * 提交同行复核。复核人须不同于提交人；复核时重新校验测量当前修订版本与关联证书有效性。
     * 版本不一致时记录 STALE 复核并返回 410 语义（stale=true）；同一版本同类有效复核重复返回 409。
     * requestId 同键同参重放首次结果，同键异参 409，失败不占键。
     */
    @Transactional
    public ReviewOutcome submit(String measurementKey, SubmitReviewRequest request, String actor) {
        String key = Inputs.requireText(measurementKey, "measurementKey");
        String reviewKey = Inputs.requireText(request.reviewKey(), "reviewKey");
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        if (request.revision() == null || request.revision() < 1) {
            throw ApiException.badRequest("revision 必须为不小于 1 的整数");
        }
        int revision = request.revision();
        ReviewConclusion conclusion = parseConclusion(request.conclusion());
        String comment = Inputs.requireText(request.comment(), "comment");
        String reviewer = Inputs.requireText(actor, "X-Actor-Id");

        Measurement measurement = measurements.findByKeyForUpdate(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));

        String fingerprint = fingerprint(key, reviewKey, revision, conclusion, comment, reviewer);
        var occupied = reviewRequests.findById(requestId);
        if (occupied.isPresent()) {
            ReviewRequestRepository.ReviewRequest held = occupied.get();
            if (!held.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("REQUEST_ID_CONFLICT",
                        "请求 ID 已被不同参数的请求占用: " + requestId);
            }
            MeasurementReview first = reviews.findByKey(held.reviewKey()).orElseThrow();
            return new ReviewOutcome(DtoMapper.toResponse(first, key, measurement.revision()), false);
        }

        if (measurement.submittedBy().equals(reviewer)) {
            throw ApiException.unprocessable("REVIEWER_IS_SUBMITTER", "复核人不得与测量提交人相同");
        }
        Certificate cert = certificates.findById(measurement.certificateId())
                .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING",
                        "测量关联的证书不存在: " + measurement.certificateId()));
        if (cert.revoked()) {
            throw ApiException.unprocessable("CERTIFICATE_REVOKED", "测量关联的证书已撤销，不能复核");
        }
        if (measurement.status() == MeasurementStatus.RELEASED) {
            throw ApiException.conflict("ALREADY_RELEASED", "测量已放行，不能再复核: " + key);
        }

        if (revision != measurement.revision()) {
            MeasurementReview stale = new MeasurementReview(0L, reviewKey, requestId, measurement.id(),
                    revision, measurement.certificateId(), reviewer, conclusion, comment,
                    ReviewStatus.STALE, Instant.now());
            insertReview(stale);
            return new ReviewOutcome(
                    DtoMapper.toResponse(reviews.findByKey(reviewKey).orElseThrow(),
                            key, measurement.revision()),
                    true);
        }

        if (reviews.findEffective(measurement.id(), revision, conclusion).isPresent()) {
            throw ApiException.conflict("DUPLICATE_REVIEW",
                    "当前版本已存在有效的 " + conclusion + " 复核");
        }

        MeasurementReview review = new MeasurementReview(0L, reviewKey, requestId, measurement.id(),
                revision, measurement.certificateId(), reviewer, conclusion, comment,
                ReviewStatus.VALID, Instant.now());
        insertReview(review);
        if (conclusion == ReviewConclusion.RETURN) {
            measurements.markNeedsRevision(measurement.id());
        }
        try {
            reviewRequests.insert(requestId, fingerprint, reviewKey);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("REQUEST_ID_CONFLICT",
                    "请求 ID 已被其他请求占用: " + requestId);
        }
        MeasurementReview saved = reviews.findByKey(reviewKey).orElseThrow();
        return new ReviewOutcome(DtoMapper.toResponse(saved, key, measurement.revision()), false);
    }

    /**
     * 复核历史：包含全部版本与 STALE 记录；测量不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<ReviewResponse> history(String measurementKey) {
        Measurement measurement = measurements.findByKey(measurementKey)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + measurementKey));
        return reviews.findByMeasurementId(measurement.id()).stream()
                .map(r -> DtoMapper.toResponse(r, measurement.measurementKey(), measurement.revision()))
                .toList();
    }

    /**
     * 待复核清单：待放行且当前修订版本尚无有效 PASS 复核的测量。
     */
    @Transactional(readOnly = true)
    public List<PendingReviewItem> pending() {
        return measurements.findPendingReview().stream()
                .map(DtoMapper::toPendingItem)
                .toList();
    }

    /**
     * 放行门禁状态：既有判定条件与复核门禁的当前评估；测量不存在返回 404。
     */
    @Transactional(readOnly = true)
    public GateStatusResponse gateStatus(String measurementKey) {
        Measurement measurement = measurements.findByKey(measurementKey)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + measurementKey));
        boolean certRevoked = certificates.findById(measurement.certificateId())
                .map(Certificate::revoked)
                .orElse(true);
        List<MeasurementReview> history = reviews.findByMeasurementId(measurement.id());
        List<String> reasons = ReleaseGate.evaluate(measurement, certRevoked, history);
        String effectivePass = history.stream()
                .filter(r -> r.conclusion() == ReviewConclusion.PASS
                        && r.status() == ReviewStatus.VALID
                        && r.measurementRevision() == measurement.revision())
                .map(MeasurementReview::reviewKey)
                .findFirst()
                .orElse(null);
        return new GateStatusResponse(measurement.measurementKey(), measurement.revision(),
                measurement.status().name(), measurement.passed(), certRevoked,
                effectivePass, reasons.isEmpty(), reasons);
    }

    private void insertReview(MeasurementReview review) {
        try {
            reviews.insert(review);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_REVIEW_KEY", "复核键已存在: " + review.reviewKey());
        }
    }

    private static ReviewConclusion parseConclusion(String value) {
        String text = Inputs.requireText(value, "conclusion");
        try {
            return ReviewConclusion.valueOf(text);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("conclusion 必须为 PASS 或 RETURN");
        }
    }

    private static String fingerprint(String measurementKey, String reviewKey, int revision,
                                      ReviewConclusion conclusion, String comment, String reviewer) {
        String canonical = String.join("", measurementKey, "\n", reviewKey, "\n",
                String.valueOf(revision), "\n", conclusion.name(), "\n", comment, "\n", reviewer);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 放行门禁评估：供放行与门禁状态查询共用的纯函数。
     */
    static final class ReleaseGate {

        /** 复核门禁原因：当前版本缺少有效 PASS 复核。 */
        static final String REVIEW_MISSING = "REVIEW_MISSING";
        /** 复核门禁原因：存在 PASS 复核但属于旧版本，已失效。 */
        static final String REVIEW_STALE = "REVIEW_STALE";

        private ReleaseGate() {
        }

        /**
         * 评估全部放行条件，返回失败原因码列表（空列表表示可放行）。
         */
        static List<String> evaluate(Measurement measurement, boolean certRevoked,
                                     List<MeasurementReview> history) {
            List<String> reasons = new ArrayList<>();
            if (measurement.status() == MeasurementStatus.RELEASED) {
                reasons.add("ALREADY_RELEASED");
            } else if (measurement.status() != MeasurementStatus.PENDING) {
                reasons.add("NOT_PENDING");
            }
            if (!measurement.passed()) {
                reasons.add("NOT_PASSED");
            }
            if (certRevoked) {
                reasons.add("CERTIFICATE_REVOKED");
            }
            reasons.addAll(evaluateReviewOnly(measurement, history));
            return reasons;
        }

        /**
         * 仅评估复核门禁：当前版本无有效 PASS 复核时返回对应原因，否则返回空列表。
         */
        static List<String> evaluateReviewOnly(Measurement measurement,
                                               List<MeasurementReview> history) {
            boolean effectivePass = history.stream().anyMatch(r ->
                    r.conclusion() == ReviewConclusion.PASS
                            && r.status() == ReviewStatus.VALID
                            && r.measurementRevision() == measurement.revision());
            if (effectivePass) {
                return List.of();
            }
            boolean anyValidPass = history.stream().anyMatch(r ->
                    r.conclusion() == ReviewConclusion.PASS && r.status() == ReviewStatus.VALID);
            return List.of(anyValidPass ? REVIEW_STALE : REVIEW_MISSING);
        }

        /**
         * 是否为复核门禁类原因（触发 422 而非 409）。
         */
        static boolean isReviewReason(String reason) {
            return REVIEW_MISSING.equals(reason) || REVIEW_STALE.equals(reason);
        }
    }
}
