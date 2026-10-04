package com.example.snowpack.service;

import com.example.snowpack.error.BusinessException;
import com.example.snowpack.repo.ClosureRepository;
import com.example.snowpack.repo.ReadingRepository;
import com.example.snowpack.repo.ReviewRepository;
import com.example.snowpack.repo.Rows.ClosureRow;
import com.example.snowpack.repo.Rows.ReadingRow;
import com.example.snowpack.repo.Rows.ReviewRow;
import com.example.snowpack.repo.Rows.ZoneStateRow;
import com.example.snowpack.repo.ZoneRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 解除封闭双人审核。第一人发起时快照读数版本与三小时窗口；
 * 第二人确认必须在同一读数版本上完成，且审核员不得重复。
 */
@Service
public class ReviewService {

    /** 解除要求的连续整点读数个数。 */
    public static final int REQUIRED_WINDOW_HOURS = 3;

    private final ZoneRepository zones;
    private final ReadingRepository readings;
    private final ClosureRepository closures;
    private final ReviewRepository reviews;
    private final Clock clock;

    public ReviewService(ZoneRepository zones, ReadingRepository readings, ClosureRepository closures,
                         ReviewRepository reviews, Clock clock) {
        this.zones = zones;
        this.readings = readings;
        this.closures = closures;
        this.reviews = reviews;
        this.clock = clock;
    }

    /** 审核结果视图。 */
    public record ReviewResult(long reviewId, String zoneId, String status, String firstReviewer,
                               String secondReviewer, String invalidateReason, Integer decisionVersion,
                               boolean replayed) {
    }

    /**
     * 提交一次审核确认。区域内无未完成审核时作为第一人发起；
     * 已有未完成审核时作为第二人确认并生成解除版本。
     * 幂等：requestId 同参重放返回当前审核状态，异参 409。
     */
    @Transactional
    public ReviewResult confirm(String zoneId, String requestId, String reviewer) {
        Instant now = clock.instant();
        zones.ensureZone(zoneId, now);
        ZoneStateRow zone = zones.lockById(zoneId).orElseThrow();

        var byFirst = reviews.findByFirstRequestId(zoneId, requestId);
        if (byFirst.isPresent()) {
            ReviewRow row = byFirst.get();
            if (!row.firstReviewer().equals(reviewer)) {
                throw BusinessException.conflict("REQUEST_ID_CONFLICT",
                        "requestId already used with different parameters");
            }
            return toResult(row, true);
        }
        var bySecond = reviews.findBySecondRequestId(zoneId, requestId);
        if (bySecond.isPresent()) {
            ReviewRow row = bySecond.get();
            if (!reviewer.equals(row.secondReviewer())) {
                throw BusinessException.conflict("REQUEST_ID_CONFLICT",
                        "requestId already used with different parameters");
            }
            return toResult(row, true);
        }

        var inProgress = reviews.findInProgress(zoneId);
        if (inProgress.isEmpty()) {
            return startReview(zone, requestId, reviewer, now);
        }
        return completeReview(zone, inProgress.get(), requestId, reviewer, now);
    }

    /** 第一人发起：校验区域封闭中且最近三个整点读数连续且均不超限。 */
    private ReviewResult startReview(ZoneStateRow zone, String requestId, String reviewer, Instant now) {
        if (!ZoneRepository.STATUS_CLOSED.equals(zone.status())) {
            throw BusinessException.conflict("ZONE_NOT_CLOSED", "zone is not closed");
        }
        ClosureRow closure = closures.findActive(zone.zoneId())
                .orElseThrow(() -> BusinessException.conflict("ZONE_NOT_CLOSED",
                        "zone has no active closure command"));
        List<ReadingRow> window = requireValidWindow(zone.zoneId());
        long reviewId = reviews.insertReview(zone.zoneId(), closure.id(), reviewer, requestId,
                zone.readingVersion(), window.get(0).id(), now);
        return new ReviewResult(reviewId, zone.zoneId(), ReviewRepository.STATUS_IN_PROGRESS,
                reviewer, null, null, null, false);
    }

    /** 第二人确认：读数版本必须与发起时一致，确认后生成唯一解除版本并解除封闭。 */
    private ReviewResult completeReview(ZoneStateRow zone, ReviewRow review, String requestId,
                                        String reviewer, Instant now) {
        if (review.firstReviewer().equals(reviewer)) {
            throw BusinessException.unprocessable("SAME_REVIEWER",
                    "second reviewer must differ from the first reviewer");
        }
        if (zone.readingVersion() != review.readingVersion()) {
            throw BusinessException.conflict("STALE_READING_VERSION",
                    "new readings arrived after review started; restart the review");
        }
        List<ReadingRow> window = requireValidWindow(zone.zoneId());
        if (window.get(0).id() != review.windowHeadReadingId()) {
            throw BusinessException.conflict("STALE_READING_VERSION",
                    "reading window changed after review started; restart the review");
        }
        reviews.complete(review.id(), reviewer, requestId, now);
        int versionNo = reviews.nextVersionNo(zone.zoneId());
        reviews.insertDecision(review.id(), zone.zoneId(), versionNo, now);
        closures.markLifted(review.closureId(), now);
        zones.updateStatus(zone.zoneId(), ZoneRepository.STATUS_OPEN, now);
        return new ReviewResult(review.id(), zone.zoneId(), ReviewRepository.STATUS_COMPLETED,
                review.firstReviewer(), reviewer, null, versionNo, false);
    }

    /** 校验最近三个整点读数：必须满三条、逐小时连续、新增积雪均不超限。 */
    private List<ReadingRow> requireValidWindow(String zoneId) {
        List<ReadingRow> latest = readings.latest(zoneId, REQUIRED_WINDOW_HOURS);
        if (latest.size() < REQUIRED_WINDOW_HOURS) {
            throw BusinessException.unprocessable("MISSING_HOURLY_READING",
                    "need " + REQUIRED_WINDOW_HOURS + " consecutive hourly readings");
        }
        for (int i = 0; i < latest.size() - 1; i++) {
            Instant expected = latest.get(i + 1).hourUtc().plus(1, ChronoUnit.HOURS);
            if (!latest.get(i).hourUtc().equals(expected)) {
                throw BusinessException.unprocessable("MISSING_HOURLY_READING",
                        "hourly readings are not consecutive around " + latest.get(i).hourUtc());
            }
        }
        boolean overLimit = latest.stream()
                .anyMatch(row -> row.newSnowCm() > ReadingService.NEW_SNOW_LIMIT_CM);
        if (overLimit) {
            throw BusinessException.unprocessable("OVER_LIMIT_READING_IN_WINDOW",
                    "one of the last " + REQUIRED_WINDOW_HOURS + " hourly readings exceeds "
                            + ReadingService.NEW_SNOW_LIMIT_CM + "cm of new snow");
        }
        return latest;
    }

    private ReviewResult toResult(ReviewRow row, boolean replayed) {
        Integer version = reviews.findDecisionByReviewId(row.id())
                .map(decision -> decision.versionNo())
                .orElse(null);
        return new ReviewResult(row.id(), row.zoneId(), row.status(), row.firstReviewer(),
                row.secondReviewer(), row.invalidateReason(), version, replayed);
    }
}