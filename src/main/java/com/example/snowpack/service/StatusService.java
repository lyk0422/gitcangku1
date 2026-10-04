package com.example.snowpack.service;

import com.example.snowpack.error.BusinessException;
import com.example.snowpack.repo.ClosureRepository;
import com.example.snowpack.repo.ReadingRepository;
import com.example.snowpack.repo.ReviewRepository;
import com.example.snowpack.repo.Rows.ClosureRow;
import com.example.snowpack.repo.Rows.DecisionRow;
import com.example.snowpack.repo.Rows.ReadingRow;
import com.example.snowpack.repo.Rows.ReviewRow;
import com.example.snowpack.repo.Rows.ZoneStateRow;
import com.example.snowpack.repo.ZoneRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 区域状态查询：连续整点读数、审核人、失效原因与封闭/解除时间线。 */
@Service
public class StatusService {

    private static final int RECENT_READING_LIMIT = 8;

    private final ZoneRepository zones;
    private final ReadingRepository readings;
    private final ClosureRepository closures;
    private final ReviewRepository reviews;

    public StatusService(ZoneRepository zones, ReadingRepository readings, ClosureRepository closures,
                         ReviewRepository reviews) {
        this.zones = zones;
        this.readings = readings;
        this.closures = closures;
        this.reviews = reviews;
    }

    public record ReadingView(long id, Instant hourUtc, int snowDepthCm, int newSnowCm, boolean overLimit) {
    }

    public record WindowView(boolean complete, boolean consecutive, boolean allWithinLimit) {
    }

    public record ReviewView(long id, String status, String firstReviewer, String secondReviewer,
                             String invalidateReason, Instant createdAt, Instant completedAt) {
    }

    public record TimelineEntry(String type, Instant at, String detail, Integer versionNo) {
    }

    public record ZoneStatus(String zoneId, String status, long readingVersion,
                             List<ReadingView> recentReadings, WindowView window,
                             ReviewView latestReview, List<TimelineEntry> timeline) {
    }

    @Transactional(readOnly = true)
    public ZoneStatus status(String zoneId) {
        ZoneStateRow zone = zones.findById(zoneId)
                .orElseThrow(() -> BusinessException.notFound("ZONE_NOT_FOUND", "zone not found"));

        List<ReadingRow> recent = readings.latest(zoneId, RECENT_READING_LIMIT);
        List<ReadingView> readingViews = recent.stream()
                .map(row -> new ReadingView(row.id(), row.hourUtc(), row.snowDepthCm(), row.newSnowCm(),
                        row.newSnowCm() > ReadingService.NEW_SNOW_LIMIT_CM))
                .toList();

        WindowView window = buildWindow(recent);
        ReviewView reviewView = reviews.findLatest(zoneId)
                .map(row -> new ReviewView(row.id(), row.status(), row.firstReviewer(), row.secondReviewer(),
                        row.invalidateReason(), row.createdAt(), row.completedAt()))
                .orElse(null);

        List<TimelineEntry> timeline = new ArrayList<>();
        for (ClosureRow closure : closures.listByZone(zoneId)) {
            timeline.add(new TimelineEntry("CLOSURE", closure.createdAt(),
                    closure.source() + " closure: " + closure.reason(), null));
            if (closure.liftedAt() != null) {
                timeline.add(new TimelineEntry("CLOSURE_LIFTED", closure.liftedAt(),
                        "closure " + closure.id() + " lifted", null));
            }
        }
        for (DecisionRow decision : reviews.listDecisions(zoneId)) {
            ReviewRow review = reviews.findById(decision.reviewId()).orElse(null);
            String reviewers = review == null ? ""
                    : " by " + review.firstReviewer() + " + " + review.secondReviewer();
            timeline.add(new TimelineEntry("RELEASE", decision.createdAt(),
                    "release decision v" + decision.versionNo() + reviewers, decision.versionNo()));
        }
        timeline.sort(Comparator.comparing(TimelineEntry::at));

        return new ZoneStatus(zone.zoneId(), zone.status(), zone.readingVersion(), readingViews,
                window, reviewView, List.copyOf(timeline));
    }

    /** 最近三个整点窗口视图：是否满三条、是否逐小时连续、是否均不超限。 */
    private WindowView buildWindow(List<ReadingRow> recent) {
        List<ReadingRow> window = recent.stream().limit(ReviewService.REQUIRED_WINDOW_HOURS).toList();
        boolean complete = window.size() == ReviewService.REQUIRED_WINDOW_HOURS;
        boolean consecutive = complete;
        for (int i = 0; i + 1 < window.size(); i++) {
            Instant expected = window.get(i + 1).hourUtc().plus(1, ChronoUnit.HOURS);
            if (!window.get(i).hourUtc().equals(expected)) {
                consecutive = false;
                break;
            }
        }
        boolean allWithinLimit = complete && window.stream()
                .allMatch(row -> row.newSnowCm() <= ReadingService.NEW_SNOW_LIMIT_CM);
        return new WindowView(complete, consecutive, allWithinLimit);
    }
}