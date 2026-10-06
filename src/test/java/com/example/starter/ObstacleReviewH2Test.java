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
import com.example.starter.domain.ObstacleHit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 圆形障碍水平包络审查的 H2 数据库测试（MODE=MySQL）。
 * 覆盖题干合成场景、障碍版本管理、STALE 语义、失败分支与幂等边界。
 */
@SpringBootTest
@DisplayName("圆形障碍水平包络审查（H2）")
class ObstacleReviewH2Test {

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

    private ObstacleCreateRequest obReq(String id, int cx, int cy, int r) {
        return new ObstacleCreateRequest(id, "tester", cx, cy, r, "req-" + rid("ob"));
    }

    private ReviewResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    private long globalVersion() {
        return jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id = 1", Long.class);
    }

    // ============================ 题干合成场景 ============================

    @Test
    @DisplayName("合成场景：心(5,3)半径2+航线半径1 BLOCKED，修订为(5,4)后 CLEAR")
    void syntheticScenarioBlockedThenClearAfterRevise() {
        // 线段 (0,0)-(10,0)，航线包络半径 1
        service.createRoute(new RouteCreateRequest("syn", pts(0, 0, 10, 0), 1, "req-" + rid("route")));
        // 障碍心 (5,3) 半径 2：垂距 3 = 2+1，接触即阻断
        MutationResponse ob = service.createObstacle(obReq("ob1", 5, 3, 2));
        assertFalse(ob.replayed());
        Map<?, ?> obData = objectMapper.convertValue(ob.data(), Map.class);
        assertEquals(1, obData.get("version"));
        assertEquals(1, ((Number) obData.get("airspaceVersion")).intValue());

        MutationResponse blocked = service.review(
                new ReviewRequest("syn", 1, 1L, "req-" + rid("review")));
        ReviewResultDto b = dataOf(blocked);
        assertEquals("BLOCKED", b.conclusion());
        assertTrue(b.hitZoneIds().isEmpty());
        // 成功保存命中线段、障碍版本及包络值
        assertEquals(1, b.hitObstacles().size());
        ObstacleHit hit = b.hitObstacles().get(0);
        assertEquals("ob1", hit.obstacleId());
        assertEquals(1, hit.obstacleVersion());
        assertEquals(0, hit.segmentIndex());
        assertEquals(2, hit.obstacleRadius());
        assertEquals(1, hit.routeEnvelopeRadius());

        // 心改 (5,4)：垂距 4 > 3，无其他阻断 → CLEAR；修订生成新版本，原审查 STALE
        MutationResponse revised = service.reviseObstacle(
                new ObstacleReviseRequest("ob1", "tester", 1, 5, 4, 2, "req-" + rid("revise")));
        Map<?, ?> revisedData = objectMapper.convertValue(revised.data(), Map.class);
        assertEquals(2, revisedData.get("version"));
        assertEquals(2L, globalVersion());
        assertEquals("STALE", service.getCurrentReview("syn").conclusion());
        // 历史审查不随障碍修订修改
        ReviewResultDto history = service.getReview(b.reviewId());
        assertEquals("BLOCKED", history.conclusion());
        assertEquals(1, history.hitObstacles().size());

        MutationResponse cleared = service.review(
                new ReviewRequest("syn", 1, 2L, "req-" + rid("review")));
        ReviewResultDto c = dataOf(cleared);
        assertEquals("CLEAR", c.conclusion());
        assertTrue(c.hitObstacles().isEmpty());
        assertEquals("CLEAR", service.getCurrentReview("syn").conclusion());
    }

    @Test
    @DisplayName("旧无包络航线半径视为 0：距离恰等于障碍半径即接触阻断")
    void legacyRouteWithoutEnvelopeTreatedAsZero() {
        // 不携带 envelopeRadius 的旧式创建
        service.createRoute(new RouteCreateRequest("legacy", pts(0, 0, 10, 0), "req-" + rid("route")));
        assertEquals(0, jdbc.queryForObject(
                "SELECT envelope_radius FROM route WHERE route_id = 'legacy'", Integer.class));
        // 障碍心 (5,2) 半径 2：垂距 2 = 2+0，接触阻断
        service.createObstacle(obReq("obL", 5, 2, 2));
        MutationResponse resp = service.review(
                new ReviewRequest("legacy", 1, 1L, "req-" + rid("review")));
        ReviewResultDto dto = dataOf(resp);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(0, dto.hitObstacles().get(0).routeEnvelopeRadius());
    }

    @Test
    @DisplayName("包络判定覆盖线段内部投影与端点，而非只测航点")
    void envelopeCoversInteriorProjectionNotJustWaypoints() {
        // 航点 (0,10)、(100,10) 距圆心 (50,0) 都远超半径和，但线段内部投影垂距 10
        service.createRoute(new RouteCreateRequest("mid", pts(0, 10, 100, 10), 2, "req-" + rid("route")));
        service.createObstacle(obReq("obM", 50, 0, 8)); // 半径和 10，接触即阻断
        MutationResponse resp = service.review(
                new ReviewRequest("mid", 1, 1L, "req-" + rid("review")));
        ReviewResultDto dto = dataOf(resp);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(0, dto.hitObstacles().get(0).segmentIndex());
    }

    @Test
    @DisplayName("多段折线只命中后段：保存每个命中线段序号")
    void multiSegmentRouteRecordsEachHitSegment() {
        // 三段折线，障碍只覆盖后两段附近（圆心 (20,1) 半径 2，端点 (20,0) 距离 1 ≤ 2）
        service.createRoute(new RouteCreateRequest("multi",
                pts(0, 0, 10, 0, 20, 0, 30, 0), 0, "req-" + rid("route")));
        service.createObstacle(obReq("obS", 20, 1, 2)); // 命中线段 1 与线段 2
        MutationResponse resp = service.review(
                new ReviewRequest("multi", 1, 1L, "req-" + rid("review")));
        ReviewResultDto dto = dataOf(resp);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(2, dto.hitObstacles().size());
        assertEquals(1, dto.hitObstacles().get(0).segmentIndex());
        assertEquals(2, dto.hitObstacles().get(1).segmentIndex());
    }

    @Test
    @DisplayName("撤销障碍后不再参与审查；历史审查不随撤障修改")
    void revokedObstacleNotEvaluatedAndHistoryKept() {
        service.createRoute(new RouteCreateRequest("rvk", pts(0, 0, 10, 0), 1, "req-" + rid("route")));
        service.createObstacle(obReq("obR", 5, 3, 2));
        MutationResponse blocked = service.review(
                new ReviewRequest("rvk", 1, 1L, "req-" + rid("review")));
        assertEquals("BLOCKED", dataOf(blocked).conclusion());

        // 撤销需带 expectedVersion；撤销后空域版本推进，旧审查 STALE
        MutationResponse revoked = service.revokeObstacle(
                new ObstacleRevokeRequest("obR", "tester", 1, "req-" + rid("revoke")));
        Map<?, ?> revokedData = objectMapper.convertValue(revoked.data(), Map.class);
        assertEquals("REVOKED", revokedData.get("status"));
        assertEquals(2, revokedData.get("version"));
        assertEquals("STALE", service.getCurrentReview("rvk").conclusion());
        assertEquals("BLOCKED", service.getReview(dataOf(blocked).reviewId()).conclusion());

        // 新空域版本下审查：撤障后无阻断 → CLEAR
        MutationResponse cleared = service.review(
                new ReviewRequest("rvk", 1, 2L, "req-" + rid("review")));
        assertEquals("CLEAR", dataOf(cleared).conclusion());
    }

    @Test
    @DisplayName("航线替换新包络半径生成新版本，审查使用新版本包络")
    void replaceRouteWithNewEnvelopeRadius() {
        service.createRoute(new RouteCreateRequest("re", pts(0, 0, 10, 0), 0, "req-" + rid("route")));
        service.createObstacle(obReq("obE", 5, 3, 2)); // 半径和 2 < 垂距 3：版本 1 CLEAR
        MutationResponse clear = service.review(
                new ReviewRequest("re", 1, 1L, "req-" + rid("review")));
        assertEquals("CLEAR", dataOf(clear).conclusion());

        // 替换为包络半径 1：半径和 3 = 垂距 3 → BLOCKED
        MutationResponse replaced = service.replaceRoute(new RouteReplaceRequest(
                "re", 1, pts(0, 0, 10, 0), 1, "req-" + rid("replace")));
        Map<?, ?> replacedData = objectMapper.convertValue(replaced.data(), Map.class);
        assertEquals(2, replacedData.get("version"));
        assertEquals(1, replacedData.get("envelopeRadius"));
        assertEquals("STALE", service.getCurrentReview("re").conclusion());

        MutationResponse blocked = service.review(
                new ReviewRequest("re", 2, 1L, "req-" + rid("review")));
        ReviewResultDto dto = dataOf(blocked);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(1, dto.hitObstacles().get(0).routeEnvelopeRadius());
    }

    @Test
    @DisplayName("矩形禁飞命中与圆形障碍命中一并裁决、各自保留")
    void zoneAndObstacleHitsBothReported() {
        service.createRoute(new RouteCreateRequest("both", pts(0, 0, 100, 0), 1, "req-" + rid("route")));
        // 矩形区命中线段；圆形障碍也命中
        service.createZone(new com.example.starter.api.dto.ZoneCreateRequest(
                "z1", 40, -5, 60, 5, "req-" + rid("zone")));
        service.createObstacle(obReq("obB", 80, 3, 2)); // 半径和 3 = 垂距 3
        MutationResponse resp = service.review(
                new ReviewRequest("both", 1, 2L, "req-" + rid("review")));
        ReviewResultDto dto = dataOf(resp);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(List.of("z1"), dto.hitZoneIds());
        assertEquals(1, dto.hitObstacles().size());
        assertEquals("obB", dto.hitObstacles().get(0).obstacleId());
    }

    // ============================ 失败分支 ============================

    @Test
    @DisplayName("非法参数 400：负半径、超界圆心、负包络半径")
    void invalidParamsRejected400() {
        ApiException negRadius = assertThrows(ApiException.class, () -> service.createObstacle(
                new ObstacleCreateRequest("bad1", "tester", 0, 0, -1, "req-" + rid("ob"))));
        assertEquals(HttpStatus.BAD_REQUEST, negRadius.status());

        ApiException badCenter = assertThrows(ApiException.class, () -> service.createObstacle(
                new ObstacleCreateRequest("bad2", "tester", 100001, 0, 1, "req-" + rid("ob"))));
        assertEquals(HttpStatus.BAD_REQUEST, badCenter.status());

        ApiException negEnvelope = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("bad3", pts(0, 0, 10, 0), -1, "req-" + rid("route"))));
        assertEquals(HttpStatus.BAD_REQUEST, negEnvelope.status());

        // 失败不占键：负半径使用过的 requestId 之后可成功复用
        String requestId = "req-ob-reuse";
        assertThrows(ApiException.class, () -> service.createObstacle(
                new ObstacleCreateRequest("bad4", "tester", 0, 0, -5, requestId)));
        assertNull(jdbc.queryForList(
                "SELECT request_id FROM request_dedup WHERE request_id = ?", requestId)
                .stream().findFirst().orElse(null));
        MutationResponse ok = service.createObstacle(
                new ObstacleCreateRequest("ok4", "tester", 0, 0, 5, requestId));
        assertFalse(ok.replayed());
    }

    @Test
    @DisplayName("障碍版本冲突 409：修订/撤销带错 expectedVersion，重复登记 409，不存在 404")
    void obstacleVersionConflicts() {
        service.createObstacle(obReq("ov", 5, 5, 2));

        ApiException dup = assertThrows(ApiException.class, () -> service.createObstacle(
                obReq("ov", 6, 6, 3)));
        assertEquals(HttpStatus.CONFLICT, dup.status());

        ApiException badRevise = assertThrows(ApiException.class, () -> service.reviseObstacle(
                new ObstacleReviseRequest("ov", "tester", 9, 6, 6, 3, "req-" + rid("revise"))));
        assertEquals(HttpStatus.CONFLICT, badRevise.status());

        ApiException badRevoke = assertThrows(ApiException.class, () -> service.revokeObstacle(
                new ObstacleRevokeRequest("ov", "tester", 9, "req-" + rid("revoke"))));
        assertEquals(HttpStatus.CONFLICT, badRevoke.status());

        ApiException missing = assertThrows(ApiException.class, () -> service.reviseObstacle(
                new ObstacleReviseRequest("ghost", "tester", 1, 0, 0, 1, "req-" + rid("revise"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        // 版本未被失败操作推进
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM obstacle WHERE obstacle_id = 'ov'", Integer.class));
        // 撤销后不可再修订/撤销
        service.revokeObstacle(new ObstacleRevokeRequest("ov", "tester", 1, "req-" + rid("revoke")));
        ApiException reviseRevoked = assertThrows(ApiException.class, () -> service.reviseObstacle(
                new ObstacleReviseRequest("ov", "tester", 2, 0, 0, 1, "req-" + rid("revise"))));
        assertEquals(HttpStatus.CONFLICT, reviseRevoked.status());
        ApiException revokeTwice = assertThrows(ApiException.class, () -> service.revokeObstacle(
                new ObstacleRevokeRequest("ov", "tester", 2, "req-" + rid("revoke"))));
        assertEquals(HttpStatus.CONFLICT, revokeTwice.status());
    }

    // ============================ 幂等 ============================

    @Test
    @DisplayName("幂等：同键同参重放首次完整响应，异参（含操作者不同）409")
    void obstacleIdempotencyReplayAndMismatch() {
        String requestId = "ob-idem-1";
        ObstacleCreateRequest first = new ObstacleCreateRequest(
                "oi", "alice", 5, 3, 2, requestId);
        MutationResponse r1 = service.createObstacle(first);
        assertFalse(r1.replayed());

        // 同键同参重放：返回首次结果，不重复推进版本
        MutationResponse r2 = service.createObstacle(
                new ObstacleCreateRequest("oi", "alice", 5, 3, 2, requestId));
        assertTrue(r2.replayed());
        // JSON 重放后数值类型可能不同（Integer/Long），按字段数值比较
        Map<?, ?> m1 = objectMapper.convertValue(r1.data(), Map.class);
        Map<?, ?> m2 = objectMapper.convertValue(r2.data(), Map.class);
        assertEquals(m1.get("obstacleId"), m2.get("obstacleId"));
        assertEquals(m1.get("status"), m2.get("status"));
        assertEquals(((Number) m1.get("version")).intValue(),
                ((Number) m2.get("version")).intValue());
        assertEquals(((Number) m1.get("airspaceVersion")).longValue(),
                ((Number) m2.get("airspaceVersion")).longValue());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM obstacle", Integer.class));
        assertEquals(1L, globalVersion());

        // 同键异参 409：参数不同
        ApiException diffParams = assertThrows(ApiException.class, () -> service.createObstacle(
                new ObstacleCreateRequest("oi", "alice", 5, 4, 2, requestId)));
        assertEquals(HttpStatus.CONFLICT, diffParams.status());
        // 同键异参 409：仅操作者不同也构成不同身份
        ApiException diffOperator = assertThrows(ApiException.class, () -> service.createObstacle(
                new ObstacleCreateRequest("oi", "bob", 5, 3, 2, requestId)));
        assertEquals(HttpStatus.CONFLICT, diffOperator.status());
        // 同键用于不同操作类型 409
        ApiException diffKind = assertThrows(ApiException.class, () -> service.revokeObstacle(
                new ObstacleRevokeRequest("oi", "alice", 1, requestId)));
        assertEquals(HttpStatus.CONFLICT, diffKind.status());
    }

    @Test
    @DisplayName("修订幂等：同键重放不重复推进障碍版本")
    void reviseIdempotentReplayKeepsVersion() {
        service.createObstacle(obReq("ri", 5, 3, 2));
        String requestId = "ob-revise-idem";
        MutationResponse first = service.reviseObstacle(
                new ObstacleReviseRequest("ri", "tester", 1, 5, 4, 2, requestId));
        assertFalse(first.replayed());
        MutationResponse replay = service.reviseObstacle(
                new ObstacleReviseRequest("ri", "tester", 1, 5, 4, 2, requestId));
        assertTrue(replay.replayed());
        assertEquals(2, jdbc.queryForObject(
                "SELECT version FROM obstacle WHERE obstacle_id = 'ri'", Integer.class));
        assertEquals(2L, globalVersion());
    }

    // ============================ 并发 ============================

    @Test
    @DisplayName("障碍登记与审查并发：结果只能是旧快照 CLEAR 或 409，无脏 BLOCKED")
    void concurrentObstacleCreateAndReviewNeverMixes() throws Exception {
        int iterations = 10;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < iterations; i++) {
                int iter = i;
                cleanup();
                String routeId = "cr" + iter;
                String obstacleId = "co" + iter;
                service.createRoute(new RouteCreateRequest(
                        routeId, pts(0, 0, 10, 0), 1, "req-route-" + iter));
                long versionBefore = globalVersion();

                java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
                java.util.concurrent.Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    try {
                        return service.review(new ReviewRequest(
                                routeId, 1, versionBefore, "req-rv-" + iter));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                java.util.concurrent.Future<Object> obstacleFuture = pool.submit(() -> {
                    barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    try {
                        return service.createObstacle(new ObstacleCreateRequest(
                                obstacleId, "tester", 5, 3, 2, "req-ob-" + iter));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reviewResult = reviewFuture.get(15, java.util.concurrent.TimeUnit.SECONDS);
                Object obstacleResult = obstacleFuture.get(15, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(obstacleResult instanceof MutationResponse, "登记必须成功");

                if (reviewResult instanceof MutationResponse mr) {
                    // 审查先拿锁：看到登记前一致快照，只能 CLEAR
                    ReviewResultDto dto = dataOf(mr);
                    assertEquals("CLEAR", dto.conclusion(),
                            "并发审查携带旧空域版本时不得使用新登记障碍判定");
                    assertEquals(versionBefore, dto.airspaceVersion());
                } else {
                    // 登记先提交：空域版本已推进，旧版本审查必须 409
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) reviewResult).status());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("同版本并发修订：恰好一次成功，其余 409，最终版本只加一")
    void concurrentReviseSameVersionOnlyOneWins() throws Exception {
        service.createObstacle(obReq("cv", 5, 3, 2));
        int n = 4;
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(n);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        try {
            List<java.util.concurrent.Future<Object>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    try {
                        return service.reviseObstacle(new ObstacleReviseRequest(
                                "cv", "tester", 1, 5, 4 + idx, 2, "req-cv-" + idx));
                    } catch (ApiException ex) {
                        return ex;
                    }
                }));
            }
            int success = 0;
            int conflict = 0;
            for (java.util.concurrent.Future<Object> f : futures) {
                Object r = f.get(15, java.util.concurrent.TimeUnit.SECONDS);
                if (r instanceof MutationResponse) {
                    success++;
                } else {
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) r).status());
                    conflict++;
                }
            }
            assertEquals(1, success, "同版本并发修订只能成功一次");
            assertEquals(n - 1, conflict);
            assertEquals(2, jdbc.queryForObject(
                    "SELECT version FROM obstacle WHERE obstacle_id = 'cv'", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("同键并发登记：恰好一次业务生效，其余重放同一结果")
    void concurrentSameRequestIdObstacleCreate() throws Exception {
        String requestId = "ob-concurrent-same-key";
        int n = 5;
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(n);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        try {
            List<java.util.concurrent.Future<MutationResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    return service.createObstacle(new ObstacleCreateRequest(
                            "ck", "tester", 5, 3, 2, requestId));
                }));
            }
            int firstCount = 0;
            int replayCount = 0;
            for (java.util.concurrent.Future<MutationResponse> f : futures) {
                MutationResponse resp = f.get(15, java.util.concurrent.TimeUnit.SECONDS);
                if (resp.replayed()) {
                    replayCount++;
                } else {
                    firstCount++;
                }
            }
            assertEquals(1, firstCount, "仅一次请求真正执行登记");
            assertEquals(n - 1, replayCount);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM obstacle", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                    Integer.class, requestId));
        } finally {
            pool.shutdownNow();
        }
    }
}
