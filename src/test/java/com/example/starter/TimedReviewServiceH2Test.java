package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.TimedPointDto;
import com.example.starter.api.dto.TimedReviewRequest;
import com.example.starter.api.dto.TimedReviewResultDto;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.api.dto.ZoneRevokeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定时航线可行性审查的 H2 数据库测试（MODE=MySQL）。
 * 覆盖主流程、速度/时刻/点数失败分支、版本冲突、历史冻结与 STALE、幂等边界。
 */
@SpringBootTest
@DisplayName("定时航线可行性审查 H2 测试")
class TimedReviewServiceH2Test {

    /** 固定 UTC 整秒基准时刻，测试不依赖真实时钟。 */
    private static final long T0 = 1_791_100_800L; // 2026-10-04T08:00:00Z

    @Autowired
    private com.example.starter.service.AirspaceReviewService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void cleanBefore() {
        cleanup();
    }

    @AfterEach
    void cleanAfter() {
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM timed_review");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
    }

    private String rid(String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    /** 创建两点航线 (0,0)-(1000,0)，初始版本 1。 */
    private void createRoute(String routeId) {
        service.createRoute(new RouteCreateRequest(routeId,
                List.of(new RoutePointDto(0, 0), new RoutePointDto(1000, 0)), rid("route")));
    }

    private static TimedPointDto tp(String x, String y, long epochSecond) {
        return new TimedPointDto(new BigDecimal(x), new BigDecimal(y),
                Instant.ofEpochSecond(epochSecond));
    }

    private TimedReviewRequest req(String requestId, String routeId, int routeVersion,
                                   long airspaceVersion, List<TimedPointDto> points) {
        return new TimedReviewRequest(requestId, "tester", routeId, routeVersion,
                airspaceVersion, points);
    }

    private TimedReviewResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), TimedReviewResultDto.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int dedupCount(String requestId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                Integer.class, requestId);
    }

    // ============================ 主流程 ============================

    @Test
    @DisplayName("主流程：100 米 / 2 秒 = 50 米每秒边界放行，快照冻结，历史与当前查询正确")
    void mainFlowClearAndSnapshotFrozen() {
        createRoute("r1");
        MutationResponse res = service.timedReview(req("tr-1", "r1", 1, 0,
                List.of(tp("0", "0", T0), tp("100", "0", T0 + 2))));
        assertFalse(res.replayed());
        TimedReviewResultDto data = dataOf(res);
        assertEquals("CLEAR", data.conclusion());
        assertEquals("tester", data.operator());
        assertEquals(1, data.routeVersion());
        assertEquals(0L, data.airspaceVersion());
        assertTrue(data.current());
        assertEquals(2, data.points().size());
        assertEquals(Instant.ofEpochSecond(T0 + 2), data.points().get(1).arrivalTime());

        // 历史查询：保留冻结的坐标、时刻与原结论
        TimedReviewResultDto hist = service.getTimedReview(data.reviewId());
        assertEquals("CLEAR", hist.conclusion());
        assertNull(hist.current());
        assertEquals(new BigDecimal("100"), hist.points().get(1).x());
        assertEquals(Instant.ofEpochSecond(T0), hist.points().get(0).arrivalTime());

        // 当前查询：版本仍匹配，结论可用
        TimedReviewResultDto cur = service.getCurrentTimedReview("r1");
        assertEquals("CLEAR", cur.conclusion());
        assertTrue(cur.current());
    }

    // ============================ 速度分支 ============================

    @Test
    @DisplayName("100.001 米 / 2 秒超速：422 携带航段号与未舍入速度，不保存审查、不占键")
    void overspeedRejectedWithUnroundedSpeed() {
        createRoute("r1");
        ApiException ex = assertThrows(ApiException.class, () -> service.timedReview(
                req("tr-os", "r1", 1, 0, List.of(tp("0", "0", T0), tp("100.001", "0", T0 + 2)))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("SEGMENT_SPEED_OUT_OF_RANGE", ex.code());
        assertEquals(1, ex.details().get("segmentIndex"));
        assertEquals(50.0005d, (Double) ex.details().get("speedMetersPerSecond"), 1e-9);
        // 不保存通过审查，失败不占 requestId
        assertEquals(0, count("timed_review"));
        assertEquals(0, dedupCount("tr-os"));
        // 同键修正参数后可成功
        MutationResponse ok = service.timedReview(req("tr-os", "r1", 1, 0,
                List.of(tp("0", "0", T0), tp("100", "0", T0 + 2))));
        assertFalse(ok.replayed());
        assertEquals("CLEAR", dataOf(ok).conclusion());
    }

    @Test
    @DisplayName("同位置两点速度 0 与低于 1 米每秒均 422")
    void zeroAndTooSlowSpeedRejected() {
        createRoute("r1");
        ApiException zero = assertThrows(ApiException.class, () -> service.timedReview(
                req(rid("tr"), "r1", 1, 0, List.of(tp("5", "5", T0), tp("5", "5", T0 + 4)))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, zero.status());
        assertEquals(0.0d, (Double) zero.details().get("speedMetersPerSecond"), 1e-12);

        // 1 米 / 2 秒 = 0.5 米每秒，低于下界
        ApiException slow = assertThrows(ApiException.class, () -> service.timedReview(
                req(rid("tr"), "r1", 1, 0, List.of(tp("0", "0", T0), tp("1", "0", T0 + 2)))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, slow.status());
        assertEquals(0.5d, (Double) slow.details().get("speedMetersPerSecond"), 1e-9);
        assertEquals(0, count("timed_review"));
    }

    @Test
    @DisplayName("第二航段越界时返回对应航段号")
    void segmentIndexPointsToFailingSegment() {
        service.createRoute(new RouteCreateRequest("r1",
                List.of(new RoutePointDto(0, 0), new RoutePointDto(1000, 0),
                        new RoutePointDto(2000, 0)), rid("route")));
        // 航段 1 合法（50 米/秒），航段 2 为 100.002 米 / 2 秒 = 50.001 米每秒
        ApiException ex = assertThrows(ApiException.class, () -> service.timedReview(
                req(rid("tr"), "r1", 1, 0, List.of(tp("0", "0", T0), tp("100", "0", T0 + 2),
                        tp("200.002", "0", T0 + 4)))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals(2, ex.details().get("segmentIndex"));
        assertEquals(50.001d, (Double) ex.details().get("speedMetersPerSecond"), 1e-9);
    }

    // ============================ 时刻 / 点数 / 精度 ============================

    @Test
    @DisplayName("时刻重复、倒退、非整秒均 400")
    void invalidTimesRejected() {
        createRoute("r1");
        ApiException dup = assertThrows(ApiException.class, () -> service.timedReview(
                req(rid("tr"), "r1", 1, 0, List.of(tp("0", "0", T0), tp("50", "0", T0)))));
        assertEquals(HttpStatus.BAD_REQUEST, dup.status());
        assertEquals("DUPLICATE_ARRIVAL_TIME", dup.code());

        ApiException backwards = assertThrows(ApiException.class, () -> service.timedReview(
                req(rid("tr"), "r1", 1, 0, List.of(tp("0", "0", T0), tp("50", "0", T0 - 1)))));
        assertEquals(HttpStatus.BAD_REQUEST, backwards.status());
        assertEquals("ARRIVAL_TIME_NOT_INCREASING", backwards.code());

        List<TimedPointDto> millis = List.of(
                tp("0", "0", T0),
                new TimedPointDto(new BigDecimal("50"), new BigDecimal("0"),
                        Instant.ofEpochSecond(T0).plusMillis(500)));
        ApiException notWhole = assertThrows(ApiException.class,
                () -> service.timedReview(req(rid("tr"), "r1", 1, 0, millis)));
        assertEquals(HttpStatus.BAD_REQUEST, notWhole.status());
        assertEquals("ARRIVAL_TIME_NOT_WHOLE_SECONDS", notWhole.code());
    }

    @Test
    @DisplayName("点数与航线不一致 400；坐标超过 3 位小数 400")
    void countMismatchAndPrecisionRejected() {
        createRoute("r1");
        ApiException count = assertThrows(ApiException.class, () -> service.timedReview(
                req(rid("tr"), "r1", 1, 0, List.of(tp("0", "0", T0), tp("50", "0", T0 + 1),
                        tp("100", "0", T0 + 2)))));
        assertEquals(HttpStatus.BAD_REQUEST, count.status());
        assertEquals("POINT_COUNT_MISMATCH", count.code());

        ApiException precision = assertThrows(ApiException.class, () -> service.timedReview(
                req(rid("tr"), "r1", 1, 0, List.of(tp("0.0001", "0", T0), tp("50", "0", T0 + 1)))));
        assertEquals(HttpStatus.BAD_REQUEST, precision.status());
        assertEquals("INVALID_COORDINATE_PRECISION", precision.code());
    }

    @Test
    @DisplayName("航线不存在 404；expectedRouteVersion / expectedAirspaceVersion 不符 409")
    void versionConflicts() {
        ApiException noRoute = assertThrows(ApiException.class, () -> service.timedReview(
                req(rid("tr"), "ghost", 1, 0, List.of(tp("0", "0", T0), tp("50", "0", T0 + 1)))));
        assertEquals(HttpStatus.NOT_FOUND, noRoute.status());

        createRoute("r1");
        ApiException badRouteVersion = assertThrows(ApiException.class, () -> service.timedReview(
                req(rid("tr"), "r1", 9, 0, List.of(tp("0", "0", T0), tp("50", "0", T0 + 1)))));
        assertEquals(HttpStatus.CONFLICT, badRouteVersion.status());
        assertEquals("VERSION_CONFLICT", badRouteVersion.code());

        service.createZone(new ZoneCreateRequest("z1", 5000, 5000, 6000, 6000, rid("zone")));
        ApiException badAirspaceVersion = assertThrows(ApiException.class, () -> service.timedReview(
                req(rid("tr"), "r1", 1, 0, List.of(tp("0", "0", T0), tp("50", "0", T0 + 1)))));
        assertEquals(HttpStatus.CONFLICT, badAirspaceVersion.status());
        assertEquals("VERSION_CONFLICT", badAirspaceVersion.code());
        // 冲突不留下任何审查记录
        assertEquals(0, count("timed_review"));
    }

    // ============================ 禁飞与 STALE ============================

    @Test
    @DisplayName("命中禁飞区 BLOCKED；撤区后当前 STALE，历史保留冻结坐标、时刻与原结论")
    void blockedThenStaleAfterRevoke() {
        createRoute("r1");
        service.createZone(new ZoneCreateRequest("z1", 40, -10, 60, 10, rid("zone")));
        MutationResponse res = service.timedReview(req(rid("tr"), "r1", 1, 1,
                List.of(tp("0", "0", T0), tp("100", "0", T0 + 2))));
        TimedReviewResultDto blocked = dataOf(res);
        assertEquals("BLOCKED", blocked.conclusion());
        assertEquals(List.of("z1"), blocked.hitZoneIds());
        assertEquals("BLOCKED", service.getCurrentTimedReview("r1").conclusion());

        service.revokeZone(new ZoneRevokeRequest("z1", rid("revoke")));
        TimedReviewResultDto stale = service.getCurrentTimedReview("r1");
        assertEquals("STALE", stale.conclusion());
        assertFalse(stale.current());

        TimedReviewResultDto hist = service.getTimedReview(blocked.reviewId());
        assertEquals("BLOCKED", hist.conclusion());
        assertEquals(List.of("z1"), hist.hitZoneIds());
        assertEquals(1L, hist.airspaceVersion());
        assertEquals(new BigDecimal("100"), hist.points().get(1).x());
        assertEquals(Instant.ofEpochSecond(T0 + 2), hist.points().get(1).arrivalTime());
    }

    @Test
    @DisplayName("航线替换后定时审查当前结论 STALE，历史不变；旧版本审查 409")
    void routeReplaceMakesCurrentStale() {
        createRoute("r1");
        MutationResponse res = service.timedReview(req(rid("tr"), "r1", 1, 0,
                List.of(tp("0", "0", T0), tp("100", "0", T0 + 2))));
        String reviewId = dataOf(res).reviewId();

        service.replaceRoute(new RouteReplaceRequest("r1", 1,
                List.of(new RoutePointDto(0, 0), new RoutePointDto(2000, 0)), rid("replace")));
        assertEquals("STALE", service.getCurrentTimedReview("r1").conclusion());
        assertEquals("CLEAR", service.getTimedReview(reviewId).conclusion());

        // 旧航线版本提交定时审查 409
        ApiException staleReq = assertThrows(ApiException.class, () -> service.timedReview(
                req(rid("tr"), "r1", 1, 0, List.of(tp("0", "0", T0), tp("100", "0", T0 + 2)))));
        assertEquals(HttpStatus.CONFLICT, staleReq.status());
    }

    // ============================ 幂等 ============================

    @Test
    @DisplayName("同键同参重放首次完整响应；同键异参 409；异操作者即异参")
    void idempotentReplayAndMismatch() {
        createRoute("r1");
        TimedReviewRequest request = req("tr-idem", "r1", 1, 0,
                List.of(tp("0", "0", T0), tp("100", "0", T0 + 2)));
        MutationResponse first = service.timedReview(request);
        assertFalse(first.replayed());

        MutationResponse replay = service.timedReview(request);
        assertTrue(replay.replayed());
        assertEquals(dataOf(first).reviewId(), dataOf(replay).reviewId());
        assertEquals("CLEAR", dataOf(replay).conclusion());
        assertEquals(1, count("timed_review"));
        assertEquals(1, dedupCount("tr-idem"));

        // 同键异参（坐标不同）409
        ApiException diffPoints = assertThrows(ApiException.class, () -> service.timedReview(
                req("tr-idem", "r1", 1, 0, List.of(tp("0", "0", T0), tp("90", "0", T0 + 2)))));
        assertEquals(HttpStatus.CONFLICT, diffPoints.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", diffPoints.code());

        // 同键仅操作者不同也构成异参 409
        ApiException diffOperator = assertThrows(ApiException.class, () -> service.timedReview(
                new TimedReviewRequest("tr-idem", "other-operator", "r1", 1, 0L,
                        List.of(tp("0", "0", T0), tp("100", "0", T0 + 2)))));
        assertEquals(HttpStatus.CONFLICT, diffOperator.status());
    }

    // ============================ 并发 ============================

    @Test
    @DisplayName("同键同参并发：恰好一次业务生效，其余重放同一 reviewId，不泄露唯一键异常")
    void concurrentSameRequestIdPlaysBackOneOutcome() throws Exception {
        createRoute("r1");
        TimedReviewRequest shared = req("tr-race", "r1", 1, 0,
                List.of(tp("0", "0", T0), tp("100", "0", T0 + 2)));
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.timedReview(shared);
                }));
            }
            int firstCount = 0;
            int replayCount = 0;
            String reviewId = null;
            for (Future<MutationResponse> f : futures) {
                MutationResponse resp = f.get(20, TimeUnit.SECONDS);
                String currentId = dataOf(resp).reviewId();
                if (reviewId == null) {
                    reviewId = currentId;
                }
                assertEquals(reviewId, currentId, "所有并发响应必须携带同一 reviewId");
                if (resp.replayed()) {
                    replayCount++;
                } else {
                    firstCount++;
                }
            }
            assertEquals(1, firstCount, "仅一次请求真正执行业务");
            assertEquals(n - 1, replayCount);
            assertEquals(1, count("timed_review"));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_dedup WHERE request_id = 'tr-race'", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("定时审查与航线替换并发：行锁串行化，替换必成功，审查或成功或 409，无中间态")
    void concurrentTimedReviewAndRouteReplaceIsSerialized() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                String routeId = "trr-" + i;
                createRoute(routeId);
                CyclicBarrier barrier = new CyclicBarrier(2);

                Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.timedReview(req("trc-" + routeId, routeId, 1, 0,
                                List.of(tp("0", "0", T0), tp("100", "0", T0 + 2))));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> replaceFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.replaceRoute(new RouteReplaceRequest(routeId, 1,
                                List.of(new RoutePointDto(0, 0), new RoutePointDto(2000, 0)),
                                "trp-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reviewResult = reviewFuture.get(20, TimeUnit.SECONDS);
                Object replaceResult = replaceFuture.get(20, TimeUnit.SECONDS);
                // 审查不改变航线版本，替换总是能看到版本 1 并成功推进到 2
                assertTrue(replaceResult instanceof MutationResponse, "替换必须成功: " + replaceResult);
                assertEquals(2, jdbc.queryForObject(
                        "SELECT version FROM route WHERE route_id = ?", Integer.class, routeId));

                if (reviewResult instanceof MutationResponse mr) {
                    // 审查先拿锁：冻结的是版本 1 的快照；替换提交后当前结论必须 STALE
                    TimedReviewResultDto dto = dataOf(mr);
                    assertEquals(1, dto.routeVersion());
                    assertEquals("CLEAR", dto.conclusion());
                    assertEquals("STALE", service.getCurrentTimedReview(routeId).conclusion());
                } else {
                    // 替换先提交：旧版本审查必须 409，且不留审查记录
                    ApiException ex = (ApiException) reviewResult;
                    assertEquals(HttpStatus.CONFLICT, ex.status());
                    assertEquals("VERSION_CONFLICT", ex.code());
                    assertEquals(0, jdbc.queryForObject(
                            "SELECT COUNT(*) FROM timed_review WHERE route_id = ?",
                            Integer.class, routeId));
                }
                cleanup();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}