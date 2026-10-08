package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.CurvePointDto;
import com.example.starter.api.dto.CurveRouteCreateRequest;
import com.example.starter.api.dto.CurveRouteReplaceRequest;
import com.example.starter.api.dto.CurveSegmentDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 二次曲线航线登记/替换/审核的 H2（MODE=MySQL）真实事务测试。
 * 覆盖题干相切场景、控制点冻结、STALE、幂等重放与曲线替换并发。
 * （第二轮执行用于重复验证真实并发用例的稳定性。）
 */
@SpringBootTest
class QuadraticRouteH2Test {

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
        jdbc.update("DELETE FROM route_curve_segment");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
    }

    private String req(String prefix) {
        return "req-" + prefix + "-" + seq.incrementAndGet();
    }

    private static CurvePointDto cp(int x, int y) {
        return new CurvePointDto(x, y);
    }

    private static CurveSegmentDto seg(int x0, int y0, int x1, int y1, int x2, int y2) {
        return new CurveSegmentDto(cp(x0, y0), cp(x1, y1), cp(x2, y2));
    }

    /** 题干合成曲线 P0=(0,0), P1=(5,10), P2=(10,0)。 */
    private static List<CurveSegmentDto> arch() {
        return List.of(seg(0, 0, 5, 10, 10, 0));
    }

    private ReviewResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    @Test
    void tangentRectangleBlockedAndLoweredRectangleClears() {
        service.createCurveRoute(new CurveRouteCreateRequest(
                "c1", arch(), "dispatcher-a", req("create")));

        // 矩形 [4,6]×[5,6]：曲线在 (5,5) 单点相切下边界 → BLOCKED
        service.createZone(new ZoneCreateRequest(
                "zt", 4, 5, 6, 6, req("zone")));
        MutationResponse blocked = service.review(
                new ReviewRequest("c1", 1, 1L, req("review")));
        ReviewResultDto bDto = dataOf(blocked);
        assertEquals("BLOCKED", bDto.conclusion());
        assertEquals(List.of("zt"), bDto.hitZoneIds());
        assertEquals("QUADRATIC", bDto.kind());
        assertTrue(bDto.pointsSnapshot().isEmpty());
        assertEquals(arch(), bDto.segmentsSnapshot(), "审核必须冻结完整 P0/P1/P2 控制点");

        // 撤销相切矩形、空域版本推进到 2
        service.revokeZone(new ZoneRevokeRequest("zt", req("revoke")));
        // 下界改为 6：矩形 [4,6]×[6,7]，曲线最高仅 5 → CLEAR
        service.createZone(new ZoneCreateRequest(
                "zc", 4, 6, 6, 7, req("zone")));
        MutationResponse clear = service.review(
                new ReviewRequest("c1", 1, 3L, req("review")));
        assertEquals("CLEAR", dataOf(clear).conclusion());
        assertTrue(dataOf(clear).hitZoneIds().isEmpty());

        // 历史审核结论保留
        assertEquals("BLOCKED", service.getReview(bDto.reviewId()).conclusion());
    }

    @Test
    void multiSegmentRouteRequiresContinuousEndpointsAndAllowsFive() {
        // 两段首尾相接：s1 P2=(10,0) == s2 P0
        List<CurveSegmentDto> two = List.of(
                seg(0, 0, 5, 10, 10, 0),
                seg(10, 0, 15, 10, 20, 0));
        MutationResponse created = service.createCurveRoute(
                new CurveRouteCreateRequest("c2", two, "op", req("create")));
        assertEquals(1, objectMapper.convertValue(created.data(),
                java.util.Map.class).get("version"));

        // 不连续：第二段起点不等于第一段终点 → 400
        List<CurveSegmentDto> broken = List.of(
                seg(0, 0, 5, 10, 10, 0),
                seg(11, 0, 15, 10, 20, 0));
        ApiException gap = assertThrows(ApiException.class, () -> service.createCurveRoute(
                new CurveRouteCreateRequest("c2bad", broken, "op", req("create"))));
        assertEquals(HttpStatus.BAD_REQUEST, gap.status());
        assertEquals("CURVE_NOT_CONTINUOUS", gap.code());

        // 最多 5 段：6 段在 Bean Validation 层拒绝（直接构造请求走服务校验也拒绝）
        List<CurveSegmentDto> six = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> seg(i * 10, 0, i * 10 + 5, 10, i * 10 + 10, 0))
                .toList();
        ApiException tooMany = assertThrows(ApiException.class, () -> service.createCurveRoute(
                new CurveRouteCreateRequest("c2six", six, "op", req("create"))));
        assertEquals(HttpStatus.BAD_REQUEST, tooMany.status());

        // 控制点共线退化为直线的曲线合法
        List<CurveSegmentDto> flat = List.of(seg(0, 10, 50, 10, 100, 10));
        service.createCurveRoute(new CurveRouteCreateRequest("c2flat", flat, "op", req("create")));
        service.createZone(new ZoneCreateRequest("zf", 40, 5, 60, 15, req("zone")));
        MutationResponse resp = service.review(
                new ReviewRequest("c2flat", 1, 1L, req("review")));
        assertEquals("BLOCKED", dataOf(resp).conclusion());
    }

    @Test
    void duplicateCurveRouteConflictsAndMissingReviewIs404() {
        service.createCurveRoute(new CurveRouteCreateRequest(
                "c3", arch(), "op", req("create")));
        ApiException dup = assertThrows(ApiException.class, () -> service.createCurveRoute(
                new CurveRouteCreateRequest("c3", arch(), "op", req("create"))));
        assertEquals(HttpStatus.CONFLICT, dup.status());

        ApiException missing = assertThrows(ApiException.class, () -> service.review(
                new ReviewRequest("ghost", 1, 0L, req("review"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    @Test
    void replaceCurveAdvancesVersionAndFreezesSnapshot() {
        service.createCurveRoute(new CurveRouteCreateRequest(
                "c4", arch(), "op", req("create")));
        service.createZone(new ZoneCreateRequest("zt", 4, 5, 6, 6, req("zone")));

        // 版本 1 审核 → BLOCKED，控制点 P1=(5,10)
        MutationResponse first = service.review(
                new ReviewRequest("c4", 1, 1L, req("review")));
        ReviewResultDto blocked = dataOf(first);
        assertEquals("BLOCKED", blocked.conclusion());
        assertEquals(10, blocked.segmentsSnapshot().get(0).p1().y());

        // 错误 expectedVersion → 409
        ApiException wrong = assertThrows(ApiException.class, () -> service.replaceCurveRoute(
                new CurveRouteReplaceRequest("c4", 9, arch(), "op", req("replace"))));
        assertEquals(HttpStatus.CONFLICT, wrong.status());

        // 替换为压低的曲线 P1=(5,9)（最高仅 4.5，不再相交）→ 版本 2
        List<CurveSegmentDto> lowered = List.of(seg(0, 0, 5, 9, 10, 0));
        MutationResponse replaced = service.replaceCurveRoute(
                new CurveRouteReplaceRequest("c4", 1, lowered, "op", req("replace")));
        assertEquals(2, objectMapper.convertValue(replaced.data(),
                java.util.Map.class).get("version"));

        // 旧版本审核 409；当前查询 STALE
        ApiException oldVersion = assertThrows(ApiException.class, () -> service.review(
                new ReviewRequest("c4", 1, 1L, req("review"))));
        assertEquals(HttpStatus.CONFLICT, oldVersion.status());
        assertEquals("STALE", service.getCurrentReview("c4").conclusion());
        // 历史记录仍冻结旧控制点与 BLOCKED 结论
        assertEquals("BLOCKED", service.getReview(blocked.reviewId()).conclusion());
        assertEquals(10, service.getReview(blocked.reviewId())
                .segmentsSnapshot().get(0).p1().y());

        // 新版本审核 → CLEAR
        MutationResponse second = service.review(
                new ReviewRequest("c4", 2, 1L, req("review")));
        ReviewResultDto clear = dataOf(second);
        assertEquals("CLEAR", clear.conclusion());
        assertEquals(9, clear.segmentsSnapshot().get(0).p1().y());
    }

    @Test
    void currentReviewGoesStaleAfterZoneRevoke() {
        service.createCurveRoute(new CurveRouteCreateRequest(
                "c5", arch(), "op", req("create")));
        service.createZone(new ZoneCreateRequest("zt", 4, 5, 6, 6, req("zone")));
        MutationResponse rev = service.review(
                new ReviewRequest("c5", 1, 1L, req("review")));
        assertEquals("BLOCKED", dataOf(rev).conclusion());

        service.revokeZone(new ZoneRevokeRequest("zt", req("revoke")));
        ReviewResultDto stale = service.getCurrentReview("c5");
        assertEquals("STALE", stale.conclusion());
        assertFalse(stale.current());
    }

    // ============================ 幂等 ============================

    @Test
    void curveIdempotencyReplaysAndConflictsAndFailureKeepsKey() {
        String requestId = "curve-idem-1";
        CurveRouteCreateRequest first =
                new CurveRouteCreateRequest("c6", arch(), "op1", requestId);
        MutationResponse r1 = service.createCurveRoute(first);
        assertFalse(r1.replayed());
        // 同键同参重放
        MutationResponse r2 = service.createCurveRoute(
                new CurveRouteCreateRequest("c6", arch(), "op1", requestId));
        assertTrue(r2.replayed());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM route WHERE route_id = 'c6'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM route_curve_segment WHERE route_id = 'c6'", Integer.class));

        // 同键改参（控制点变化）→ 409
        ApiException changedParams = assertThrows(ApiException.class, () ->
                service.createCurveRoute(new CurveRouteCreateRequest("c6",
                        List.of(seg(0, 0, 5, 9, 10, 0)), "op1", requestId)));
        assertEquals(HttpStatus.CONFLICT, changedParams.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", changedParams.code());
        // 同键改操作者（全参数结构化比较）同样 409
        ApiException changedOperator = assertThrows(ApiException.class, () ->
                service.createCurveRoute(new CurveRouteCreateRequest("c6", arch(), "op2", requestId)));
        assertEquals(HttpStatus.CONFLICT, changedOperator.status());

        // 失败请求不占键：不连续曲线先失败，随后同键用于合法登记
        String failKey = "curve-idem-fail";
        ApiException failed = assertThrows(ApiException.class, () ->
                service.createCurveRoute(new CurveRouteCreateRequest("c6b",
                        List.of(seg(0, 0, 5, 10, 10, 0), seg(12, 0, 15, 10, 20, 0)),
                        "op1", failKey)));
        assertEquals(HttpStatus.BAD_REQUEST, failed.status());
        assertNullDedup(failKey);
        MutationResponse ok = service.createCurveRoute(
                new CurveRouteCreateRequest("c6b", arch(), "op1", failKey));
        assertFalse(ok.replayed());
        assertFalse(jdbc.queryForList(
                "SELECT request_id FROM request_dedup WHERE request_id = ?", failKey).isEmpty());
    }

    @Test
    void reviewReplayKeepsFrozenControlPointsAfterCurveRevision() {
        service.createCurveRoute(new CurveRouteCreateRequest(
                "c7", arch(), "op", req("create")));
        service.createZone(new ZoneCreateRequest("zt", 4, 5, 6, 6, req("zone")));
        String reviewKey = "curve-review-idem";
        MutationResponse first = service.review(
                new ReviewRequest("c7", 1, 1L, reviewKey));
        assertEquals("BLOCKED", dataOf(first).conclusion());

        // 曲线修订推进版本后，同键重放仍返回首次冻结结果
        service.replaceCurveRoute(new CurveRouteReplaceRequest(
                "c7", 1, List.of(seg(0, 0, 5, 9, 10, 0)), "op", req("replace")));
        MutationResponse replay = service.review(
                new ReviewRequest("c7", 1, 1L, reviewKey));
        assertTrue(replay.replayed());
        ReviewResultDto dto = dataOf(replay);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(1, dto.routeVersion());
        assertEquals(10, dto.segmentsSnapshot().get(0).p1().y());
        assertEquals(dataOf(first).reviewId(), dto.reviewId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class));
    }

    private void assertNullDedup(String requestId) {
        assertTrue(jdbc.queryForList(
                "SELECT request_id FROM request_dedup WHERE request_id = ?", requestId).isEmpty());
    }

    // ============================ 并发 ============================

    @Test
    void concurrentSameCurveCreateKeyPlaysBackOneOutcome() throws Exception {
        String requestId = "curve-concurrent-key";
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.createCurveRoute(
                            new CurveRouteCreateRequest("c8", arch(), "op", requestId));
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
            assertEquals(1, firstCount, "仅一次曲线登记真正生效");
            assertEquals(n - 1, replayCount);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM route WHERE route_id = 'c8'", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM route_curve_segment WHERE route_id = 'c8'",
                    Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                    Integer.class, requestId));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentCurveReplaceAndReviewIsSerialized() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                String routeId = "c9-" + i;
                service.createCurveRoute(new CurveRouteCreateRequest(
                        routeId, arch(), "op", req("create")));
                service.createZone(new ZoneCreateRequest(
                        "z-" + i, 4, 5, 6, 6, req("zone")));
                // 每轮新建一个区域，空域版本随轮次推进
                long airspaceVersion = i + 1L;
                CyclicBarrier barrier = new CyclicBarrier(2);

                Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.review(
                                new ReviewRequest(routeId, 1, airspaceVersion, "rv-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> replaceFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.replaceCurveRoute(new CurveRouteReplaceRequest(
                                routeId, 1, List.of(seg(0, 0, 5, 9, 10, 0)),
                                "op", "rp-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object replaceResult = replaceFuture.get(15, TimeUnit.SECONDS);
                assertTrue(replaceResult instanceof MutationResponse, "曲线替换必须成功一次");

                if (reviewResult instanceof MutationResponse mr) {
                    // 审核先拿锁：必须冻结版本 1 的控制点 P1.y=10，结论 BLOCKED
                    ReviewResultDto dto = dataOf(mr);
                    assertEquals(1, dto.routeVersion());
                    assertEquals(10, dto.segmentsSnapshot().get(0).p1().y());
                    assertEquals("BLOCKED", dto.conclusion());
                } else {
                    // 替换先提交：航线版本为 2，旧版本审核必须 409
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) reviewResult).status());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
