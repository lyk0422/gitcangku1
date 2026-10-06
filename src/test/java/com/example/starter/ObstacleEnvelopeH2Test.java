package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ObstacleCreateRequest;
import com.example.starter.api.dto.ObstacleReviseRequest;
import com.example.starter.api.dto.ObstacleRevokeRequest;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.domain.EnvelopeHit;
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
 * 水平包络审查的 H2 数据库测试（MODE=MySQL）。
 * 覆盖题干合成用例、障碍版本规则、包络半径修订、幂等与真实并发互斥。
 */
@SpringBootTest
class ObstacleEnvelopeH2Test {

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
        jdbc.update("DELETE FROM obstacle");
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

    private ReviewResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    private MutationResponse createObstacle(String id, int cx, int cy, int radius) {
        return service.createObstacle(
                new ObstacleCreateRequest(id, cx, cy, radius, "tester", "req-" + rid("ob")));
    }

    // ============================ 题干合成用例 ============================

    @Test
    void syntheticSegmentBlockedThenClearAfterObstacleCenterMoves() {
        // 合成线段 (0,0)-(10,0)，航线包络半径 1
        service.createRoute(new RouteCreateRequest("sr", pts(0, 0, 10, 0), 1, "req-" + rid("route")));
        // 障碍心 (5,3) 半径 2：距离 3 <= 2+1 → BLOCKED
        MutationResponse ob = createObstacle("so", 5, 3, 2);
        assertEquals(1, objectMapper.convertValue(ob.data(), Map.class).get("version"));
        assertEquals(1, ((Number) objectMapper.convertValue(ob.data(), Map.class)
                .get("airspaceVersion")).intValue());

        MutationResponse blockedResp = service.review(
                new ReviewRequest("sr", 1, 1L, "req-" + rid("review")));
        ReviewResultDto blocked = dataOf(blockedResp);
        assertEquals("BLOCKED", blocked.conclusion());
        assertTrue(blocked.hitZoneIds().isEmpty());
        assertEquals(1, blocked.envelopeRadius());
        assertEquals(1, blocked.envelopeHits().size());
        EnvelopeHit hit = blocked.envelopeHits().get(0);
        assertEquals("so", hit.obstacleId());
        assertEquals(1, hit.obstacleVersion());
        assertEquals(0, hit.segmentIndex());
        assertEquals(1, hit.routeRadius());
        assertEquals(2, hit.obstacleRadius());

        // 心改 (5,4)：距离 4 > 3，且无其他阻断 → CLEAR；修订生成障碍新版本
        MutationResponse revised = service.reviseObstacle(
                new ObstacleReviseRequest("so", 1, 5, 4, 2, "tester", "req-" + rid("revise")));
        Map<?, ?> revisedData = objectMapper.convertValue(revised.data(), Map.class);
        assertEquals(2, revisedData.get("version"));
        assertEquals(2, ((Number) revisedData.get("airspaceVersion")).intValue());

        // 原审查标 STALE，历史结论不随障碍修订改变
        assertEquals("STALE", service.getCurrentReview("sr").conclusion());
        ReviewResultDto historical = service.getReview(blocked.reviewId());
        assertEquals("BLOCKED", historical.conclusion());
        assertEquals(1, historical.envelopeHits().size());

        MutationResponse clearResp = service.review(
                new ReviewRequest("sr", 1, 2L, "req-" + rid("review")));
        ReviewResultDto clear = dataOf(clearResp);
        assertEquals("CLEAR", clear.conclusion());
        assertTrue(clear.envelopeHits().isEmpty());
        assertEquals("CLEAR", service.getCurrentReview("sr").conclusion());
    }

    @Test
    void touchAtExactRadiusSumBlocks() {
        // 距离恰等于两半径之和（接触）也阻断
        service.createRoute(new RouteCreateRequest("tr", pts(0, 0, 10, 0), 1, "req-" + rid("route")));
        createObstacle("to", 5, 4, 3);
        MutationResponse resp = service.review(
                new ReviewRequest("tr", 1, 1L, "req-" + rid("review")));
        assertEquals("BLOCKED", dataOf(resp).conclusion());
        assertEquals(1, dataOf(resp).envelopeHits().size());
    }

    @Test
    void endpointProximityBlocksAndInteriorProjectionCounts() {
        // 最近点为线段端点：圆心 (12,0) 半径 1 + 航线半径 1，距离 2 <= 2 → BLOCKED
        service.createRoute(new RouteCreateRequest("er", pts(0, 0, 10, 0), 1, "req-" + rid("route")));
        createObstacle("eo", 12, 0, 1);
        MutationResponse resp = service.review(
                new ReviewRequest("er", 1, 1L, "req-" + rid("review")));
        assertEquals("BLOCKED", dataOf(resp).conclusion());

        // 内部投影：两个航点都远离圆心，但线段中段距离 1 <= 1 → BLOCKED（不能只测航点）
        service.createRoute(new RouteCreateRequest("ir2", pts(0, 0, 10, 0), 0, "req-" + rid("route")));
        createObstacle("io", 5, 1, 1);
        MutationResponse resp2 = service.review(
                new ReviewRequest("ir2", 1, 2L, "req-" + rid("review")));
        assertEquals("BLOCKED", dataOf(resp2).conclusion());
        assertEquals(0, dataOf(resp2).envelopeHits().get(0).segmentIndex());
    }

    @Test
    void oldRouteWithoutEnvelopeRadiusTreatedAsZero() {
        // 旧无包络航线（不登记半径）视为 0：距离 3 <= 3+0 接触即阻断
        service.createRoute(new RouteCreateRequest("or", pts(0, 0, 10, 0), "req-" + rid("route")));
        createObstacle("oo", 5, 3, 3);
        MutationResponse resp = service.review(
                new ReviewRequest("or", 1, 1L, "req-" + rid("review")));
        ReviewResultDto dto = dataOf(resp);
        assertEquals(0, dto.envelopeRadius());
        assertEquals("BLOCKED", dto.conclusion());

        // 距离 4 > 3+0 → CLEAR
        service.reviseObstacle(new ObstacleReviseRequest("oo", 1, 5, 4, 3, "tester", "req-" + rid("revise")));
        MutationResponse resp2 = service.review(
                new ReviewRequest("or", 1, 2L, "req-" + rid("review")));
        assertEquals("CLEAR", dataOf(resp2).conclusion());
    }

    @Test
    void zoneAndObstacleHitsBothReported() {
        // 原几何禁飞命中与包络命中一并裁决、同时保留
        service.createRoute(new RouteCreateRequest("br", pts(0, 0, 10, 0), 1, "req-" + rid("route")));
        service.createZone(new ZoneCreateRequest("bz", 2, -1, 4, 1, "req-" + rid("zone")));
        createObstacle("bo", 8, 2, 1);
        MutationResponse resp = service.review(
                new ReviewRequest("br", 1, 2L, "req-" + rid("review")));
        ReviewResultDto dto = dataOf(resp);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(List.of("bz"), dto.hitZoneIds());
        assertEquals(1, dto.envelopeHits().size());
        assertEquals("bo", dto.envelopeHits().get(0).obstacleId());
    }

    // ============================ 障碍版本规则 ============================

    @Test
    void obstacleReviseAndRevokeEnforceExpectedVersion() {
        createObstacle("vo", 5, 3, 2);
        // 修订版本不匹配 → 409
        ApiException wrongRevise = assertThrows(ApiException.class, () -> service.reviseObstacle(
                new ObstacleReviseRequest("vo", 7, 5, 4, 2, "tester", "req-" + rid("revise"))));
        assertEquals(HttpStatus.CONFLICT, wrongRevise.status());
        // 修订不存在的障碍 → 404
        ApiException missing = assertThrows(ApiException.class, () -> service.reviseObstacle(
                new ObstacleReviseRequest("ghost", 1, 5, 4, 2, "tester", "req-" + rid("revise"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
        // 撤销版本不匹配 → 409
        ApiException wrongRevoke = assertThrows(ApiException.class, () -> service.revokeObstacle(
                new ObstacleRevokeRequest("vo", 9, "tester", "req-" + rid("revoke"))));
        assertEquals(HttpStatus.CONFLICT, wrongRevoke.status());

        // 正确修订 → 版本 2；正确撤销 → 版本 3
        MutationResponse revised = service.reviseObstacle(
                new ObstacleReviseRequest("vo", 1, 5, 4, 2, "tester", "req-" + rid("revise")));
        assertEquals(2, objectMapper.convertValue(revised.data(), Map.class).get("version"));
        MutationResponse revoked = service.revokeObstacle(
                new ObstacleRevokeRequest("vo", 2, "tester", "req-" + rid("revoke")));
        Map<?, ?> revokedData = objectMapper.convertValue(revoked.data(), Map.class);
        assertEquals(3, revokedData.get("version"));
        assertEquals("REVOKED", revokedData.get("status"));

        // 旧版本不可改：撤销后再次撤销/修订 → 409
        ApiException twice = assertThrows(ApiException.class, () -> service.revokeObstacle(
                new ObstacleRevokeRequest("vo", 3, "tester", "req-" + rid("revoke2"))));
        assertEquals(HttpStatus.CONFLICT, twice.status());
        ApiException reviseRevoked = assertThrows(ApiException.class, () -> service.reviseObstacle(
                new ObstacleReviseRequest("vo", 3, 5, 5, 2, "tester", "req-" + rid("revise2"))));
        assertEquals(HttpStatus.CONFLICT, reviseRevoked.status());
    }

    @Test
    void revokedObstacleNoLongerBlocksAndBumpsAirspaceVersion() {
        service.createRoute(new RouteCreateRequest("xr", pts(0, 0, 10, 0), 1, "req-" + rid("route")));
        createObstacle("xo", 5, 3, 2);
        MutationResponse blocked = service.review(
                new ReviewRequest("xr", 1, 1L, "req-" + rid("review")));
        assertEquals("BLOCKED", dataOf(blocked).conclusion());

        service.revokeObstacle(new ObstacleRevokeRequest("xo", 1, "tester", "req-" + rid("revoke")));
        // 撤障后旧审查 STALE；历史审查不随撤障修改
        assertEquals("STALE", service.getCurrentReview("xr").conclusion());
        ReviewResultDto historical = service.getReview(dataOf(blocked).reviewId());
        assertEquals("BLOCKED", historical.conclusion());
        assertEquals(1, historical.envelopeHits().size());
        // 用新空域版本复审 → CLEAR
        MutationResponse clear = service.review(
                new ReviewRequest("xr", 1, 2L, "req-" + rid("review")));
        assertEquals("CLEAR", dataOf(clear).conclusion());
    }

    @Test
    void invalidObstacleParamsRejected() {
        // 负半径 → 400
        ApiException negative = assertThrows(ApiException.class, () -> service.createObstacle(
                new ObstacleCreateRequest("bad1", 5, 3, -1, "tester", "req-" + rid("ob"))));
        assertEquals(HttpStatus.BAD_REQUEST, negative.status());
        // 半径缺失 → 400
        ApiException nullRadius = assertThrows(ApiException.class, () -> service.createObstacle(
                new ObstacleCreateRequest("bad2", 5, 3, null, "tester", "req-" + rid("ob"))));
        assertEquals(HttpStatus.BAD_REQUEST, nullRadius.status());
        // 重复登记 → 409
        createObstacle("dup", 5, 3, 2);
        ApiException dup = assertThrows(ApiException.class, () -> service.createObstacle(
                new ObstacleCreateRequest("dup", 6, 4, 2, "tester", "req-" + rid("ob"))));
        assertEquals(HttpStatus.CONFLICT, dup.status());
        // 非法包络半径 → 400
        ApiException badEnvelope = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("bad3", pts(0, 0, 10, 0), -1, "req-" + rid("route"))));
        assertEquals(HttpStatus.BAD_REQUEST, badEnvelope.status());
    }

    // ============================ 航线包络半径修订 ============================

    @Test
    void routeRadiusRevisionCreatesNewVersionAndStalesReview() {
        // 半径 0 时 CLEAR（距离 3 > 2+0）
        service.createRoute(new RouteCreateRequest("rr", pts(0, 0, 10, 0), 0, "req-" + rid("route")));
        createObstacle("ro", 5, 3, 2);
        MutationResponse clear = service.review(
                new ReviewRequest("rr", 1, 1L, "req-" + rid("review")));
        assertEquals("CLEAR", dataOf(clear).conclusion());

        // 仅修订包络半径（坐标不变）也生成新版本，原审查 STALE
        MutationResponse replaced = service.replaceRoute(new RouteReplaceRequest(
                "rr", 1, pts(0, 0, 10, 0), 1, "req-" + rid("replace")));
        assertEquals(2, objectMapper.convertValue(replaced.data(), Map.class).get("version"));
        assertEquals("STALE", service.getCurrentReview("rr").conclusion());
        // 旧航线版本审核 → 409；新版本（半径 1）距离 3 <= 2+1 → BLOCKED
        ApiException stale = assertThrows(ApiException.class, () -> service.review(
                new ReviewRequest("rr", 1, 1L, "req-" + rid("review"))));
        assertEquals(HttpStatus.CONFLICT, stale.status());
        MutationResponse blocked = service.review(
                new ReviewRequest("rr", 2, 1L, "req-" + rid("review")));
        assertEquals("BLOCKED", dataOf(blocked).conclusion());
        assertEquals(1, dataOf(blocked).envelopeRadius());
    }

    // ============================ 幂等 ============================

    @Test
    void obstacleIdempotencyReplayAndMismatch() {
        String requestId = "ob-idem-1";
        ObstacleCreateRequest req = new ObstacleCreateRequest("io1", 5, 3, 2, "tester", requestId);
        MutationResponse first = service.createObstacle(req);
        assertFalse(first.replayed());
        // 同键同参重放首次完整响应，版本不再推进
        MutationResponse replay = service.createObstacle(
                new ObstacleCreateRequest("io1", 5, 3, 2, "tester", requestId));
        assertTrue(replay.replayed());
        assertEquals(1, objectMapper.convertValue(replay.data(), Map.class).get("version"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM obstacle", Integer.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id=1", Long.class));
        // 同键异参（含操作者不同）→ 409
        ApiException diffParams = assertThrows(ApiException.class, () -> service.createObstacle(
                new ObstacleCreateRequest("io1", 5, 4, 2, "tester", requestId)));
        assertEquals(HttpStatus.CONFLICT, diffParams.status());
        ApiException diffOperator = assertThrows(ApiException.class, () -> service.createObstacle(
                new ObstacleCreateRequest("io1", 5, 3, 2, "other", requestId)));
        assertEquals(HttpStatus.CONFLICT, diffOperator.status());
        // 同键用于不同类型操作 → 409
        ApiException diffKind = assertThrows(ApiException.class, () -> service.revokeObstacle(
                new ObstacleRevokeRequest("io1", 1, "tester", requestId)));
        assertEquals(HttpStatus.CONFLICT, diffKind.status());
    }

    @Test
    void failedObstacleRequestDoesNotConsumeIdempotencyKey() {
        String requestId = "ob-fail-1";
        ApiException failed = assertThrows(ApiException.class, () -> service.createObstacle(
                new ObstacleCreateRequest("fo", 5, 3, -2, "tester", requestId)));
        assertEquals(HttpStatus.BAD_REQUEST, failed.status());
        assertNull(jdbc.queryForList(
                "SELECT request_id FROM request_dedup WHERE request_id = ?", requestId)
                .stream().findFirst().orElse(null));
        // 失败不占键：同键可成功用于合法请求
        MutationResponse ok = service.createObstacle(
                new ObstacleCreateRequest("fo", 5, 3, 2, "tester", requestId));
        assertFalse(ok.replayed());
        assertNotNull(jdbc.queryForList(
                "SELECT request_id FROM request_dedup WHERE request_id = ?", requestId)
                .stream().findFirst().orElse(null));
    }

    @Test
    void reviewReplayKeepsEnvelopeHitsImmutable() {
        service.createRoute(new RouteCreateRequest("pr", pts(0, 0, 10, 0), 1, "req-" + rid("route")));
        createObstacle("po", 5, 3, 2);
        String requestId = "review-ob-1";
        MutationResponse first = service.review(new ReviewRequest("pr", 1, 1L, requestId));
        assertEquals("BLOCKED", dataOf(first).conclusion());
        // 撤障后同键重放仍返回首次的命中明细
        service.revokeObstacle(new ObstacleRevokeRequest("po", 1, "tester", "req-" + rid("revoke")));
        MutationResponse replay = service.review(new ReviewRequest("pr", 1, 1L, requestId));
        assertTrue(replay.replayed());
        ReviewResultDto dto = dataOf(replay);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(1, dto.envelopeHits().size());
        assertEquals(dataOf(first).reviewId(), dto.reviewId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class));
    }

    // ============================ 并发 ============================

    @Test
    void concurrentObstacleCreateAndReviewNeverMixesVersions() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 10; i++) {
                cleanup();
                String routeId = "cor-" + i;
                String obstacleId = "coo-" + i;
                service.createRoute(new RouteCreateRequest(
                        routeId, pts(0, 0, 10, 0), 1, "req-" + rid("route")));
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
                Future<Object> obstacleFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.createObstacle(new ObstacleCreateRequest(
                                obstacleId, 5, 3, 2, "tester", "req-ob-" + obstacleId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object obstacleResult = obstacleFuture.get(15, TimeUnit.SECONDS);
                assertTrue(obstacleResult instanceof MutationResponse, "障碍登记必须成功");
                if (reviewResult instanceof MutationResponse mr) {
                    // 审核先拿锁：看到登记前一致快照，只能 CLEAR 且空域版本为 0
                    ReviewResultDto dto = dataOf(mr);
                    assertEquals("CLEAR", dto.conclusion(),
                            "旧空域版本审核不得使用并发新登记障碍判定");
                    assertEquals(0L, dto.airspaceVersion());
                } else {
                    // 障碍先提交：空域版本已推进，旧版本审核必须 409
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) reviewResult).status());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameObstacleRequestIdPlaysBackOneOutcome() throws Exception {
        String requestId = "ob-concurrent-key";
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.createObstacle(
                            new ObstacleCreateRequest("ck", 5, 3, 2, "tester", requestId));
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
            assertEquals(1, firstCount, "仅一次请求真正登记障碍");
            assertEquals(n - 1, replayCount);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM obstacle", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                    Integer.class, requestId));
        } finally {
            pool.shutdownNow();
        }
    }
}
