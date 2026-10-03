package com.example.starter.dive.service;

import com.example.starter.dive.domain.Anomaly;
import com.example.starter.dive.domain.DepthPoint;
import com.example.starter.dive.domain.DiveReview;
import com.example.starter.dive.domain.DiveSession;
import com.example.starter.dive.domain.PointUpload;
import com.example.starter.dive.repository.DepthPointRepository;
import com.example.starter.dive.repository.DiveReviewRepository;
import com.example.starter.dive.repository.DiveSessionRepository;
import com.example.starter.dive.repository.PointUploadRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 潜水作业记录与审核业务入口：点位上传（幂等）、上浮审核（幂等、按版本冻结）、
 * 追加勘误点后的复核链，以及原始点/异常/审核版本查询。
 * 追加与审核通过作业行锁串行化，审核在同一事务内读取完整一致的点位集合。
 */
@Service
public class DiveLogService {

    /** 待上传的新点位。 */
    public record NewPoint(Instant recordedAtUtc, BigDecimal depthM) {
    }

    /** 上传结果。 */
    public record UploadResult(long sessionId, int pointVersion, int acceptedCount,
                               int firstSeq, int lastSeq, boolean replayed) {
    }

    /** 审核结果。 */
    public record ReviewResult(long reviewId, DiveReview.ReviewDecision decision, int pointVersion,
                               List<Anomaly> anomalies, boolean replayed) {
    }

    /** 总览：原始点、当前有效审核、全部审核版本（复核链）。 */
    public record OverviewResult(DiveSession session, List<DepthPoint> points,
                                 DiveReview activeReview, List<DiveReview> reviews) {
    }

    private final DiveSessionRepository sessionRepository;
    private final DepthPointRepository pointRepository;
    private final PointUploadRepository uploadRepository;
    private final DiveReviewRepository reviewRepository;
    private final Clock clock;

    public DiveLogService(DiveSessionRepository sessionRepository,
                          DepthPointRepository pointRepository,
                          PointUploadRepository uploadRepository,
                          DiveReviewRepository reviewRepository,
                          Clock clock) {
        this.sessionRepository = sessionRepository;
        this.pointRepository = pointRepository;
        this.uploadRepository = uploadRepository;
        this.reviewRepository = reviewRepository;
        this.clock = clock;
    }

    /** 追加上传点位：同requestId同参重放、异参409；已审核作业自动生成复核记录并令原审核过期。 */
    @Transactional
    public UploadResult uploadPoints(long sessionId, String requestId, List<NewPoint> points) {
        validateRequestId(requestId);
        if (points == null || points.isEmpty()) {
            throw new BadRequestException("点位列表不能为空");
        }
        for (NewPoint point : points) {
            if (point == null || point.recordedAtUtc() == null || point.depthM() == null) {
                throw new BadRequestException("点位的记录时刻与深度不能为空");
            }
        }
        DiveSession session = lockSession(sessionId);
        String payloadHash = hashPayload(sessionId, requestId, points);
        Optional<PointUpload> existing = uploadRepository.findByRequestId(sessionId, requestId);
        if (existing.isPresent()) {
            PointUpload batch = existing.get();
            if (!batch.payloadHash().equals(payloadHash)) {
                throw new ConflictException("requestId已用于不同的点位上传参数：" + requestId);
            }
            return new UploadResult(sessionId, batch.pointVersion(), batch.pointCount(),
                    batch.firstSeq(), batch.lastSeq(), true);
        }
        Instant now = clock.instant();
        int firstSeq = pointRepository.maxSeq(sessionId) + 1;
        int seq = firstSeq;
        for (NewPoint point : points) {
            pointRepository.insert(sessionId, seq, point.recordedAtUtc(), point.depthM(),
                    requestId, now);
            seq++;
        }
        int lastSeq = seq - 1;
        int newVersion = session.pointVersion() + 1;
        sessionRepository.updatePointVersion(sessionId, newVersion);
        reviewRepository.findActive(sessionId).ifPresent(active -> {
            reviewRepository.markExpired(active.id());
            List<Anomaly> anomalies =
                    ReviewEvaluator.evaluate(session, pointRepository.findBySessionId(sessionId));
            DiveReview.ReviewDecision decision = anomalies.isEmpty()
                    ? DiveReview.ReviewDecision.PASSED : DiveReview.ReviewDecision.REJECTED;
            reviewRepository.insert(sessionId, newVersion, DiveReview.ReviewKind.RE_REVIEW,
                    decision, null, active.id(), anomalies, clock.instant());
        });
        uploadRepository.insert(sessionId, requestId, payloadHash, points.size(),
                firstSeq, lastSeq, newVersion, now);
        return new UploadResult(sessionId, newVersion, points.size(), firstSeq, lastSeq, false);
    }

    /** 请求上浮审核：同requestId重放、版本变化409；同一版本最多一条通过决定，失败不占幂等键。 */
    @Transactional
    public ReviewResult requestReview(long sessionId, String requestId) {
        validateRequestId(requestId);
        DiveSession session = lockSession(sessionId);
        int version = session.pointVersion();
        Optional<DiveReview> byIdemKey = reviewRepository.findByIdemKey(sessionId, requestId);
        if (byIdemKey.isPresent()) {
            DiveReview existing = byIdemKey.get();
            if (existing.pointVersion() != version) {
                throw new ConflictException("requestId已用于点位版本" + existing.pointVersion()
                        + "的审核，当前版本为" + version + "：" + requestId);
            }
            return toResult(existing, true);
        }
        Optional<DiveReview> passed = reviewRepository.findActivePassedByVersion(sessionId, version);
        if (passed.isPresent()) {
            return toResult(passed.get(), true);
        }
        List<DepthPoint> points = pointRepository.findBySessionId(sessionId);
        List<Anomaly> anomalies = ReviewEvaluator.evaluate(session, points);
        DiveReview.ReviewDecision decision = anomalies.isEmpty()
                ? DiveReview.ReviewDecision.PASSED : DiveReview.ReviewDecision.REJECTED;
        // 新记录取代当前有效记录：旧记录过期并挂入复核链，任何时刻最多一条有效记录
        Optional<DiveReview> active = reviewRepository.findActive(sessionId);
        Long supersedesId = null;
        if (active.isPresent()) {
            reviewRepository.markExpired(active.get().id());
            supersedesId = active.get().id();
        }
        try {
            long reviewId = reviewRepository.insert(sessionId, version,
                    DiveReview.ReviewKind.INITIAL, decision, requestId, supersedesId, anomalies,
                    clock.instant());
            return new ReviewResult(reviewId, decision, version, anomalies, false);
        } catch (DuplicateKeyException ex) {
            DiveReview concurrent = reviewRepository.findActivePassedByVersion(sessionId, version)
                    .orElseThrow(() -> ex);
            return toResult(concurrent, true);
        }
    }

    /** 查询总览：原始点、当前有效审核及其异常、全部审核版本（复核链）。 */
    @Transactional(readOnly = true)
    public OverviewResult overview(long sessionId) {
        DiveSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("作业不存在：" + sessionId));
        return new OverviewResult(session,
                pointRepository.findBySessionId(sessionId),
                reviewRepository.findActive(sessionId).orElse(null),
                reviewRepository.findBySessionId(sessionId));
    }

    private DiveSession lockSession(long sessionId) {
        return sessionRepository.lockById(sessionId)
                .orElseThrow(() -> new NotFoundException("作业不存在：" + sessionId));
    }

    private static ReviewResult toResult(DiveReview review, boolean replayed) {
        return new ReviewResult(review.id(), review.decision(), review.pointVersion(),
                review.anomalies(), replayed);
    }

    private static void validateRequestId(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            throw new BadRequestException("requestId不能为空且长度不超过64");
        }
    }

    private static String hashPayload(long sessionId, String requestId, List<NewPoint> points) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(sessionId).append('\n').append(requestId);
        for (NewPoint point : points) {
            canonical.append('\n').append(point.recordedAtUtc()).append(',')
                    .append(point.depthM().stripTrailingZeros().toPlainString());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256不可用", ex);
        }
    }
}