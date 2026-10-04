package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.TimedReviewRequest;
import com.example.starter.api.dto.TimedReviewResultDto;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.api.dto.ZoneRevokeRequest;
import com.example.starter.service.AirspaceReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定时航线可行性审查的 H2 数据库测试（MODE=MySQL）。
 * 覆盖主流程、速度闭区间边界、400/404/409/422 失败分支、
 * 历史冻结与当前 STALE 语义、幂等边界。
 */
@SpringBootTest
class TimedReviewServiceH2Test {

    /** 到达时刻基准：UTC 整秒。 */
    private static final Instant BASE = Instant.parse("2026-10-04T12:00:00Z");

    @Autowired
    private AirspaceReviewService service;
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

    /** 基准时刻加 offset 秒后的 ISO-8601 UTC 整秒时刻。 */
    private static String at(long offsetSeconds) {
        return BASE.plusSeconds(offsetSeconds).toString();
    }

    private static List<RoutePointDto> pts(double... xy) {
        List<RoutePointDto> points = new ArrayList<>(xy.length / 2);
        for (int i = 0; i < xy.length; i += 2) {
            points.add(new RoutePointDto(
                    BigDecimal.valueOf(xy[i]), BigDecimal.valueOf(xy[i + 1])));
        }
        return points;
    }

    private void createRoute(String routeId, List<RoutePointDto> points) {
        service.createRoute(new RouteCreateRequest(routeId, points, "req-" + rid("route")));
    }

    private TimedReviewRequest timedReq(String routeId, int version, long airspace,
                                        List<String> times, String requestId) {
        return new TimedReviewRequest(routeId, version, airspace, "tester", times, requestId);
    }

    private TimedReviewResultDto timedData(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), TimedReviewResultDto.class);
    }

    private int timedReviewCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM timed_review", Integer.class);
    }

    // ============================ 主流程与速度边界 ============================

    @Test
    void feasibleAtExactMaxAndMinSpeedBoundaries() {
        // 两点相距 100 米、间隔 2 秒：速度恰好 50 米/秒（闭区间上界），可通过
        createRoute("t-max", pts(0, 0, 100, 0));
        MutationResponse fast = service.submitTimedReview(
                timedReq("t-max", 1, 0L, List.of(at(0), at(2)), "req-" + rid("timed")));
        assertFalse(fast.replayed());
        TimedReviewResultDto dto = timedData(fast);
        assertEquals("FEASIBLE", dto.conclusion());
        assertTrue(dto.current());
        assertEquals(1, dto.routeVersion());
        assertEquals(0L, dto.airspaceVersion());
        assertEquals("tester", dto.operator());
        assertEquals(List.of(at(0), at(2)), dto.arrivalTimes());
        assertEquals(2, dto.pointsSnapshot().size());
        assertEquals(1, timedReviewCount());

        // 100 米 / 100 秒：速度恰好 1 米/秒（闭区间下界），可通过
        createRoute("t-min", pts(0, 0, 100, 0));
        MutationResponse slow = service.submitTimedReview(
                timedReq("t-min", 1, 0L, List.of(at(0), at(100)), "req-" + rid("timed")));
        assertEquals("FEASIBLE", timedData(slow).conclusion());
    }

    @Test
    void overspeedByFractionOfMeterIsRejected422AndKeyNotConsumed() {
        // 两点相距 100.001 米、间隔 2 秒：速度 50.0005 米/秒，
        // 显示两位小数是 50.00，但不得放行
        createRoute("t-over", pts(0, 0, 100.001, 0));
        String requestId = "req-overspeed-1";
        ApiException ex = assertThrows(ApiException.class, () -> service.submitTimedReview(
                timedReq("t-over", 1, 0L, List.of(at(0), at(2)), requestId)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("SEGMENT_SPEED_OUT_OF_RANGE", ex.code());
        assertNotNull(ex.details());
        assertEquals(1, ex.details().get("segmentIndex"));
        double speed = ((Number) ex.details().get("speedMetersPerSecond")).doubleValue();
        assertTrue(speed > 50.0, "未舍入速度必须大于 50: " + speed);
        assertEquals("50.00", String.format("%.2f", speed), "显示舍入到 50.00 也不能放行");
        // 不保存任何审查结果，失败不占幂等键
        assertEquals(0, timedReviewCount());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?", Integer.class, requestId));
        // 同键修正参数（3 秒到达，约 33.33 米/秒）后可成功
        MutationResponse ok = service.submitTimedReview(
                timedReq("t-over", 1, 0L, List.of(at(0), at(3)), requestId));
        assertFalse(ok.replayed());
        assertEquals("FEASIBLE", timedData(ok).conclusion());
        assertEquals(1, timedReviewCount());
    }

    @Test
    void zeroSpeedAndTooSlowSegmentsAreRejected422() {
        // 相邻同位置两点：速度 0，不合格；航段号指向第一个越界航段
        createRoute("t-zero", pts(0, 0, 0, 0, 100, 0));
        ApiException zero = assertThrows(ApiException.class, () -> service.submitTimedReview(
                timedReq("t-zero", 1, 0L, List.of(at(0), at(1), at(3)), "req-" + rid("timed"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, zero.status());
        assertEquals(1, zero.details().get("segmentIndex"));
        assertEquals(0.0, ((Number) zero.details().get("speedMetersPerSecond")).doubleValue());

        // 1 米 / 2 秒 = 0.5 米/秒，低于下界
        createRoute("t-slow", pts(0, 0, 1, 0));
        ApiException slow = assertThrows(ApiException.class, () -> service.submitTimedReview(
                timedReq("t-slow", 1, 0L, List.of(at(0), at(2)), "req-" + rid("timed"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, slow.status());
        assertEquals(0.5, ((Number) slow.details().get("speedMetersPerSecond")).doubleValue());
        assertEquals(0, timedReviewCount());
    }

    // ============================ 400 失败分支 ============================

    @Test
    void pointCountMismatchIs400() {
        createRoute("t-cnt", pts(0, 0, 100, 0));
        ApiException ex = assertThrows(ApiException.class, () -> service.submitTimedReview(
                timedReq("t-cnt", 1, 0L, List.of(at(0), at(1), at(2)), "req-" + rid("timed"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("TIMED_POINT_COUNT_MISMATCH", ex.code());
        assertEquals(0, timedReviewCount());
    }

    @Test
    void duplicateOrNonIncreasingTimesAre400() {
        createRoute("t-dup", pts(0, 0, 100, 0));
        ApiException dup = assertThrows(ApiException.class, () -> service.submitTimedReview(
                timedReq("t-dup", 1, 0L, List.of(at(0), at(0)), "req-" + rid("timed"))));
        assertEquals(HttpStatus.BAD_REQUEST, dup.status());

        ApiException backwards = assertThrows(ApiException.class, () -> service.submitTimedReview(
                timedReq("t-dup", 1, 0L, List.of(at(5), at(2)), "req-" + rid("timed"))));
        assertEquals(HttpStatus.BAD_REQUEST, backwards.status());
        assertEquals(0, timedReviewCount());
    }

    @Test
    void fractionalSecondMalformedAndNonUtcTimesAre400() {
        createRoute("t-fmt", pts(0, 0, 100, 0));
        // 非整秒（含毫秒）
        ApiException fraction = assertThrows(ApiException.class, () -> service.submitTimedReview(
                timedReq("t-fmt", 1, 0L,
                        List.of("2026-10-04T12:00:00.500Z", at(2)), "req-" + rid("timed"))));
        assertEquals(HttpStatus.BAD_REQUEST, fraction.status());
        // 非 ISO 格式
        ApiException malformed = assertThrows(ApiException.class, () -> service.submitTimedReview(
                timedReq("t-fmt", 1, 0L, List.of("not-a-time", at(2)), "req-" + rid("timed"))));
        assertEquals(HttpStatus.BAD_REQUEST, malformed.status());
        // 非 UTC 偏移
        ApiException offset = assertThrows(ApiException.class, () -> service.submitTimedReview(
                timedReq("t-fmt", 1, 0L,
                        List.of("2026-10-04T12:00:00+08:00", at(2)), "req-" + rid("timed"))));
        assertEquals(HttpStatus.BAD_REQUEST, offset.status());
        assertEquals(0, timedReviewCount());
    }

    @Test
    void moreThan20WaypointsIs400() {
        // 21 个航点、21 个时刻（每段 1 米/秒，速度本身合法）：超过 20 航点上限
        List<RoutePointDto> points = new ArrayList<>();
        List<String> times = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            points.add(new RoutePointDto(BigDecimal.valueOf(i), BigDecimal.ZERO));
            times.add(at(i));
        }
        createRoute("t-many", points);
        ApiException ex = assertThrows(ApiException.class, () -> service.submitTimedReview(
                timedReq("t-many", 1, 0L, times, "req-" + rid("timed"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("TIMED_TOO_MANY_POINTS", ex.code());
        assertEquals(0, timedReviewCount());
    }

    // ============================ 404 / 409 失败分支 ============================

    @Test
    void missingRouteAndVersionConflicts() {
        ApiException missing = assertThrows(ApiException.class, () -> service.submitTimedReview(
                timedReq("ghost", 1, 0L, List.of(at(0), at(2)), "req-" + rid("timed"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        createRoute("t-ver", pts(0, 0, 100, 0));
        ApiException badRouteVersion = assertThrows(ApiException.class,
                () -> service.submitTimedReview(
                        timedReq("t-ver", 9, 0L, List.of(at(0), at(2)), "req-" + rid("timed"))));
        assertEquals(HttpStatus.CONFLICT, badRouteVersion.status());

        service.createZone(new ZoneCreateRequest("vz", -50, -50, -40, -40, "req-" + rid("zone")));
        ApiException badAirspace = assertThrows(ApiException.class,
                () -> service.submitTimedReview(
                        timedReq("t-ver", 1, 0L, List.of(at(0), at(2)), "req-" + rid("timed"))));
        assertEquals(HttpStatus.CONFLICT, badAirspace.status());
        assertEquals(0, timedReviewCount());
    }

    // ============================ 静态禁飞检查与历史冻结 ============================

    @Test
    void staticNoFlyCheckMustPassAndBlockedIsSaved() {
        createRoute("t-blk", pts(0, 10, 100, 10));
        service.createZone(new ZoneCreateRequest("bz", 40, 5, 60, 15, "req-" + rid("zone")));
        // 速度合法（50 米/秒）但命中禁飞区：保存 BLOCKED 结论
        MutationResponse resp = service.submitTimedReview(
                timedReq("t-blk", 1, 1L, List.of(at(0), at(2)), "req-" + rid("timed")));
        TimedReviewResultDto dto = timedData(resp);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(List.of("bz"), dto.hitZoneIds());
        assertEquals(1, timedReviewCount());
        // 历史查询保留原结论
        assertEquals("BLOCKED", service.getTimedReview(dto.reviewId()).conclusion());
        // 撤销禁飞区后当前结论失效，历史不变
        service.revokeZone(new ZoneRevokeRequest("bz", "req-" + rid("revoke")));
        assertEquals("STALE", service.getCurrentTimedReview("t-blk").conclusion());
        assertEquals("BLOCKED", service.getTimedReview(dto.reviewId()).conclusion());
    }

    @Test
    void historyFreezesCoordinatesTimesAndAirspaceVersion() {
        createRoute("t-frz", pts(0, 0, 100, 0));
        String requestId = "req-freeze-1";
        MutationResponse first = service.submitTimedReview(
                timedReq("t-frz", 1, 0L, List.of(at(0), at(2)), requestId));
        TimedReviewResultDto dto = timedData(first);
        assertEquals("FEASIBLE", dto.conclusion());

        // 替换航线坐标并新建禁飞区：定时计划不得重绑新坐标
        service.replaceRoute(new RouteReplaceRequest(
                "t-frz", 1, pts(0, 50, 100, 50), "req-" + rid("replace")));
        service.createZone(new ZoneCreateRequest("fz2", -50, -50, -40, -40, "req-" + rid("zone")));

        // 历史：冻结的坐标、时刻、空域版本与原结论
        TimedReviewResultDto history = service.getTimedReview(dto.reviewId());
        assertEquals("FEASIBLE", history.conclusion());
        assertNull(history.current());
        assertEquals(1, history.routeVersion());
        assertEquals(0L, history.airspaceVersion());
        assertEquals(List.of(at(0), at(2)), history.arrivalTimes());
        assertEquals(0, BigDecimal.ZERO.compareTo(history.pointsSnapshot().get(0).y()));
        assertEquals(0, BigDecimal.valueOf(100).compareTo(history.pointsSnapshot().get(1).x()));

        // 当前：版本失配只能 STALE
        TimedReviewResultDto current = service.getCurrentTimedReview("t-frz");
        assertEquals("STALE", current.conclusion());
        assertFalse(current.current());

        // 同键重放仍返回首次完整响应
        MutationResponse replay = service.submitTimedReview(
                timedReq("t-frz", 1, 0L, List.of(at(0), at(2)), requestId));
        assertTrue(replay.replayed());
        assertEquals(dto.reviewId(), timedData(replay).reviewId());
        assertEquals(1, timedReviewCount());

        // 必须提交新的计划：新版本 + 新空域版本 + 新 requestId
        MutationResponse newPlan = service.submitTimedReview(
                timedReq("t-frz", 2, 1L, List.of(at(0), at(2)), "req-" + rid("timed")));
        assertEquals("FEASIBLE", timedData(newPlan).conclusion());
        assertEquals("FEASIBLE", service.getCurrentTimedReview("t-frz").conclusion());
        assertEquals(2, timedReviewCount());
    }

    // ============================ 幂等 ============================

    @Test
    void idempotentReplayAndStructuredParamMismatch() {
        createRoute("t-idem", pts(0, 0, 100, 0));
        String requestId = "req-timed-idem-1";
        TimedReviewRequest req = timedReq("t-idem", 1, 0L, List.of(at(0), at(2)), requestId);
        MutationResponse first = service.submitTimedReview(req);
        assertFalse(first.replayed());

        // 同键同参重放：返回首次完整响应，不产生新记录
        MutationResponse replay = service.submitTimedReview(
                timedReq("t-idem", 1, 0L, List.of(at(0), at(2)), requestId));
        assertTrue(replay.replayed());
        assertEquals(timedData(first).reviewId(), timedData(replay).reviewId());
        assertEquals(1, timedReviewCount());

        // 同键异参（操作者不同）→ 409
        ApiException diffOperator = assertThrows(ApiException.class,
                () -> service.submitTimedReview(new TimedReviewRequest(
                        "t-idem", 1, 0L, "other-operator", List.of(at(0), at(2)), requestId)));
        assertEquals(HttpStatus.CONFLICT, diffOperator.status());
        // 同键异参（时刻不同）→ 409
        ApiException diffTimes = assertThrows(ApiException.class,
                () -> service.submitTimedReview(
                        timedReq("t-idem", 1, 0L, List.of(at(0), at(3)), requestId)));
        assertEquals(HttpStatus.CONFLICT, diffTimes.status());
        // 同键用于不同类型操作 → 409
        ApiException diffKind = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("other-route", pts(0, 0, 10, 0), requestId)));
        assertEquals(HttpStatus.CONFLICT, diffKind.status());
        assertEquals(1, timedReviewCount());
    }

    @Test
    void missingTimedReviewQueriesAre404() {
        ApiException history = assertThrows(ApiException.class,
                () -> service.getTimedReview("tr_missing"));
        assertEquals(HttpStatus.NOT_FOUND, history.status());
        ApiException current = assertThrows(ApiException.class,
                () -> service.getCurrentTimedReview("never-timed"));
        assertEquals(HttpStatus.NOT_FOUND, current.status());
    }
    // ============================ 并发 ============================

    @Test
    void concurrentTimedReviewAndRouteReplaceIsSerialized() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                String routeId = "trr-" + i;
                createRoute(routeId, pts(0, 0, 100, 0));
                CyclicBarrier barrier = new CyclicBarrier(2);

                Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.submitTimedReview(new TimedReviewRequest(
                                routeId, 1, 0L, "tester", List.of(at(0), at(2)),
                                "req-trv-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> replaceFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.replaceRoute(new RouteReplaceRequest(
                                routeId, 1, pts(0, 10, 100, 10), "req-trp-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object replaceResult = replaceFuture.get(15, TimeUnit.SECONDS);
                assertTrue(replaceResult instanceof MutationResponse, "替换必须成功一次");

                if (reviewResult instanceof MutationResponse mr) {
                    // 定时审查先拿锁：冻结的是版本 1 的旧坐标 y=0
                    TimedReviewResultDto dto = timedData(mr);
                    assertEquals(1, dto.routeVersion());
                    assertEquals(0, BigDecimal.ZERO.compareTo(dto.pointsSnapshot().get(0).y()));
                } else {
                    // 替换先提交：航线版本已是 2，旧版本定时审查必须 409
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) reviewResult).status());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentTimedReviewAndZoneCreateNeverMixesVersions() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 10; i++) {
                String routeId = "tzr-" + i;
                String zoneId = "tzz-" + i;
                int y = 500 + i * 100;
                createRoute(routeId, pts(0, y, 100, y));
                long versionBefore = jdbc.queryForObject(
                        "SELECT global_version FROM airspace_meta WHERE id=1", Long.class);
                CyclicBarrier barrier = new CyclicBarrier(2);

                Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.submitTimedReview(new TimedReviewRequest(
                                routeId, 1, versionBefore, "tester", List.of(at(0), at(2)),
                                "req-tzv-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> zoneFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.createZone(new ZoneCreateRequest(
                                zoneId, 40, y - 5, 60, y + 5, "req-tzz-" + zoneId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object zoneResult = zoneFuture.get(15, TimeUnit.SECONDS);
                assertTrue(zoneResult instanceof MutationResponse, "建区必须成功");

                if (reviewResult instanceof MutationResponse mr) {
                    // 定时审查先拿锁：看到的是建区前一致快照，只能 FEASIBLE 且携带旧空域版本
                    TimedReviewResultDto dto = timedData(mr);
                    assertEquals("FEASIBLE", dto.conclusion(),
                            "携带旧空域版本的定时审查不得使用新建区域判定");
                    assertEquals(versionBefore, dto.airspaceVersion());
                } else {
                    // 建区先提交：空域版本已推进，定时审查必须 409
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) reviewResult).status());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameTimedRequestIdPlaysBackOneOutcome() throws Exception {
        createRoute("t-race", pts(0, 0, 100, 0));
        String requestId = "req-timed-race-1";
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.submitTimedReview(new TimedReviewRequest(
                            "t-race", 1, 0L, "tester", List.of(at(0), at(2)), requestId));
                }));
            }
            int firstCount = 0;
            int replayCount = 0;
            String reviewId = null;
            for (Future<MutationResponse> f : futures) {
                MutationResponse resp = f.get(15, TimeUnit.SECONDS);
                if (resp.replayed()) {
                    replayCount++;
                } else {
                    firstCount++;
                }
                String currentId = timedData(resp).reviewId();
                if (reviewId == null) {
                    reviewId = currentId;
                } else {
                    assertEquals(reviewId, currentId, "并发同键必须重放同一审查记录");
                }
            }
            assertEquals(1, firstCount, "仅一次请求真正执行业务");
            assertEquals(n - 1, replayCount);
            assertEquals(1, timedReviewCount());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                    Integer.class, requestId));
        } finally {
            pool.shutdownNow();
        }
    }
}