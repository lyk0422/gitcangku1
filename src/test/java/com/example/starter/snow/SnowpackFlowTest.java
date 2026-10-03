package com.example.starter.snow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.snow.dto.ClosureRequest;
import com.example.starter.snow.dto.ConfirmationRequest;
import com.example.starter.snow.dto.ErrataRequest;
import com.example.starter.snow.dto.ReadingRequest;
import com.example.starter.snow.dto.ReviewCreateRequest;
import com.example.starter.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 基于真实 H2（MODE=MySQL）的解除审核主流程与失败分支测试。
 * 时钟固定为 2026-10-04T12:00:00Z，审核窗口为 09/10/11 三个整点。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class SnowpackFlowTest {

    private static final String H09 = "2026-10-04T09:00:00Z";
    private static final String H10 = "2026-10-04T10:00:00Z";
    private static final String H11 = "2026-10-04T11:00:00Z";
    private static final String H12 = "2026-10-04T12:00:00Z";

    @Autowired
    private SnowpackService snowpackService;
    @Autowired
    private UnsealService unsealService;
    @Autowired
    private StatusService statusService;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM unseal_decision");
        jdbc.update("DELETE FROM unseal_confirmation");
        jdbc.update("DELETE FROM unseal_review");
        jdbc.update("DELETE FROM reading_errata");
        jdbc.update("DELETE FROM snow_reading");
        jdbc.update("DELETE FROM closure_command");
        jdbc.update("DELETE FROM region_state");
        clock.setInstant(TestClockConfig.T0);
    }

    private String region() {
        return "R-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String reqId() {
        return UUID.randomUUID().toString();
    }

    private void close(String regionId) {
        snowpackService.createClosure(regionId, new ClosureRequest(reqId(), "test closure"));
    }

    private Map<String, Object> report(String regionId, String hour, int newSnowCm) {
        return snowpackService.reportReading(regionId,
                new ReadingRequest(reqId(), hour, 100, newSnowCm));
    }

    private void reportWindow(String regionId, int h09, int h10, int h11) {
        report(regionId, H09, h09);
        report(regionId, H10, h10);
        report(regionId, H11, h11);
    }

    private long createReview(String regionId) {
        Map<String, Object> view = unsealService.createReview(regionId,
                new ReviewCreateRequest(reqId()));
        return ((Number) view.get("reviewId")).longValue();
    }

    @Test
    void fullUnsealFlowCreatesDecisionAndOpensRegion() {
        String region = region();
        close(region);
        reportWindow(region, 3, 4, 5);
        long reviewId = createReview(region);

        Map<String, Object> first = unsealService.confirm(reviewId,
                new ConfirmationRequest(reqId(), "alice"));
        assertEquals("PENDING", first.get("status"));
        assertEquals("alice", first.get("firstReviewer"));

        Map<String,Object> second = unsealService.confirm(reviewId,
                new ConfirmationRequest(reqId(), "bob"));
        assertEquals("COMPLETED", second.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> decision = (Map<String, Object>) second.get("decision");
        assertEquals(1L, ((Number) decision.get("version")).longValue());

        Map<String, Object> status = statusService.getStatus(region);
        assertEquals("OPEN", status.get("status"));
        assertEquals(3, status.get("consecutiveHourCount"));
        @SuppressWarnings("unchecked")
        Map<String, Object> review = (Map<String, Object>) status.get("latestReview");
        assertEquals(List.of("alice", "bob"), review.get("reviewers"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) status.get("timeline");
        assertEquals(List.of("CLOSED", "UNSEALED"),
                timeline.stream().map(e -> e.get("type")).toList());
    }

    @Test
    void windowWithFourFiveSixCentimetersIsRejected() {
        String region = region();
        close(region);
        reportWindow(region, 4, 5, 6);
        ApiException ex = assertThrows(ApiException.class,
                () -> createReview(region));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals("OVER_LIMIT_IN_WINDOW", ex.getCode());
    }

    @Test
    void missingHourlyReadingIsRejected() {
        String region = region();
        close(region);
        report(region, H09, 2);
        report(region, H11, 2);
        ApiException ex = assertThrows(ApiException.class,
                () -> createReview(region));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals("MISSING_HOURLY_READING", ex.getCode());
    }

    @Test
    void reviewOnOpenRegionIsRejected() {
        String region = region();
        reportWindow(region, 1, 1, 1);
        ApiException ex = assertThrows(ApiException.class,
                () -> createReview(region));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("REGION_NOT_CLOSED", ex.getCode());
    }

    @Test
    void sameReviewerTwiceIsRejected() {
        String region = region();
        close(region);
        reportWindow(region, 2, 2, 2);
        long reviewId = createReview(region);
        unsealService.confirm(reviewId, new ConfirmationRequest(reqId(), "alice"));
        ApiException ex = assertThrows(ApiException.class,
                () -> unsealService.confirm(reviewId, new ConfirmationRequest(reqId(), "alice")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals("DUPLICATE_REVIEWER", ex.getCode());
    }

    @Test
    void newOverLimitReadingInvalidatesPendingReview() {
        String region = region();
        close(region);
        reportWindow(region, 2, 2, 2);
        long reviewId = createReview(region);
        unsealService.confirm(reviewId, new ConfirmationRequest(reqId(), "alice"));

        Map<String, Object> reading = report(region, H12, 8);
        assertEquals(1, ((Number) reading.get("invalidatedReviews")).intValue());

        ApiException ex = assertThrows(ApiException.class,
                () -> unsealService.confirm(reviewId, new ConfirmationRequest(reqId(), "bob")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("REVIEW_INVALIDATED", ex.getCode());

        Map<String, Object> status = statusService.getStatus(region);
        @SuppressWarnings("unchecked")
        Map<String, Object> review = (Map<String, Object>) status.get("latestReview");
        assertEquals("INVALIDATED", review.get("status"));
        assertTrue(((String) review.get("invalidatedReason")).startsWith("OVER_LIMIT_READING"));
        assertEquals("CLOSED", status.get("status"));
    }

    @Test
    void overLimitAfterUnsealCreatesNewClosureAndKeepsDecision() {
        String region = region();
        close(region);
        reportWindow(region, 2, 2, 2);
        long reviewId = createReview(region);
        unsealService.confirm(reviewId, new ConfirmationRequest(reqId(), "alice"));
        unsealService.confirm(reviewId, new ConfirmationRequest(reqId(), "bob"));
        assertEquals("OPEN", statusService.getStatus(region).get("status"));

        clock.setInstant(TestClockConfig.T0.plusSeconds(1));
        Map<String, Object> reading = report(region, H12, 9);
        assertTrue(reading.containsKey("closureCommandId"));

        Map<String, Object> status = statusService.getStatus(region);
        assertEquals("CLOSED", status.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) status.get("timeline");
        assertEquals(List.of("CLOSED", "UNSEALED", "CLOSED"),
                timeline.stream().map(e -> e.get("type")).toList());
        Integer decisions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM unseal_decision WHERE region_id = ?", Integer.class, region);
        assertEquals(1, decisions);
    }

    @Test
    void oldReadingOnlyAcceptsErrata() {
        String region = region();
        Map<String, Object> saved = report(region, H09, 3);
        long readingId = ((Number) saved.get("readingId")).longValue();

        Map<String, Object> errata = snowpackService.appendErrata(readingId,
                new ErrataRequest(reqId(), "carol", "sensor drift corrected"));
        assertEquals(readingId, ((Number) errata.get("readingId")).longValue());

        ApiException ex = assertThrows(ApiException.class,
                () -> report(region, H09, 4));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("READING_ALREADY_EXISTS", ex.getCode());
        assertEquals(3, jdbc.queryForObject(
                "SELECT new_snow_cm FROM snow_reading WHERE id = ?", Integer.class, readingId));
    }

    @Test
    void requestIdReplayAndConflict() {
        String region = region();
        String requestId = reqId();
        ReadingRequest req = new ReadingRequest(requestId, H09, 100, 3);
        Map<String, Object> first = snowpackService.reportReading(region, req);
        Map<String, Object> replay = snowpackService.reportReading(region, req);
        assertEquals(first.get("readingId"), replay.get("readingId"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM snow_reading WHERE region_id = ?", Integer.class, region));

        ApiException ex = assertThrows(ApiException.class, () -> snowpackService.reportReading(
                region, new ReadingRequest(requestId, H09, 100, 4)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("REQUEST_ID_CONFLICT", ex.getCode());
    }

    @Test
    void nonWholeHourIsRejected() {
        String region = region();
        ApiException ex = assertThrows(ApiException.class, () -> snowpackService.reportReading(
                region, new ReadingRequest(reqId(), "2026-10-04T09:30:00Z", 100, 3)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals("INVALID_HOUR", ex.getCode());
    }
}