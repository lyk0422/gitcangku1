package com.example.starter.dive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.example.starter.StarterApplication;
import com.example.starter.dive.DiveDtos.AppendPointsRequest;
import com.example.starter.dive.DiveDtos.AppendResult;
import com.example.starter.dive.DiveDtos.CreateLogRequest;
import com.example.starter.dive.DiveDtos.LogDetailDto;
import com.example.starter.dive.DiveDtos.LogDto;
import com.example.starter.dive.DiveDtos.PointInput;
import com.example.starter.dive.DiveDtos.ReviewDto;
import com.example.starter.dive.DiveDtos.ReviewRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 潜水作业记录主流程与拒绝分支测试，使用真实H2内存库（MODE=MySQL）。
 */
@SpringBootTest(classes = {StarterApplication.class, TestClockConfig.class})
@AutoConfigureMockMvc
@Sql(statements = {
        "DELETE FROM re_review",
        "DELETE FROM dive_review",
        "DELETE FROM idempotency_record",
        "DELETE FROM depth_point",
        "DELETE FROM dive_log"
})
class DiveLogFlowTest {

    private static final Instant T0 = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant T40 = Instant.parse("2026-10-04T10:40:00Z");

    @Autowired
    private DiveLogService service;

    @Autowired
    private MockMvc mockMvc;

    private LogDto newLog(String requestId) {
        return service.createLog(new CreateLogRequest(requestId, 30, 40, T0, T40));
    }

    /** 合成10:00至10:40每5分钟一点，末点0米，中间点深度由depths给出。 */
    private List<PointInput> syntheticPoints(double... depths) {
        List<PointInput> points = new ArrayList<>();
        for (int i = 0; i < depths.length; i++) {
            points.add(new PointInput(T0.plus(Duration.ofMinutes(5L * i)), depths[i]));
        }
        return points;
    }

    @Test
    void mainFlowApprovedAndFrozen() {
        LogDto log = newLog("create-1");
        AppendResult appended = service.appendPoints(log.id(),
                new AppendPointsRequest("up-1",
                        syntheticPoints(0, 10, 20, 28, 25, 18, 12, 6, 0)));
        assertEquals(1, appended.pointVersion());
        assertFalse(appended.errataRecorded());

        ReviewDto review = service.review(log.id(), new ReviewRequest("rv-1"));
        assertEquals("APPROVED", review.decision());
        assertTrue(review.anomalies().isEmpty());
        assertEquals(9, review.pointCount());
        assertEquals(1, review.pointVersion());

        LogDetailDto detail = service.detail(log.id());
        assertEquals(9, detail.points().size());
        assertEquals("APPROVED", detail.currentReview().decision());
        assertEquals("CURRENT", detail.currentReview().status());
        assertEquals(1, detail.reviews().size());
        assertTrue(detail.reReviews().isEmpty());
    }

    @Test
    void rejectWhenDepthExceedsEvenIfLastPointIsZero() {
        LogDto log = newLog("create-2");
        // 10:15达到31米，超过30米上限；末点回到0米也不得通过
        service.appendPoints(log.id(), new AppendPointsRequest("up-2",
                syntheticPoints(0, 10, 20, 31, 25, 18, 12, 6, 0)));

        ReviewDto review = service.review(log.id(), new ReviewRequest("rv-2"));
        assertEquals("REJECTED", review.decision());
        assertEquals(1, review.anomalies().size());
        var anomaly = review.anomalies().get(0);
        assertEquals("DEPTH_EXCEEDED", anomaly.type());
        assertEquals(4, anomaly.pointSeq());
        assertEquals(Instant.parse("2026-10-04T10:15:00Z"), anomaly.observedAtUtc());
        assertEquals(31.0, anomaly.depthM());
    }

    @Test
    void rejectWhenMeasurementGapExceedsFiveMinutes() {
        LogDto log = newLog("create-3");
        List<PointInput> points = syntheticPoints(0, 10, 20, 25, 18, 12, 6, 0);
        // 第4点从10:15挪到10:16，形成10:05->10:16的11分钟缺测区间
        points.set(3, new PointInput(Instant.parse("2026-10-04T10:16:00Z"), 25));
        // 重新对齐后续点保持递增但间隔仍超5分钟
        service.appendPoints(log.id(), new AppendPointsRequest("up-3", points));

        ReviewDto review = service.review(log.id(), new ReviewRequest("rv-3"));
        assertEquals("REJECTED", review.decision());
        assertTrue(review.anomalies().stream().anyMatch(a -> a.type().equals("MEASUREMENT_GAP")));
    }

    @Test
    void rejectWhenTimeRegresses() {
        LogDto log = newLog("create-4");
        List<PointInput> points = syntheticPoints(0, 10, 20, 25, 18, 12, 6, 0);
        // 第3点时刻倒退到第2点之前
        points.set(2, new PointInput(Instant.parse("2026-10-04T10:04:00Z"), 20));
        service.appendPoints(log.id(), new AppendPointsRequest("up-4", points));

        ReviewDto review = service.review(log.id(), new ReviewRequest("rv-4"));
        assertEquals("REJECTED", review.decision());
        assertTrue(review.anomalies().stream().anyMatch(a -> a.type().equals("TIME_REGRESSION")));
    }

    @Test
    void rejectWhenDurationExceedsLimit() {
        LogDto log = service.createLog(new CreateLogRequest("create-5", 30, 40, T0,
                Instant.parse("2026-10-04T10:45:00Z")));
        List<PointInput> points = new ArrayList<>();
        for (int i = 0; i <= 9; i++) {
            points.add(new PointInput(T0.plus(Duration.ofMinutes(5L * i)), i == 9 ? 0 : 10));
        }
        service.appendPoints(log.id(), new AppendPointsRequest("up-5", points));

        ReviewDto review = service.review(log.id(), new ReviewRequest("rv-5"));
        assertEquals("REJECTED", review.decision());
        assertTrue(review.anomalies().stream().anyMatch(a -> a.type().equals("DURATION_EXCEEDED")));
    }    @Test
    void rejectWhenCoverageMissing() {
        LogDto log = newLog("create-6");
        List<PointInput> points = syntheticPoints(0, 10, 20, 25, 18, 12, 6, 0);
        // 末点停在10:35，未覆盖作业结束10:40
        points.remove(points.size() - 1);
        service.appendPoints(log.id(), new AppendPointsRequest("up-6", points));

        ReviewDto review = service.review(log.id(), new ReviewRequest("rv-6"));
        assertEquals("REJECTED", review.decision());
        assertTrue(review.anomalies().stream().anyMatch(a -> a.type().equals("COVERAGE_MISSING")));
    }

    @Test
    void idempotentReplaySameParamsAndConflictOnDifferent() throws Exception {
        LogDto log = newLog("create-7");
        var request = new AppendPointsRequest("up-7", syntheticPoints(0, 10, 20, 25, 18, 12, 6, 0));
        AppendResult first = service.appendPoints(log.id(), request);
        AppendResult replay = service.appendPoints(log.id(), request);
        assertEquals(first.pointVersion(), replay.pointVersion());
        assertEquals(8, service.detail(log.id()).points().size());

        // 同requestId异参 -> 409
        String body = """
                {"requestId":"up-7","points":[{"observedAtUtc":"2026-10-04T10:00:00Z","depthM":5.0}]}
                """;
        mockMvc.perform(post("/api/dive-logs/{id}/points", log.id())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        assertEquals(8, service.detail(log.id()).points().size());
    }

    @Test
    void failedReviewDoesNotConsumeIdempotencyKey() throws Exception {
        LogDto log = newLog("create-8");
        // 无点位时审核失败，返回422且不占幂等键
        mockMvc.perform(post("/api/dive-logs/{id}/reviews", log.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"rv-8\"}"))
                .andExpect(status().isUnprocessableEntity());

        service.appendPoints(log.id(), new AppendPointsRequest("up-8",
                syntheticPoints(0, 10, 20, 28, 25, 18, 12, 6, 0)));
        ReviewDto retry = service.review(log.id(), new ReviewRequest("rv-8"));
        assertEquals("APPROVED", retry.decision());
    }

    @Test
    void errataAppendCreatesReReviewAndStalesOriginalReview() {
        LogDto log = newLog("create-9");
        service.appendPoints(log.id(), new AppendPointsRequest("up-9",
                syntheticPoints(0, 10, 20, 28, 25, 18, 12, 6, 0)));
        ReviewDto approved = service.review(log.id(), new ReviewRequest("rv-9"));
        assertEquals("APPROVED", approved.decision());

        // 审核后追加勘误点：生成复核记录并标记原审核过期，原始点不被修改
        AppendResult errata = service.appendPoints(log.id(), new AppendPointsRequest("up-9b",
                List.of(new PointInput(T0.plus(Duration.ofMinutes(2)), 5))));
        assertTrue(errata.errataRecorded());
        assertEquals(2, errata.pointVersion());

        LogDetailDto detail = service.detail(log.id());
        assertEquals(10, detail.points().size());
        assertEquals(9, detail.points().stream().filter(p -> !p.errata()).count());
        assertTrue(detail.points().get(9).errata());
        assertEquals("STALE", detail.reviews().get(0).status());
        assertEquals(1, detail.reReviews().size());
        assertEquals(1, detail.reReviews().get(0).fromPointVersion());
        assertEquals(2, detail.reReviews().get(0).toPointVersion());
        assertEquals(approved.id(), detail.reReviews().get(0).staleReviewId());

        // 复核后生成新的当前版本审核，不再静默维持原通过状态；
        // 勘误点时刻早于已上传末点，按严格递增规则被识别为时间倒退而拒绝
        ReviewDto reReview = service.review(log.id(), new ReviewRequest("rv-9b"));
        assertEquals(2, reReview.pointVersion());
        assertEquals(10, reReview.pointCount());
        assertEquals("CURRENT", reReview.status());
        assertEquals("REJECTED", reReview.decision());
        assertTrue(reReview.anomalies().stream().anyMatch(a -> a.type().equals("TIME_REGRESSION")));
        assertEquals(2, service.detail(log.id()).reviews().size());
    }

    @Test
    void notFoundReturns404() throws Exception {
        mockMvc.perform(post("/api/dive-logs/{id}/reviews", 9999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"rv-x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reviewTimestampUsesControllableClock() {
        LogDto log = newLog("create-10");
        service.appendPoints(log.id(), new AppendPointsRequest("up-10",
                syntheticPoints(0, 10, 20, 28, 25, 18, 12, 6, 0)));
        ReviewDto review = service.review(log.id(), new ReviewRequest("rv-10"));
        assertEquals(Instant.parse("2026-10-04T02:00:00Z"), review.createdAtUtc());
    }
}