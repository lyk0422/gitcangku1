package com.example.starter.dive;

import com.example.starter.dive.domain.AnomalyType;
import com.example.starter.dive.domain.DepthPoint;
import com.example.starter.dive.domain.DiveReview;
import com.example.starter.dive.service.ConflictException;
import com.example.starter.dive.service.DiveLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实H2（MODE=MySQL）集成测试：主流程、拒绝、幂等重放/409、复核链与并发边界。
 * 测试库为独立命名内存库，每个用例前清理场景数据，上下文关闭后随连接池释放。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(DiveLogServiceIntegrationTest.FixedClockConfig.class)
class DiveLogServiceIntegrationTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-10-04T08:00:00Z");
    private static final Instant BASE = Instant.parse("2026-10-04T10:00:00Z");
    private static final AtomicLong SESSION_IDS = new AtomicLong(9000);

    /** 固定时钟，验证业务时间来自可替换的Clock。 */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private DiveLogService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void cleanScenarioData() {
        jdbc.update("DELETE FROM dive_review");
        jdbc.update("DELETE FROM point_upload");
        jdbc.update("DELETE FROM depth_point");
        jdbc.update("DELETE FROM dive_session");
    }

    private long newSession() {
        long id = SESSION_IDS.incrementAndGet();
        jdbc.update("INSERT INTO dive_session (id, name, planned_start_utc, planned_end_utc, "
                        + "max_depth_m, max_duration_min, point_version, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, ?)",
                id, "集成测试作业", utc(BASE), utc(BASE.plusSeconds(40 * 60)),
                new BigDecimal("30.00"), 40, utc(BASE.minusSeconds(3600)));
        return id;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static List<DiveLogService.NewPoint> compliantPoints() {
        String[] depths = {"0", "10", "20", "28", "24", "16", "9", "4", "0"};
        List<DiveLogService.NewPoint> points = new ArrayList<>();
        for (int i = 0; i < depths.length; i++) {
            points.add(new DiveLogService.NewPoint(
                    BASE.plusSeconds(i * 5 * 60L), new BigDecimal(depths[i])));
        }
        return points;
    }

    private static List<DiveLogService.NewPoint> exceedDepthPoints() {
        List<DiveLogService.NewPoint> points = compliantPoints();
        points.set(3, new DiveLogService.NewPoint(BASE.plusSeconds(15 * 60), new BigDecimal("31")));
        return points;
    }

    @Test
    void mainFlowPassesAndFreezesVersion() {
        long sessionId = newSession();
        DiveLogService.UploadResult upload = service.uploadPoints(sessionId, "u-1", compliantPoints());
        assertEquals(9, upload.acceptedCount());
        assertEquals(1, upload.pointVersion());
        assertFalse(upload.replayed());

        DiveLogService.ReviewResult review = service.requestReview(sessionId, "r-1");
        assertEquals(DiveReview.ReviewDecision.PASSED, review.decision());
        assertTrue(review.anomalies().isEmpty());
        assertEquals(1, review.pointVersion());

        DiveLogService.OverviewResult overview = service.overview(sessionId);
        assertEquals(9, overview.points().size());
        assertNotNull(overview.activeReview());
        assertEquals(DiveReview.ReviewDecision.PASSED, overview.activeReview().decision());
        assertEquals(1, overview.activeReview().pointVersion());
        assertEquals(FIXED_NOW, overview.activeReview().createdAtUtc());
        assertEquals(1, overview.reviews().size());
    }

    @Test
    void exceedDepthRejectedWithSpecificAnomaly() {
        long sessionId = newSession();
        service.uploadPoints(sessionId, "u-1", exceedDepthPoints());
        DiveLogService.ReviewResult review = service.requestReview(sessionId, "r-1");
        assertEquals(DiveReview.ReviewDecision.REJECTED, review.decision());
        assertEquals(1, review.anomalies().size());
        assertEquals(AnomalyType.EXCEED_DEPTH, review.anomalies().get(0).type());
        assertEquals(4, review.anomalies().get(0).fromSeq());

        DiveLogService.OverviewResult overview = service.overview(sessionId);
        assertEquals(DiveReview.ReviewDecision.REJECTED, overview.activeReview().decision());
        assertEquals(AnomalyType.EXCEED_DEPTH, overview.activeReview().anomalies().get(0).type());
    }

    @Test
    void uploadIdempotencyReplayAndConflict() {
        long sessionId = newSession();
        DiveLogService.UploadResult first = service.uploadPoints(sessionId, "u-1", compliantPoints());
        DiveLogService.UploadResult replay = service.uploadPoints(sessionId, "u-1", compliantPoints());
        assertTrue(replay.replayed());
        assertEquals(first.firstSeq(), replay.firstSeq());
        assertEquals(first.lastSeq(), replay.lastSeq());
        assertEquals(first.pointVersion(), replay.pointVersion());
        assertEquals(9, service.overview(sessionId).points().size());

        assertThrows(ConflictException.class,
                () -> service.uploadPoints(sessionId, "u-1", exceedDepthPoints()));
    }

    @Test
    void reviewIdempotencyAndFailureNotOccupyingKey() {
        long sessionId = newSession();
        service.uploadPoints(sessionId, "u-1", compliantPoints());
        DiveLogService.ReviewResult first = service.requestReview(sessionId, "r-1");
        DiveLogService.ReviewResult replay = service.requestReview(sessionId, "r-1");
        assertTrue(replay.replayed());
        assertEquals(first.reviewId(), replay.reviewId());
        assertEquals(1, service.overview(sessionId).reviews().size());

        long rejectedSession = newSession();
        service.uploadPoints(rejectedSession, "u-1", exceedDepthPoints());
        assertEquals(DiveReview.ReviewDecision.REJECTED,
                service.requestReview(rejectedSession, "r-2").decision());
        // 失败不占幂等键：同一requestId可再次发起审核，不返回409
        DiveLogService.ReviewResult retry = service.requestReview(rejectedSession, "r-2");
        assertEquals(DiveReview.ReviewDecision.REJECTED, retry.decision());
        assertFalse(retry.replayed());

        // 通过后再用同一requestId但点位版本已变化，返回409
        service.uploadPoints(sessionId, "u-2",
                List.of(new DiveLogService.NewPoint(BASE.plusSeconds(45 * 60), BigDecimal.ZERO)));
        assertThrows(ConflictException.class, () -> service.requestReview(sessionId, "r-1"));
    }
    @Test
    void appendAfterReviewExpiresOriginalAndChainsReReview() {
        long sessionId = newSession();
        service.uploadPoints(sessionId, "u-1", compliantPoints());
        DiveLogService.ReviewResult passed = service.requestReview(sessionId, "r-1");
        assertEquals(DiveReview.ReviewDecision.PASSED, passed.decision());
        List<DepthPoint> frozenPoints = service.overview(sessionId).points();

        // 追加勘误点：10:45深度31米，超深且超时长
        service.uploadPoints(sessionId, "u-2",
                List.of(new DiveLogService.NewPoint(BASE.plusSeconds(45 * 60), new BigDecimal("31"))));

        DiveLogService.OverviewResult overview = service.overview(sessionId);
        assertEquals(2, overview.reviews().size());
        DiveReview original = overview.reviews().get(0);
        DiveReview reReview = overview.reviews().get(1);
        assertEquals(DiveReview.ReviewStatus.EXPIRED, original.status());
        assertEquals(passed.reviewId(), original.id());
        assertEquals(DiveReview.ReviewKind.RE_REVIEW, reReview.kind());
        assertEquals(original.id(), reReview.supersedesId());
        assertEquals(DiveReview.ReviewDecision.REJECTED, reReview.decision());
        assertEquals(2, reReview.pointVersion());
        assertTrue(reReview.anomalies().stream()
                .anyMatch(a -> a.type() == AnomalyType.EXCEED_DEPTH));
        assertEquals(reReview.id(), overview.activeReview().id());

        // 已冻结原始点不被修改：前9个点与审核时完全一致
        List<DepthPoint> points = overview.points();
        assertEquals(10, points.size());
        for (int i = 0; i < frozenPoints.size(); i++) {
            assertEquals(frozenPoints.get(i).recordedAtUtc(), points.get(i).recordedAtUtc());
            assertEquals(0, frozenPoints.get(i).depthM().compareTo(points.get(i).depthM()));
            assertEquals(frozenPoints.get(i).seq(), points.get(i).seq());
        }
    }

    @Test
    void concurrentReviewsProduceSinglePassDecision() {
        long sessionId = newSession();
        service.uploadPoints(sessionId, "u-1", compliantPoints());
        int threads = 8;
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<DiveLogService.ReviewResult>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    String requestId = "rc-" + i;
                    futures.add(pool.submit(() -> {
                        start.await();
                        return service.requestReview(sessionId, requestId);
                    }));
                }
                start.countDown();
                long passedReviewId = -1;
                for (Future<DiveLogService.ReviewResult> future : futures) {
                    DiveLogService.ReviewResult result = future.get(30, TimeUnit.SECONDS);
                    assertEquals(DiveReview.ReviewDecision.PASSED, result.decision());
                    if (passedReviewId < 0) {
                        passedReviewId = result.reviewId();
                    }
                    assertEquals(passedReviewId, result.reviewId());
                }
            } finally {
                pool.shutdownNow();
            }
        });
        // 同一版本最多一条通过决定
        Long passedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dive_review WHERE session_id = ? AND decision = 'PASSED'",
                Long.class, sessionId);
        assertEquals(1, passedCount);
        assertEquals(1, service.overview(sessionId).reviews().size());
    }

    @Test
    void appendAndReviewRaceStaysConsistent() {
        long sessionId = newSession();
        service.uploadPoints(sessionId, "u-1", compliantPoints());
        int batches = 5;
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);
                Future<?> appends = pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < batches; i++) {
                        service.uploadPoints(sessionId, "ra-" + i, List.of(new DiveLogService.NewPoint(
                                BASE.plusSeconds((41 + i) * 60L), new BigDecimal("5"))));
                    }
                    return null;
                });
                Future<?> reviews = pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 20; i++) {
                        service.requestReview(sessionId, "rr-" + i);
                    }
                    return null;
                });
                start.countDown();
                appends.get(30, TimeUnit.SECONDS);
                reviews.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
        });
        DiveLogService.OverviewResult overview = service.overview(sessionId);
        // 最终状态一致：版本=初始1+追加批次数，点位完整，唯一有效审核对应当前版本
        assertEquals(1 + batches, overview.session().pointVersion());
        assertEquals(9 + batches, overview.points().size());
        assertNotNull(overview.activeReview());
        assertEquals(overview.session().pointVersion(), overview.activeReview().pointVersion());
        for (DiveReview review : overview.reviews()) {
            assertTrue(review.pointVersion() >= 1
                    && review.pointVersion() <= overview.session().pointVersion());
        }
        long activeCount = overview.reviews().stream()
                .filter(r -> r.status() == DiveReview.ReviewStatus.ACTIVE).count();
        assertEquals(1, activeCount);
    }

    @Test
    void webLayerSmoke() throws Exception {
        long sessionId = newSession();
        String uploadBody = """
                {"requestId":"web-u-1","points":[
                {"recordedAtUtc":"2026-10-04T10:00:00Z","depthM":0},
                {"recordedAtUtc":"2026-10-04T10:05:00Z","depthM":10},
                {"recordedAtUtc":"2026-10-04T10:10:00Z","depthM":20},
                {"recordedAtUtc":"2026-10-04T10:15:00Z","depthM":28},
                {"recordedAtUtc":"2026-10-04T10:20:00Z","depthM":24},
                {"recordedAtUtc":"2026-10-04T10:25:00Z","depthM":16},
                {"recordedAtUtc":"2026-10-04T10:30:00Z","depthM":9},
                {"recordedAtUtc":"2026-10-04T10:35:00Z","depthM":4},
                {"recordedAtUtc":"2026-10-04T10:40:00Z","depthM":0}]}
                """;
        mockMvc.perform(post("/api/dive/sessions/{id}/points", sessionId)
                        .contentType(MediaType.APPLICATION_JSON).content(uploadBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pointVersion").value(1))
                .andExpect(jsonPath("$.acceptedCount").value(9));

        mockMvc.perform(post("/api/dive/sessions/{id}/reviews", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"web-r-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("PASSED"))
                .andExpect(jsonPath("$.pointVersion").value(1));

        mockMvc.perform(get("/api/dive/sessions/{id}/overview", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(9))
                .andExpect(jsonPath("$.activeReview.decision").value("PASSED"))
                .andExpect(jsonPath("$.reviews.length()").value(1));

        // 同requestId异参：409
        String conflictBody = uploadBody.replace("28", "31");
        mockMvc.perform(post("/api/dive/sessions/{id}/points", sessionId)
                        .contentType(MediaType.APPLICATION_JSON).content(conflictBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }
}