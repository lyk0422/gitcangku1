package com.example.starter;

import com.example.starter.api.ApiException;
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
 * 覆盖主流程、失败分支、版本失效（STALE）、幂等边界与真实并发互斥。
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
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
    }

    private String rid(String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    private static List<RoutePointDto> pts(int... xy) {
        return java.util.stream.IntStream.range(0, xy.length / 2)
                .mapToObj(i -> new RoutePointDto(xy[2 * i], xy[2 * i + 1]))
                .toList();
    }

    private ZoneCreateRequest zoneReq(String zoneId, int xMin, int yMin, int xMax, int yMax) {
        return new ZoneCreateRequest(zoneId, xMin, yMin, xMax, yMax, "req-" + rid("zone"));
    }

    private ReviewResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    // ============================ 主流程 ============================

    @Test
    void clearThenBlockedThenClearAfterRevoke() {
        // 航线水平穿过 y=10
        MutationResponse route = service.createRoute(
                new RouteCreateRequest("r1", pts(0, 10, 100, 10), "req-" + rid("route")));
        assertFalse(route.replayed());
        assertEquals(1, objectMapper.convertValue(route.data(), Map.class).get("version"));

        // 初始空域版本 0，无禁飞区 → CLEAR
        MutationResponse r1 = service.review(
                new ReviewRequest("r1", 1, 0L, "req-" + rid("review")));
        ReviewResultDto c1 = dataOf(r1);
        assertEquals("CLEAR", c1.conclusion());
        assertTrue(c1.hitZoneIds().isEmpty());
        assertEquals(0L, c1.airspaceVersion());
        assertTrue(c1.current());

        // 创建与航线相交的禁飞区后，空域版本变为 1
        MutationResponse z1 = service.createZone(zoneReq("z1", 40, 5, 60, 15));
        assertEquals(1, ((Number) objectMapper.convertValue(z1.data(), Map.class)
                .get("airspaceVersion")).intValue());

        // 用旧空域版本审核 → 409
        ApiException oldVersion = assertThrows(ApiException.class, () -> service.review(
                new ReviewRequest("r1", 1, 0L, "req-" + rid("review"))));
        assertEquals(HttpStatus.CONFLICT, oldVersion.status());

        // 用新版本审核 → BLOCKED
        MutationResponse r2 = service.review(
                new ReviewRequest("r1", 1, 1L, "req-" + rid("review")));
        ReviewResultDto b1 = dataOf(r2);
        assertEquals("BLOCKED", b1.conclusion());
        assertEquals(List.of("z1"), b1.hitZoneIds());

        // 撤销禁飞区，空域版本变为 2，再审核 → CLEAR
        service.revokeZone(new ZoneRevokeRequest("z1", "req-" + rid("revoke")));
        MutationResponse r3 = service.review(
                new ReviewRequest("r1", 1, 2L, "req-" + rid("review")));
        assertEquals("CLEAR", dataOf(r3).conclusion());

        // 历史记录保留原结论
        assertEquals("BLOCKED", service.getReview(b1.reviewId()).conclusion());
        assertNull(service.getReview(b1.reviewId()).current());
    }

    @Test
    void blockedReturnsAllHitZoneIdsSortedAndDeduplicated() {
        service.createRoute(
                new RouteCreateRequest("r2", pts(0, 10, 100, 10), "req-" + rid("route")));
        // 三个相交区域 + 一个不相交区域，id 故意乱序
        service.createZone(zoneReq("zeta", 40, 5, 60, 15));
        service.createZone(zoneReq("alpha", 45, 8, 55, 12));
        service.createZone(zoneReq("mid", 48, 9, 52, 11));
        service.createZone(zoneReq("faraway", -50, -50, -40, -40));

        MutationResponse resp = service.review(
                new ReviewRequest("r2", 1, 4L, "req-" + rid("review")));
        assertEquals(List.of("alpha", "mid", "zeta"), dataOf(resp).hitZoneIds());
    }

    @Test
    void crossingSegmentWithEndpointsOutsideIsBlocked() {
        service.createRoute(
                new RouteCreateRequest("r3", pts(0, 0, 100, 20), "req-" + rid("route")));
        service.createZone(zoneReq("box", 40, 5, 60, 15));
        MutationResponse resp = service.review(
                new ReviewRequest("r3", 1, 1L, "req-" + rid("review")));
        assertEquals("BLOCKED", dataOf(resp).conclusion());
        assertEquals(List.of("box"), dataOf(resp).hitZoneIds());
    }

    @Test
    void boundaryTouchIsBlocked() {
        // 航段恰与区域底边接触
        service.createRoute(
                new RouteCreateRequest("r4", pts(0, 5, 100, 5), "req-" + rid("route")));
        service.createZone(zoneReq("edge", 40, 5, 60, 15));
        MutationResponse resp = service.review(
                new ReviewRequest("r4", 1, 1L, "req-" + rid("review")));
        assertEquals("BLOCKED", dataOf(resp).conclusion());
    }

    // ============================ 失败分支 ============================

    @Test
    void degenerateRectangleRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest("bad", 10, 10, 10, 20, "req-" + rid("zone"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
    }

    @Test
    void duplicateZoneRejected() {
        service.createZone(zoneReq("dup", 0, 0, 10, 10));
        ApiException ex = assertThrows(ApiException.class, () -> service.createZone(
                zoneReq("dup", 1, 1, 11, 11)));
        assertEquals(HttpStatus.CONFLICT, ex.status());
    }

    @Test
    void revokeMissingOrTwiceRejected() {
        ApiException missing = assertThrows(ApiException.class, () -> service.revokeZone(
                new ZoneRevokeRequest("ghost", "req-" + rid("revoke"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        service.createZone(zoneReq("once", 0, 0, 10, 10));
        service.revokeZone(new ZoneRevokeRequest("once", "req-" + rid("revoke")));
        ApiException twice = assertThrows(ApiException.class, () -> service.revokeZone(
                new ZoneRevokeRequest("once", "req-" + rid("revoke2"))));
        assertEquals(HttpStatus.CONFLICT, twice.status());
    }

    @Test
    void identicalPointsRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("same", pts(5, 5, 5, 5), "req-" + rid("route"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
    }

    @Test
    void repeatedButNotAllIdenticalPointsAllowedAndZeroLengthSegmentHandled() {
        // 中间重复点合法（至少两个点不同）；零长度线段的几何判定也必须正确
        service.createRoute(new RouteCreateRequest("dup",
                pts(0, 10, 0, 10, 100, 10), "req-" + rid("route")));
        service.createZone(zoneReq("zz", 40, 5, 60, 15));
        MutationResponse resp = service.review(
                new ReviewRequest("dup", 1, 1L, "req-" + rid("review")));
        assertEquals("BLOCKED", dataOf(resp).conclusion());
        assertEquals(3, dataOf(resp).pointsSnapshot().size());
    }

    @Test
    void replayedReviewKeepsOriginalConclusionAfterAirspaceChanges() {
        service.createRoute(
                new RouteCreateRequest("hr", pts(0, 10, 100, 10), "req-" + rid("route")));
        String requestId = "replay-after-change";
        MutationResponse first = service.review(
                new ReviewRequest("hr", 1, 0L, requestId));
        assertEquals("CLEAR", dataOf(first).conclusion());
        // 环境发生变化（建区）后，同键重放仍返回首次的不可变原结论
        service.createZone(zoneReq("hz", 40, 5, 60, 15));
        MutationResponse replay = service.review(
                new ReviewRequest("hr", 1, 0L, requestId));
        assertTrue(replay.replayed());
        ReviewResultDto dto = dataOf(replay);
        assertEquals("CLEAR", dto.conclusion());
        assertEquals(0L, dto.airspaceVersion());
        assertEquals(dataOf(first).reviewId(), dto.reviewId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class));
    }

    @Test
    void reviewMissingRouteIs404AndWrongVersionsAre409() {
        ApiException noRoute = assertThrows(ApiException.class, () -> service.review(
                new ReviewRequest("ghost", 1, 0L, "req-" + rid("review"))));
        assertEquals(HttpStatus.NOT_FOUND, noRoute.status());

        service.createRoute(
                new RouteCreateRequest("rv", pts(0, 0, 10, 10), "req-" + rid("route")));
        ApiException badRouteVersion = assertThrows(ApiException.class, () -> service.review(
                new ReviewRequest("rv", 9, 0L, "req-" + rid("review"))));
        assertEquals(HttpStatus.CONFLICT, badRouteVersion.status());

        service.createZone(zoneReq("z", 0, 0, 100, 100));
        ApiException badAirspaceVersion = assertThrows(ApiException.class, () -> service.review(
                new ReviewRequest("rv", 1, 0L, "req-" + rid("review"))));
        assertEquals(HttpStatus.CONFLICT, badAirspaceVersion.status());
    }

    @Test
    void replaceRouteRejectsWrongExpectedVersionAndAdvancesVersion() {
        service.createRoute(
                new RouteCreateRequest("rp", pts(0, 0, 10, 10), "req-" + rid("route")));
        ApiException wrong = assertThrows(ApiException.class, () -> service.replaceRoute(
                new RouteReplaceRequest("rp", 7, pts(1, 1, 2, 2), "req-" + rid("replace"))));
        assertEquals(HttpStatus.CONFLICT, wrong.status());

        ApiException missing = assertThrows(ApiException.class, () -> service.replaceRoute(
                new RouteReplaceRequest("ghost", 1, pts(1, 1, 2, 2), "req-" + rid("replace"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        MutationResponse ok = service.replaceRoute(
                new RouteReplaceRequest("rp", 1, pts(0, 0, 100, 100), "req-" + rid("replace")));
        assertEquals(2, objectMapper.convertValue(ok.data(), Map.class).get("version"));
        // 替换后旧航线版本审核 409
        ApiException stale = assertThrows(ApiException.class, () -> service.review(
                new ReviewRequest("rp", 1, 0L, "req-" + rid("review"))));
        assertEquals(HttpStatus.CONFLICT, stale.status());
        // 新版本可审核
        MutationResponse current = service.review(
                new ReviewRequest("rp", 2, 0L, "req-" + rid("review")));
        assertEquals("CLEAR", dataOf(current).conclusion());
    }

    // ============================ STALE 语义 ============================

    @Test
    void currentReviewBecomesStaleAfterZoneChangeAndRouteReplace() {
        service.createRoute(
                new RouteCreateRequest("st", pts(0, 10, 100, 10), "req-" + rid("route")));
        MutationResponse rev = service.review(
                new ReviewRequest("st", 1, 0L, "req-" + rid("review")));
        ReviewResultDto dto = dataOf(rev);
        assertEquals("CLEAR", dto.conclusion());

        // 刚完成：当前结论可用
        ReviewResultDto current1 = service.getCurrentReview("st");
        assertEquals("CLEAR", current1.conclusion());
        assertTrue(current1.current());

        // 空域变化：当前结论必须 STALE，旧 CLEAR 不能当成当前通过
        service.createZone(zoneReq("nz", -90, -90, -80, -80));
        ReviewResultDto stale1 = service.getCurrentReview("st");
        assertEquals("STALE", stale1.conclusion());
        assertFalse(stale1.current());
        // 历史结论不变
        assertEquals("CLEAR", service.getReview(dto.reviewId()).conclusion());

        // 用新空域版本重新审核 → CLEAR，恢复可用
        MutationResponse rev2 = service.review(
                new ReviewRequest("st", 1, 1L, "req-" + rid("review")));
        assertTrue(dataOf(rev2).current());
        assertEquals("CLEAR", service.getCurrentReview("st").conclusion());

        // 航线替换后再次 STALE
        service.replaceRoute(new RouteReplaceRequest(
                "st", 1, pts(0, 10, 100, 10), "req-" + rid("replace")));
        assertEquals("STALE", service.getCurrentReview("st").conclusion());

        // 无审核记录查询 404
        ApiException ex = assertThrows(ApiException.class,
                () -> service.getCurrentReview("never-reviewed"));
        assertEquals(HttpStatus.NOT_FOUND, ex.status());
    }

    @Test
    void blockedCurrentGoesStaleAndHistoricalBlockedStaysBlocked() {
        service.createRoute(
                new RouteCreateRequest("bs", pts(0, 10, 100, 10), "req-" + rid("route")));
        service.createZone(zoneReq("bz", 40, 5, 60, 15));
        MutationResponse rev = service.review(
                new ReviewRequest("bs", 1, 1L, "req-" + rid("review")));
        ReviewResultDto blocked = dataOf(rev);
        assertEquals("BLOCKED", blocked.conclusion());
        assertEquals("BLOCKED", service.getCurrentReview("bs").conclusion());

        service.revokeZone(new ZoneRevokeRequest("bz", "req-" + rid("revoke")));
        // 撤销后旧 BLOCKED 对当前查询同样失效
        assertEquals("STALE", service.getCurrentReview("bs").conclusion());
        assertEquals("BLOCKED", service.getReview(blocked.reviewId()).conclusion());
    }

    // ============================ 幂等 ============================

    @Test
    void sameRequestReplaysOriginalResult() {
        String requestId = "fixed-replay-1";
        ZoneCreateRequest first = new ZoneCreateRequest("iz", 0, 0, 10, 10, requestId);
        MutationResponse r1 = service.createZone(first);
        assertFalse(r1.replayed());

        // 同键同参重放：返回首次结果，不再次推进空域版本
        MutationResponse r2 = service.createZone(
                new ZoneCreateRequest("iz", 0, 0, 10, 10, requestId));
        assertTrue(r2.replayed());
        // JSON 重放后整数节点反序列化为 Integer，按数值比较
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
        service.createZone(new ZoneCreateRequest("iz2", 0, 0, 10, 10, requestId));
        // 同键不同参数 → 409
        ApiException diffParams = assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest("iz2", 1, 1, 11, 11, requestId)));
        assertEquals(HttpStatus.CONFLICT, diffParams.status());
        // 同键用于不同类型操作 → 409
        ApiException diffKind = assertThrows(ApiException.class, () -> service.revokeZone(
                new ZoneRevokeRequest("iz2", requestId)));
        assertEquals(HttpStatus.CONFLICT, diffKind.status());
    }

    @Test
    void failedRequestDoesNotConsumeIdempotencyKey() {
        String requestId = "fixed-fail-1";
        // 首次因非退化校验失败
        ApiException failed = assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest("fz", 5, 5, 5, 5, requestId)));
        assertEquals(HttpStatus.BAD_REQUEST, failed.status());
        // 失败回滚不占键
        assertNull(reviewDedupOrNull(requestId));
        // 键未被占用：同键可成功用于合法请求
        MutationResponse ok = service.createZone(
                new ZoneCreateRequest("fz", 0, 0, 10, 10, requestId));
        assertFalse(ok.replayed());
        // 成功后才占键
        assertNotNull(reviewDedupOrNull(requestId));
    }

    @Test
    void reviewIdempotentReplayKeepsImmutableResult() {
        service.createRoute(
                new RouteCreateRequest("ir", pts(0, 10, 100, 10), "req-" + rid("route")));
        String requestId = "fixed-review-1";
        ReviewRequest req = new ReviewRequest("ir", 1, 0L, requestId);
        MutationResponse first = service.review(req);
        MutationResponse replay = service.review(
                new ReviewRequest("ir", 1, 0L, requestId));
        assertTrue(replay.replayed());
        assertEquals(dataOf(first).reviewId(), dataOf(replay).reviewId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class));
    }

    private Object reviewDedupOrNull(String requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT request_id FROM request_dedup WHERE request_id = ?", requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ============================ 并发 ============================

    @Test
    void concurrentZoneCreateAndReviewNeverMixesVersionsAndZones() throws Exception {
        int iterations = 12;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < iterations; i++) {
                String routeId = "cr-" + i;
                String zoneId = "cz-" + i;
                // 每轮使用互不相交的 y 几何，避免历史轮次已提交区域命中本轮新航线
                int y = 10 + i * 100;
                service.createRoute(new RouteCreateRequest(
                        routeId, pts(0, y, 100, y), "req-" + rid("route")));
                long versionBefore = jdbc.queryForObject(
                        "SELECT global_version FROM airspace_meta WHERE id=1", Long.class);

                CyclicBarrier barrier = new CyclicBarrier(2);
                // 审核方：明确提交建区前的空域版本
                Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.review(new ReviewRequest(
                                routeId, 1, versionBefore, "req-review-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                // 建区方：创建恰好封锁本轮航线的区域
                Future<Object> zoneFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.createZone(new ZoneCreateRequest(
                                zoneId, 40, y - 5, 60, y + 5, "req-zone-" + zoneId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object zoneResult = zoneFuture.get(15, TimeUnit.SECONDS);
                assertTrue(zoneResult instanceof MutationResponse, "建区必须成功");

                if (reviewResult instanceof MutationResponse mr) {
                    // 审核先拿锁：它看到的是建区前的一致快照，结论只能是 CLEAR，
                    // 绝不能出现“空域版本是旧的却命中新区域”的 BLOCKED
                    ReviewResultDto dto = dataOf(mr);
                    assertEquals("CLEAR", dto.conclusion(),
                            "并发审核携带旧空域版本时不得使用新建区域判定");
                    assertEquals(versionBefore, dto.airspaceVersion());
                } else {
                    // 建区先提交：版本已推进，审核必须 409，不能产生任何结论
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
                service.createRoute(new RouteCreateRequest(
                        routeId, pts(0, 10, 100, 10), "req-" + rid("route")));
                CyclicBarrier barrier = new CyclicBarrier(2);

                Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.review(new ReviewRequest(
                                routeId, 1, 0L, "req-rv-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> replaceFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.replaceRoute(new RouteReplaceRequest(
                                routeId, 1, pts(0, 0, 100, 0), "req-rp-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object replaceResult = replaceFuture.get(15, TimeUnit.SECONDS);
                assertTrue(replaceResult instanceof MutationResponse, "替换必须成功一次");

                if (reviewResult instanceof MutationResponse mr) {
                    // 审核先拿锁：点列快照必须是版本 1 的旧点 y=10
                    ReviewResultDto dto = dataOf(mr);
                    assertEquals(1, dto.routeVersion());
                    assertEquals(10, dto.pointsSnapshot().get(0).y());
                } else {
                    // 替换先提交：航线版本已是 2，旧版本审核必须 409
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) reviewResult).status());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // 稳定性复核轮：同键并发场景多次验证均要求恰好一次业务生效
    @Test
    void concurrentSameRequestIdPlaysBackOneOutcome() throws Exception {
        // 同键同参并发：只能有一次业务生效，另一方重放同一结果
        String requestId = "concurrent-same-key";
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.createZone(
                            new ZoneCreateRequest("ck", 0, 0, 10, 10, requestId));
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
                    "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?", Integer.class, requestId));
            // 同键异参事后仍 409
            ApiException ex = assertThrows(ApiException.class, () -> service.createZone(
                    new ZoneCreateRequest("ck", 1, 1, 12, 12, requestId)));
            assertEquals(HttpStatus.CONFLICT, ex.status());
        } finally {
            pool.shutdownNow();
        }
    }
}
