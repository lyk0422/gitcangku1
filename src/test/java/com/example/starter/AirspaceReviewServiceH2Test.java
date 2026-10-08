package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.CurveRouteCreateRequest;
import com.example.starter.api.dto.CurveRouteReplaceRequest;
import com.example.starter.api.dto.CurveSegmentDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.api.dto.ZoneRevokeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * 禁飞区审查业务的 H2 数据库测试（MODE=MySQL）。
 * 覆盖直线/二次曲线两类航线的主流程、失败分支、版本失效（STALE）、
 * 幂等边界（含操作者与期望版本）与真实并发互斥。
 */
@SpringBootTest
class AirspaceReviewServiceH2Test {

    @Autowired
    private com.example.starter.service.AirspaceReviewService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    private final AtomicInteger seq = new AtomicInteger();

    // 新协议要求所有写请求带非空操作者；直线用例统一用该固定操作者
    private static final String OPERATOR = "tester";

    @BeforeEach
    void cleanBefore() {
        cleanup();
    }

    @AfterEach
    void cleanAfter() {
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_curve_point");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
    }

    private String rid(String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    private String req(String prefix) {
        return "req-" + rid(prefix);
    }

    private static List<RoutePointDto> pts(int... xy) {
        return java.util.stream.IntStream.range(0, xy.length / 2)
                .mapToObj(i -> new RoutePointDto(xy[2 * i], xy[2 * i + 1]))
                .toList();
    }

    private static RoutePointDto p(int x, int y) {
        return new RoutePointDto(x, y);
    }

    private static CurveSegmentDto seg(int x0, int y0, int x1, int y1, int x2, int y2) {
        return new CurveSegmentDto(p(x0, y0), p(x1, y1), p(x2, y2));
    }

    private ZoneCreateRequest zoneReq(String zoneId, int xMin, int yMin, int xMax, int yMax) {
        return new ZoneCreateRequest(zoneId, xMin, yMin, xMax, yMax, OPERATOR, req("zone"));
    }

    private static ZoneCreateRequest zc(String zoneId, int xMin, int yMin, int xMax, int yMax,
                                        String requestId) {
        return new ZoneCreateRequest(zoneId, xMin, yMin, xMax, yMax, OPERATOR, requestId);
    }

    private static ZoneRevokeRequest zr(String zoneId, String requestId) {
        return new ZoneRevokeRequest(zoneId, OPERATOR, requestId);
    }

    private static RouteCreateRequest rc(String routeId, List<RoutePointDto> points,
                                         String requestId) {
        return new RouteCreateRequest(routeId, points, OPERATOR, requestId);
    }

    private static RouteReplaceRequest rr(String routeId, int expectedVersion,
                                          List<RoutePointDto> points, String requestId) {
        return new RouteReplaceRequest(routeId, expectedVersion, points, OPERATOR, requestId);
    }

    private static ReviewRequest rv(String routeId, int routeVersion, long airspaceVersion,
                                    String requestId) {
        return new ReviewRequest(routeId, routeVersion, airspaceVersion, OPERATOR, requestId);
    }

    private static CurveRouteCreateRequest cc(String routeId, List<CurveSegmentDto> segments,
                                              String requestId) {
        return new CurveRouteCreateRequest(routeId, segments, OPERATOR, requestId);
    }

    private static CurveRouteReplaceRequest cr(String routeId, int expectedVersion,
                                               List<CurveSegmentDto> segments, String requestId) {
        return new CurveRouteReplaceRequest(routeId, expectedVersion, segments, OPERATOR,
                requestId);
    }

    private ReviewResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    // ============================ 直线主流程 ============================

    @Test
    void clearThenBlockedThenClearAfterRevoke() {
        MutationResponse route = service.createRoute(
                rc("r1", pts(0, 10, 100, 10), req("route")));
        assertFalse(route.replayed());
        assertEquals(1, objectMapper.convertValue(route.data(), Map.class).get("version"));

        MutationResponse r1 = service.review(rv("r1", 1, 0L, req("review")));
        ReviewResultDto c1 = dataOf(r1);
        assertEquals("CLEAR", c1.conclusion());
        assertEquals("LINE", c1.routeKind());
        assertTrue(c1.hitZoneIds().isEmpty());
        assertEquals(0L, c1.airspaceVersion());
        assertEquals(OPERATOR, c1.operator());
        assertTrue(c1.current());

        MutationResponse z1 = service.createZone(zoneReq("z1", 40, 5, 60, 15));
        assertEquals(1, ((Number) objectMapper.convertValue(z1.data(), Map.class)
                .get("airspaceVersion")).intValue());

        ApiException oldVersion = assertThrows(ApiException.class,
                () -> service.review(rv("r1", 1, 0L, req("review"))));
        assertEquals(HttpStatus.CONFLICT, oldVersion.status());

        MutationResponse r2 = service.review(rv("r1", 1, 1L, req("review")));
        ReviewResultDto b1 = dataOf(r2);
        assertEquals("BLOCKED", b1.conclusion());
        assertEquals(List.of("z1"), b1.hitZoneIds());

        service.revokeZone(zr("z1", req("revoke")));
        MutationResponse r3 = service.review(rv("r1", 1, 2L, req("review")));
        assertEquals("CLEAR", dataOf(r3).conclusion());

        assertEquals("BLOCKED", service.getReview(b1.reviewId()).conclusion());
        assertNull(service.getReview(b1.reviewId()).current());
    }

    @Test
    void blockedReturnsAllHitZoneIdsSortedAndDeduplicated() {
        service.createRoute(rc("r2", pts(0, 10, 100, 10), req("route")));
        service.createZone(zoneReq("zeta", 40, 5, 60, 15));
        service.createZone(zoneReq("alpha", 45, 8, 55, 12));
        service.createZone(zoneReq("mid", 48, 9, 52, 11));
        service.createZone(zoneReq("faraway", -50, -50, -40, -40));

        MutationResponse resp = service.review(rv("r2", 1, 4L, req("review")));
        assertEquals(List.of("alpha", "mid", "zeta"), dataOf(resp).hitZoneIds());
    }

    @Test
    void crossingSegmentWithEndpointsOutsideIsBlocked() {
        service.createRoute(rc("r3", pts(0, 0, 100, 20), req("route")));
        service.createZone(zoneReq("box", 40, 5, 60, 15));
        MutationResponse resp = service.review(rv("r3", 1, 1L, req("review")));
        assertEquals("BLOCKED", dataOf(resp).conclusion());
        assertEquals(List.of("box"), dataOf(resp).hitZoneIds());
    }

    @Test
    void boundaryTouchIsBlocked() {
        service.createRoute(rc("r4", pts(0, 5, 100, 5), req("route")));
        service.createZone(zoneReq("edge", 40, 5, 60, 15));
        MutationResponse resp = service.review(rv("r4", 1, 1L, req("review")));
        assertEquals("BLOCKED", dataOf(resp).conclusion());
    }

    // ============================ 直线失败分支 ============================

    @Test
    void degenerateRectangleRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> service.createZone(
                zc("bad", 10, 10, 10, 20, req("zone"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
    }

    @Test
    void duplicateZoneRejected() {
        service.createZone(zoneReq("dup", 0, 0, 10, 10));
        ApiException ex = assertThrows(ApiException.class, () -> service.createZone(
                zc("dup", 1, 1, 11, 11, req("zone"))));
        assertEquals(HttpStatus.CONFLICT, ex.status());
    }

    @Test
    void revokeMissingOrTwiceRejected() {
        ApiException missing = assertThrows(ApiException.class,
                () -> service.revokeZone(zr("ghost", req("revoke"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        service.createZone(zoneReq("once", 0, 0, 10, 10));
        service.revokeZone(zr("once", req("revoke")));
        ApiException twice = assertThrows(ApiException.class,
                () -> service.revokeZone(zr("once", req("revoke2"))));
        assertEquals(HttpStatus.CONFLICT, twice.status());
    }

    @Test
    void identicalPointsRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> service.createRoute(
                rc("same", pts(5, 5, 5, 5), req("route"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
    }

    @Test
    void repeatedButNotAllIdenticalPointsAllowedAndZeroLengthSegmentHandled() {
        service.createRoute(rc("dup", pts(0, 10, 0, 10, 100, 10), req("route")));
        service.createZone(zoneReq("zz", 40, 5, 60, 15));
        MutationResponse resp = service.review(rv("dup", 1, 1L, req("review")));
        assertEquals("BLOCKED", dataOf(resp).conclusion());
        assertEquals(3, dataOf(resp).pointsSnapshot().size());
    }

    // ============================ 直线重放/版本/STALE ============================

    @Test
    void replayedReviewKeepsOriginalConclusionAfterAirspaceChanges() {
        service.createRoute(rc("hr", pts(0, 10, 100, 10), req("route")));
        String requestId = "replay-after-change";
        MutationResponse first = service.review(rv("hr", 1, 0L, requestId));
        assertEquals("CLEAR", dataOf(first).conclusion());
        service.createZone(zoneReq("hz", 40, 5, 60, 15));
        MutationResponse replay = service.review(rv("hr", 1, 0L, requestId));
        assertTrue(replay.replayed());
        ReviewResultDto dto = dataOf(replay);
        assertEquals("CLEAR", dto.conclusion());
        assertEquals(0L, dto.airspaceVersion());
        assertEquals(dataOf(first).reviewId(), dto.reviewId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class));
    }

    @Test
    void reviewMissingRouteIs404AndWrongVersionsAre409() {
        ApiException noRoute = assertThrows(ApiException.class,
                () -> service.review(rv("ghost", 1, 0L, req("review"))));
        assertEquals(HttpStatus.NOT_FOUND, noRoute.status());

        service.createRoute(rc("rv", pts(0, 0, 10, 10), req("route")));
        ApiException badRouteVersion = assertThrows(ApiException.class,
                () -> service.review(rv("rv", 9, 0L, req("review"))));
        assertEquals(HttpStatus.CONFLICT, badRouteVersion.status());

        service.createZone(zoneReq("z", 0, 0, 100, 100));
        ApiException badAirspaceVersion = assertThrows(ApiException.class,
                () -> service.review(rv("rv", 1, 0L, req("review"))));
        assertEquals(HttpStatus.CONFLICT, badAirspaceVersion.status());
    }

    @Test
    void replaceRouteRejectsWrongExpectedVersionAndAdvancesVersion() {
        service.createRoute(rc("rp", pts(0, 0, 10, 10), req("route")));
        ApiException wrong = assertThrows(ApiException.class, () -> service.replaceRoute(
                rr("rp", 7, pts(1, 1, 2, 2), req("replace"))));
        assertEquals(HttpStatus.CONFLICT, wrong.status());

        ApiException missing = assertThrows(ApiException.class, () -> service.replaceRoute(
                rr("ghost", 1, pts(1, 1, 2, 2), req("replace"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        MutationResponse ok = service.replaceRoute(
                rr("rp", 1, pts(0, 0, 100, 100), req("replace")));
        assertEquals(2, objectMapper.convertValue(ok.data(), Map.class).get("version"));
        ApiException stale = assertThrows(ApiException.class,
                () -> service.review(rv("rp", 1, 0L, req("review"))));
        assertEquals(HttpStatus.CONFLICT, stale.status());
        MutationResponse current = service.review(rv("rp", 2, 0L, req("review")));
        assertEquals("CLEAR", dataOf(current).conclusion());
    }

    @Test
    void currentReviewBecomesStaleAfterZoneChangeAndRouteReplace() {
        service.createRoute(rc("st", pts(0, 10, 100, 10), req("route")));
        MutationResponse rev = service.review(rv("st", 1, 0L, req("review")));
        ReviewResultDto dto = dataOf(rev);
        assertEquals("CLEAR", dto.conclusion());

        ReviewResultDto current1 = service.getCurrentReview("st");
        assertEquals("CLEAR", current1.conclusion());
        assertTrue(current1.current());

        service.createZone(zoneReq("nz", -90, -90, -80, -80));
        ReviewResultDto stale1 = service.getCurrentReview("st");
        assertEquals("STALE", stale1.conclusion());
        assertFalse(stale1.current());
        assertEquals("CLEAR", service.getReview(dto.reviewId()).conclusion());

        MutationResponse rev2 = service.review(rv("st", 1, 1L, req("review")));
        assertTrue(dataOf(rev2).current());
        assertEquals("CLEAR", service.getCurrentReview("st").conclusion());

        service.replaceRoute(rr("st", 1, pts(0, 10, 100, 10), req("replace")));
        assertEquals("STALE", service.getCurrentReview("st").conclusion());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.getCurrentReview("never-reviewed"));
        assertEquals(HttpStatus.NOT_FOUND, ex.status());
    }

    @Test
    void blockedCurrentGoesStaleAndHistoricalBlockedStaysBlocked() {
        service.createRoute(rc("bs", pts(0, 10, 100, 10), req("route")));
        service.createZone(zoneReq("bz", 40, 5, 60, 15));
        MutationResponse rev = service.review(rv("bs", 1, 1L, req("review")));
        ReviewResultDto blocked = dataOf(rev);
        assertEquals("BLOCKED", blocked.conclusion());
        assertEquals("BLOCKED", service.getCurrentReview("bs").conclusion());

        service.revokeZone(zr("bz", req("revoke")));
        assertEquals("STALE", service.getCurrentReview("bs").conclusion());
        assertEquals("BLOCKED", service.getReview(blocked.reviewId()).conclusion());
    }

    // ============================ 直线幂等 ============================

    @Test
    void sameRequestReplaysOriginalResult() {
        String requestId = "fixed-replay-1";
        MutationResponse r1 = service.createZone(zc("iz", 0, 0, 10, 10, requestId));
        assertFalse(r1.replayed());

        MutationResponse r2 = service.createZone(zc("iz", 0, 0, 10, 10, requestId));
        assertTrue(r2.replayed());
        assertEquals(1, ((Number) objectMapper.convertValue(r2.data(), Map.class)
                .get("airspaceVersion")).intValue());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id=1", Long.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM no_fly_zone", Integer.class));
    }

    @Test
    void sameRequestWithDifferentParamsConflicts() {
        String requestId = "fixed-conflict-1";
        service.createZone(zc("iz2", 0, 0, 10, 10, requestId));
        ApiException diffParams = assertThrows(ApiException.class, () -> service.createZone(
                zc("iz2", 1, 1, 11, 11, requestId)));
        assertEquals(HttpStatus.CONFLICT, diffParams.status());
        ApiException diffKind = assertThrows(ApiException.class,
                () -> service.revokeZone(zr("iz2", requestId)));
        assertEquals(HttpStatus.CONFLICT, diffKind.status());
    }

    @Test
    void differentOperatorWithSameKeyConflicts() {
        String requestId = "fixed-operator-1";
        service.createZone(zc("op", 0, 0, 10, 10, requestId));
        // 同键但操作者不同：属于全参数不同，必须 409，不能重放
        ApiException ex = assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest("op", 0, 0, 10, 10, "someone-else", requestId)));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", ex.code());
        // 首次操作者被原样保留
        String saved = jdbc.queryForObject(
                "SELECT operator FROM request_dedup WHERE request_id = ?", String.class,
                requestId);
        assertEquals(OPERATOR, saved);
    }

    @Test
    void failedRequestDoesNotConsumeIdempotencyKey() {
        String requestId = "fixed-fail-1";
        ApiException failed = assertThrows(ApiException.class, () -> service.createZone(
                zc("fz", 5, 5, 5, 5, requestId)));
        assertEquals(HttpStatus.BAD_REQUEST, failed.status());
        assertNull(reviewDedupOrNull(requestId));
        MutationResponse ok = service.createZone(zc("fz", 0, 0, 10, 10, requestId));
        assertFalse(ok.replayed());
        assertNotNull(reviewDedupOrNull(requestId));
    }

    @Test
    void reviewIdempotentReplayKeepsImmutableResult() {
        service.createRoute(rc("ir", pts(0, 10, 100, 10), req("route")));
        String requestId = "fixed-review-1";
        MutationResponse first = service.review(rv("ir", 1, 0L, requestId));
        MutationResponse replay = service.review(rv("ir", 1, 0L, requestId));
        assertTrue(replay.replayed());
        assertEquals(dataOf(first).reviewId(), dataOf(replay).reviewId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class));
    }

    private Object reviewDedupOrNull(String requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT request_id FROM request_dedup WHERE request_id = ?", requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ============================ 直线并发 ============================

    @Test
    void concurrentZoneCreateAndReviewNeverMixesVersionsAndZones() throws Exception {
        int iterations = 12;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < iterations; i++) {
                String routeId = "cr-" + i;
                String zoneId = "cz-" + i;
                int y = 10 + i * 100;
                service.createRoute(rc(routeId, pts(0, y, 100, y), req("route")));
                long versionBefore = jdbc.queryForObject(
                        "SELECT global_version FROM airspace_meta WHERE id=1", Long.class);

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.review(rv(routeId, 1, versionBefore,
                                "req-review-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> zoneFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.createZone(zc(zoneId, 40, y - 5, 60, y + 5,
                                "req-zone-" + zoneId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object zoneResult = zoneFuture.get(15, TimeUnit.SECONDS);
                assertTrue(zoneResult instanceof MutationResponse, "建区必须成功");

                if (reviewResult instanceof MutationResponse mr) {
                    ReviewResultDto dto = dataOf(mr);
                    assertEquals("CLEAR", dto.conclusion(),
                            "并发审核携带旧空域版本时不得使用新建区域判定");
                    assertEquals(versionBefore, dto.airspaceVersion());
                } else {
                    ApiException ex = (ApiException) reviewResult;
                    assertEquals(HttpStatus.CONFLICT, ex.status());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentRouteReplaceAndReviewIsSerialized() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                String routeId = "rr-" + i;
                service.createRoute(rc(routeId, pts(0, 10, 100, 10), req("route")));
                CyclicBarrier barrier = new CyclicBarrier(2);

                Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.review(rv(routeId, 1, 0L, "req-rv-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> replaceFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.replaceRoute(
                                rr(routeId, 1, pts(0, 0, 100, 0), "req-rp-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object replaceResult = replaceFuture.get(15, TimeUnit.SECONDS);
                assertTrue(replaceResult instanceof MutationResponse, "替换必须成功一次");

                if (reviewResult instanceof MutationResponse mr) {
                    ReviewResultDto dto = dataOf(mr);
                    assertEquals(1, dto.routeVersion());
                    assertEquals(10, dto.pointsSnapshot().get(0).y());
                } else {
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) reviewResult).status());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameRequestIdPlaysBackOneOutcome() throws Exception {
        String requestId = "concurrent-same-key";
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.createZone(zc("ck", 0, 0, 10, 10, requestId));
                }));
            }
            int firstCount = 0;
            int replayCount = 0;
            for (Future<MutationResponse> f : futures) {
                MutationResponse resp = f.get(15, TimeUnit.SECONDS);
                if (resp.replayed()) {
                    replayCount++;
                } else {
                    firstCount++;
                }
            }
            assertEquals(1, firstCount, "仅一次请求真正执行业务");
            assertEquals(n - 1, replayCount);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM no_fly_zone", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?", Integer.class,
                    requestId));
            ApiException ex = assertThrows(ApiException.class, () -> service.createZone(
                    zc("ck", 1, 1, 12, 12, requestId)));
            assertEquals(HttpStatus.CONFLICT, ex.status());
        } finally {
            pool.shutdownNow();
        }
    }

    // ============================ 二次曲线：题干合成场景 ============================

    /** 题干合成曲线 P0=(0,0),P1=(5,10),P2=(10,0) 的单段航线。 */
    private List<CurveSegmentDto> syntheticCurve() {
        return List.of(seg(0, 0, 5, 10, 10, 0));
    }

    @Test
    void syntheticCurveTangentToTopTouchingRectangleIsBlocked() {
        // 曲线在 t=0.5 到达最高点 (5,5)，与矩形 [4,6]×[5,6] 单点相切 → BLOCKED
        service.createCurveRoute(cc("q1", syntheticCurve(), req("curve")));
        service.createZone(zoneReq("touch", 4, 5, 6, 6));
        MutationResponse resp = service.review(rv("q1", 1, 1L, req("review")));
        ReviewResultDto dto = dataOf(resp);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(List.of("touch"), dto.hitZoneIds());
        assertEquals("CURVE", dto.routeKind());
        // 审核冻结完整控制点（含不被连线经过的 P1）
        assertEquals(1, dto.curveSnapshot().size());
        assertEquals(p(5, 10), dto.curveSnapshot().get(0).p1());
        assertTrue(dto.pointsSnapshot().isEmpty());
    }

    @Test
    void syntheticCurveClearsWhenRectangleLowerBoundRaisedToSix() {
        // 同一曲线，矩形下界改为 6：最高点 y=5 在矩形外，无实际相交 → CLEAR
        service.createCurveRoute(cc("q2", syntheticCurve(), req("curve")));
        service.createZone(zoneReq("above", 4, 6, 6, 8));
        MutationResponse resp = service.review(rv("q2", 1, 1L, req("review")));
        assertEquals("CLEAR", dataOf(resp).conclusion());
        assertTrue(dataOf(resp).hitZoneIds().isEmpty());
    }

    @Test
    void controlPointInsideZoneButCurveOutsideMustBeClear() {
        // 控制点 (50,100) 在矩形内，但曲线顶点仅 y=50：不能用控制点包围盒判阻断
        service.createCurveRoute(cc("q3",
                List.of(seg(0, 0, 50, 100, 100, 0)), req("curve")));
        service.createZone(zoneReq("cpbox", 45, 60, 55, 90));
        MutationResponse resp = service.review(rv("q3", 1, 1L, req("review")));
        assertEquals("CLEAR", dataOf(resp).conclusion());
    }

    @Test
    void degenerateControlPointActsAsStraightSegment() {
        // P1 取中点（三点共线）：曲线退化为 (0,10)→(100,10) 的直线
        service.createCurveRoute(cc("q4",
                List.of(seg(0, 10, 50, 10, 100, 10)), req("curve")));
        service.createZone(zoneReq("linebox", 40, 5, 60, 15));
        assertEquals("BLOCKED",
                dataOf(service.review(rv("q4", 1, 1L, req("review")))).conclusion());
    }

    @Test
    void multiSegmentCurveRequiresJoiningEndpointsAndAllowsUpToFive() {
        // 两段首尾相接：第一段 P2=(10,0) 必须等于第二段 P0
        List<CurveSegmentDto> joined = List.of(
                seg(0, 0, 5, 10, 10, 0),
                seg(10, 0, 15, -10, 20, 0));
        service.createCurveRoute(cc("q5", joined, req("curve")));
        service.createZone(zoneReq("second", 14, -8, 16, -4));
        // 第二段向下弯到 (15,-5)，命中下方矩形
        assertEquals("BLOCKED",
                dataOf(service.review(rv("q5", 1, 1L, req("review")))).conclusion());

        // 首尾端点不一致 → 400
        List<CurveSegmentDto> broken = List.of(
                seg(0, 0, 5, 10, 10, 0),
                seg(11, 0, 15, -10, 20, 0));
        ApiException notContinuous = assertThrows(ApiException.class,
                () -> service.createCurveRoute(cc("q6", broken, req("curve"))));
        assertEquals(HttpStatus.BAD_REQUEST, notContinuous.status());
        assertEquals("CURVE_NOT_CONTINUOUS", notContinuous.code());

        // 6 段超过上限（直接构造绕过 Bean Validation 的 @Size）→ 400
        List<CurveSegmentDto> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(seg(i * 10, 0, i * 10 + 5, 10, i * 10 + 10, 0));
        }
        ApiException tooMany = assertThrows(ApiException.class,
                () -> service.createCurveRoute(cc("q7", six, req("curve"))));
        assertEquals(HttpStatus.BAD_REQUEST, tooMany.status());
        assertEquals("INVALID_CURVE_SEGMENT_COUNT", tooMany.code());
    }

    // ============================ 二次曲线：修订/版本/STALE ============================

    @Test
    void curveRevisionAdvancesVersionFreezesSnapshotAndGoesStale() {
        service.createCurveRoute(cc("cv", syntheticCurve(), req("curve")));
        service.createZone(zoneReq("tz", 4, 5, 6, 6));
        MutationResponse blocked = service.review(rv("cv", 1, 1L, req("review")));
        ReviewResultDto blockedDto = dataOf(blocked);
        assertEquals("BLOCKED", blockedDto.conclusion());
        assertEquals(p(5, 10), blockedDto.curveSnapshot().get(0).p1());

        // 修订曲线：控制点改为不经过矩形（顶点 y=2）
        List<CurveSegmentDto> revised = List.of(seg(0, 0, 5, 4, 10, 0));
        MutationResponse replaced = service.replaceCurveRoute(
                cr("cv", 1, revised, req("curve-replace")));
        assertEquals(2, objectMapper.convertValue(replaced.data(), Map.class).get("version"));

        // 旧版本审核必须 409，当前查询 STALE
        ApiException oldVersion = assertThrows(ApiException.class,
                () -> service.review(rv("cv", 1, 1L, req("review2"))));
        assertEquals(HttpStatus.CONFLICT, oldVersion.status());
        assertEquals("STALE", service.getCurrentReview("cv").conclusion());
        // 历史审核保留旧结论与旧的完整控制点
        ReviewResultDto historical = service.getReview(blockedDto.reviewId());
        assertEquals("BLOCKED", historical.conclusion());
        assertEquals(p(5, 10), historical.curveSnapshot().get(0).p1());

        // 用新版本审核 → CLEAR，且冻结修订后的控制点
        MutationResponse clear = service.review(rv("cv", 2, 1L, req("review3")));
        ReviewResultDto clearDto = dataOf(clear);
        assertEquals("CLEAR", clearDto.conclusion());
        assertEquals(p(5, 4), clearDto.curveSnapshot().get(0).p1());
        assertEquals("CLEAR", service.getCurrentReview("cv").conclusion());

        // 持久化的当前几何已更新为修订版本（控制点 P1=4）
        Integer p1y = jdbc.queryForObject(
                "SELECT y FROM route_curve_point WHERE route_id = 'cv' AND seg_seq = 0 AND pt_seq = 1",
                Integer.class);
        assertEquals(4, p1y);
        // 历史快照列独立保留旧控制点 P1=10
        String snapshot = jdbc.queryForObject(
                "SELECT curve_snapshot FROM review WHERE review_id = ?", String.class,
                blockedDto.reviewId());
        assertTrue(snapshot.contains("\"y\":10"));
    }

    @Test
    void curveReplaceChecksExpectedVersionAndRouteKind() {
        service.createCurveRoute(cc("ck1", syntheticCurve(), req("curve")));
        // 期望版本错误 → 409
        ApiException wrongVersion = assertThrows(ApiException.class,
                () -> service.replaceCurveRoute(cr("ck1", 9, syntheticCurve(), req("cr1"))));
        assertEquals(HttpStatus.CONFLICT, wrongVersion.status());
        // 航线不存在 → 404
        ApiException missing = assertThrows(ApiException.class,
                () -> service.replaceCurveRoute(cr("ghost", 1, syntheticCurve(), req("cr2"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        // 直线航线不能用曲线接口修订
        service.createRoute(rc("lk", pts(0, 0, 10, 0), req("route")));
        ApiException kindMismatch = assertThrows(ApiException.class,
                () -> service.replaceCurveRoute(cr("lk", 1, syntheticCurve(), req("cr3"))));
        assertEquals(HttpStatus.CONFLICT, kindMismatch.status());
        assertEquals("ROUTE_KIND_MISMATCH", kindMismatch.code());

        // 曲线航线不能用直线接口替换
        ApiException lineOnCurve = assertThrows(ApiException.class,
                () -> service.replaceRoute(rr("ck1", 1, pts(0, 0, 10, 0), req("cr4"))));
        assertEquals(HttpStatus.CONFLICT, lineOnCurve.status());
        assertEquals("ROUTE_KIND_MISMATCH", lineOnCurve.code());
    }

    @Test
    void curveCreateDuplicateIdConflictsAndReplaysSnapshot() {
        String requestId = "curve-create-idem";
        MutationResponse first = service.createCurveRoute(
                cc("ci", syntheticCurve(), requestId));
        assertFalse(first.replayed());
        // 同 routeId 再建 → 409
        ApiException dup = assertThrows(ApiException.class,
                () -> service.createCurveRoute(cc("ci", syntheticCurve(), req("curve2"))));
        assertEquals(HttpStatus.CONFLICT, dup.status());
        // 同键同参重放：返回首次结果
        MutationResponse replay = service.createCurveRoute(
                cc("ci", syntheticCurve(), requestId));
        assertTrue(replay.replayed());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM route WHERE route_id = 'ci'", Integer.class));
        // 同键改期望版本无关（创建无版本字段），改控制点即异参 → 409
        ApiException diffParams = assertThrows(ApiException.class,
                () -> service.createCurveRoute(
                        cc("ci", List.of(seg(0, 0, 5, 8, 10, 0)), requestId)));
        assertEquals(HttpStatus.CONFLICT, diffParams.status());
    }

    @Test
    void concurrentCurveRevisionAndReviewIsSerialized() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                String routeId = "crv-" + i;
                service.createCurveRoute(cc(routeId, syntheticCurve(), req("curve")));
                service.createZone(zoneReq("z-" + i, 4, 5, 6, 6));
                CyclicBarrier barrier = new CyclicBarrier(2);

                Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.review(rv(routeId, 1, 1L, "req-crv-rv-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                // 修订为不到达矩形的曲线（顶点 y=2）
                Future<Object> replaceFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.replaceCurveRoute(cr(routeId, 1,
                                List.of(seg(0, 0, 5, 4, 10, 0)),
                                "req-crv-rp-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object replaceResult = replaceFuture.get(15, TimeUnit.SECONDS);
                assertTrue(replaceResult instanceof MutationResponse, "曲线修订必须成功一次");

                if (reviewResult instanceof MutationResponse mr) {
                    // 审核先拿锁：冻结的必须是版本 1 的旧控制点 P1.y=10，结论 BLOCKED
                    ReviewResultDto dto = dataOf(mr);
                    assertEquals(1, dto.routeVersion());
                    assertEquals(10, dto.curveSnapshot().get(0).p1().y());
                    assertEquals("BLOCKED", dto.conclusion());
                } else {
                    // 修订先提交：航线版本已是 2，旧版本审核必须 409
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) reviewResult).status());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // === APPEND POINT ===
}
