package com.example.starter.snow;

import com.example.starter.snow.dto.ConfirmationRequest;
import com.example.starter.snow.dto.ReviewCreateRequest;
import com.example.starter.snow.model.ConfirmationRec;
import com.example.starter.snow.model.ReadingRec;
import com.example.starter.snow.model.ReviewRec;
import com.example.starter.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 解除封闭双人审核：要求最近连续 3 个 UTC 整点新增积雪均不超限，
 * 两名不同审核员先后确认，第二人确认后生成解除版本并开放区域。
 * 审核创建时快照三条读数主键，确认完成前在同一区域行锁内复核快照，
 * 保证与新读数写入竞争时依据一致的三小时读数版本。
 */
@Service
public class UnsealService {

    private final RegionDao regionDao;
    private final ReadingDao readingDao;
    private final ReviewDao reviewDao;
    private final Clock clock;

    public UnsealService(RegionDao regionDao, ReadingDao readingDao, ReviewDao reviewDao, Clock clock) {
        this.regionDao = regionDao;
        this.readingDao = readingDao;
        this.reviewDao = reviewDao;
        this.clock = clock;
    }

    /**
     * 发起解除审核：区域必须封闭（否则 409），最近 3 个整点读数齐全（否则 422）
     * 且新增积雪均不超过阈值（否则 422）。
     */
    @Transactional
    public Map<String, Object> createReview(String regionId, ReviewCreateRequest req) {
        Instant now = clock.instant();
        regionDao.ensureRegion(regionId, now);
        String status = regionDao.lockRegionStatus(regionId);

        Optional<ReviewRec> existing = reviewDao.findByRequestId(req.requestId());
        if (existing.isPresent()) {
            ReviewRec r = existing.get();
            if (r.regionId().equals(regionId)) {
                return reviewView(r, null);
            }
            throw ApiException.conflict("REQUEST_ID_CONFLICT",
                    "requestId 已存在但参数不同: " + req.requestId());
        }
        if (!RegionDao.CLOSED.equals(status)) {
            throw ApiException.conflict("REGION_NOT_CLOSED", "区域未封闭，不能发起解除审核: " + regionId);
        }

        Instant hour1 = SnowpackService.currentHour(clock).minusSeconds(3 * 3600L);
        List<ReadingRec> window = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Instant hour = hour1.plusSeconds(i * 3600L);
            ReadingRec reading = readingDao.findByHour(regionId, hour)
                    .orElseThrow(() -> ApiException.unprocessable("MISSING_HOURLY_READING",
                            "缺少整点读数: " + hour));
            window.add(reading);
        }
        for (ReadingRec reading : window) {
            if (reading.newSnowCm() > SnowpackService.NEW_SNOW_LIMIT_CM) {
                throw ApiException.unprocessable("OVER_LIMIT_IN_WINDOW",
                        "整点 " + reading.hourUtc() + " 新增积雪 " + reading.newSnowCm()
                                + "cm 超过 " + SnowpackService.NEW_SNOW_LIMIT_CM + "cm，不能解除");
            }
        }
        String snapshot = window.stream().map(r -> String.valueOf(r.id()))
                .collect(Collectors.joining(","));
        long id = reviewDao.insertReview(regionId, window.get(0).hourUtc(),
                window.get(2).hourUtc(), snapshot, req.requestId(), now);
        return reviewView(reviewDao.findById(id).orElseThrow(), null);
    }

    /**
     * 审核确认：第一人记录签名，第二人（必须不同）确认后生成解除版本并开放区域。
     * 同一审核员重复签字返回 422；审核已失效或已完成返回 409。
     */
    @Transactional
    public Map<String, Object> confirm(long reviewId, ConfirmationRequest req) {
        Optional<ReviewRec> found = reviewDao.findById(reviewId);
        if (found.isEmpty()) {
            throw ApiException.notFound("REVIEW_NOT_FOUND", "审核不存在: " + reviewId);
        }
        String regionId = found.get().regionId();
        Instant now = clock.instant();
        regionDao.ensureRegion(regionId, now);
        String status = regionDao.lockRegionStatus(regionId);
        ReviewRec review = reviewDao.findById(reviewId).orElseThrow();

        Optional<ConfirmationRec> existing = reviewDao.findConfirmationByRequestId(req.requestId());
        if (existing.isPresent()) {
            ConfirmationRec c = existing.get();
            if (c.reviewId() == reviewId && c.reviewer().equals(req.reviewer())) {
                return reviewView(review, null);
            }
            throw ApiException.conflict("REQUEST_ID_CONFLICT",
                    "requestId 已存在但参数不同: " + req.requestId());
        }
        if (ReviewRec.INVALIDATED.equals(review.status())) {
            throw ApiException.conflict("REVIEW_INVALIDATED",
                    "审核已失效: " + review.invalidatedReason());
        }
        if (ReviewRec.COMPLETED.equals(review.status())) {
            throw ApiException.conflict("REVIEW_ALREADY_COMPLETED", "审核已完成: " + reviewId);
        }
        if (!RegionDao.CLOSED.equals(status)) {
            throw ApiException.conflict("REGION_NOT_CLOSED", "区域未封闭，不能确认解除: " + regionId);
        }
        verifySnapshotConsistent(review);

        if (review.firstReviewer() == null) {
            reviewDao.setFirstReviewer(reviewId, req.reviewer());
            reviewDao.insertConfirmation(reviewId, req.reviewer(), 1, req.requestId(), now);
            return reviewView(reviewDao.findById(reviewId).orElseThrow(), null);
        }
        if (review.firstReviewer().equals(req.reviewer())) {
            throw ApiException.unprocessable("DUPLICATE_REVIEWER",
                    "同一审核员不能重复签字: " + req.reviewer());
        }
        reviewDao.insertConfirmation(reviewId, req.reviewer(), 2, req.requestId(), now);
        reviewDao.complete(reviewId, now);
        long version = reviewDao.nextVersion(regionId);
        long decisionId = reviewDao.insertDecision(reviewId, regionId, version, now);
        regionDao.updateStatus(regionId, RegionDao.OPEN, now);
        return reviewView(reviewDao.findById(reviewId).orElseThrow(),
                Map.of("decisionId", decisionId, "version", version));
    }

    /**
     * 在区域行锁内复核快照读数仍然一致且均未超限，防止以旧读数解除刚重新封闭的区域。
     */
    private void verifySnapshotConsistent(ReviewRec review) {
        for (String idText : review.readingIds().split(",")) {
            ReadingRec reading = readingDao.findById(Long.parseLong(idText))
                    .orElseThrow(() -> ApiException.conflict("SNAPSHOT_BROKEN",
                            "快照读数不存在: " + idText));
            if (reading.newSnowCm() > SnowpackService.NEW_SNOW_LIMIT_CM) {
                throw ApiException.conflict("SNAPSHOT_BROKEN",
                        "快照读数已超限，审核结果不可信: " + reading.hourUtc());
            }
        }
    }

    private Map<String, Object> reviewView(ReviewRec r, Map<String, Object> decision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reviewId", r.id());
        body.put("regionId", r.regionId());
        body.put("status", r.status());
        body.put("windowStart", r.windowStart().toString());
        body.put("windowEnd", r.windowEnd().toString());
        body.put("readingIds", r.readingIds());
        if (r.firstReviewer() != null) {
            body.put("firstReviewer", r.firstReviewer());
        }
        List<ConfirmationRec> confirmations = reviewDao.findConfirmations(r.id());
        body.put("reviewers", confirmations.stream().map(ConfirmationRec::reviewer).toList());
        if (r.invalidatedReason() != null) {
            body.put("invalidatedReason", r.invalidatedReason());
        }
        if (r.completedAt() != null) {
            body.put("completedAt", r.completedAt().toString());
        }
        if (decision != null) {
            body.put("decision", decision);
        }
        return body;
    }
}