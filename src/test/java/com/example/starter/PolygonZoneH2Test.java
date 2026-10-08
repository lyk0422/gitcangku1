package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.PolygonRingDto;
import com.example.starter.api.dto.PolygonZoneCreateRequest;
import com.example.starter.api.dto.PolygonZoneResult;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 带孔洞多边形禁飞区的真实 H2（MODE=MySQL）服务测试：
 * 题干合成可观察结果、非法 400、版本推进与环冻结、STALE、幂等与跨类型冲突。
 */
@SpringBootTest
@DisplayName("带孔洞多边形禁飞区：H2 服务测试")
class PolygonZoneH2Test {

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
        jdbc.update("DELETE FROM polygon_zone_ring");
        jdbc.update("DELETE FROM polygon_zone");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("DELETE FROM zone_identity");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
    }

    private String req(String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    private static RoutePointDto pt(int x, int y) {
        return new RoutePointDto(x, y);
    }

    private static List<RoutePointDto> square(int x0, int y0, int x1, int y1) {
        return List.of(pt(x0, y0), pt(x1, y0), pt(x1, y1), pt(x0, y1));
    }

    private PolygonZoneCreateRequest headlineZone(String zoneId, String requestId) {
        return new PolygonZoneCreateRequest(zoneId,
                square(0, 0, 10, 10),
                List.of(new PolygonRingDto(square(3, 3, 7, 7))), requestId);
    }

    private void createRoute(String routeId, int... xy) {
        List<RoutePointDto> points = java.util.stream.IntStream.range(0, xy.length / 2)
                .mapToObj(i -> pt(xy[2 * i], xy[2 * i + 1])).toList();
        service.createRoute(new RouteCreateRequest(routeId, points, req("route")));
    }

    private ReviewResultDto review(String routeId, long airspaceVersion) {
        MutationResponse resp = service.review(
                new ReviewRequest(routeId, 1, airspaceVersion, req("review")));
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    @Test
    @DisplayName("题干合成：孔洞内 CLEAR，触孔边 BLOCKED，穿实体 BLOCKED")
    void headlineObservableResults() {
        service.createPolygonZone(headlineZone("ph", req("pz")));
        assertEquals(1L, currentVersion());

        createRoute("rClear", 4, 4, 6, 6);
        assertEquals("CLEAR", review("rClear", 1).conclusion());

        createRoute("rTouch", 4, 4, 7, 6);
        ReviewResultDto touched = review("rTouch", 1);
        assertEquals("BLOCKED", touched.conclusion());
        assertEquals(List.of("ph"), touched.hitZoneIds());

        createRoute("rCross", 2, 5, 8, 5);
        ReviewResultDto crossed = review("rCross", 1);
        assertEquals("BLOCKED", crossed.conclusion());
        assertEquals(List.of("ph"), crossed.hitZoneIds());
    }

    @Test
    @DisplayName("窄禁飞实体不能靠采样跳过：孔洞两侧 3 米墙体被水平穿越命中")
    void narrowSolidIsNotSkippedBySampling() {
        PolygonZoneCreateRequest narrow = new PolygonZoneCreateRequest("narrow",
                square(0, 0, 10, 10),
                List.of(new PolygonRingDto(square(3, 3, 7, 7))), req("pz"));
        service.createPolygonZone(narrow);
        createRoute("rn", 0, 5, 10, 5);
        assertEquals("BLOCKED", review("rn", 1).conclusion());
        // 孔洞内的完整线段仍 CLEAR
        createRoute("rn2", 4, 5, 6, 5);
        assertEquals("CLEAR", review("rn2", 1).conclusion());
    }

    @Test
    @DisplayName("非法多边形 400：不推进版本、不占键、不残留数据")
    void invalidPolygonRejectedWithoutSideEffects() {
        String requestId = "bad-polygon-1";
        // 孔洞顶点落在外环边上
        PolygonZoneCreateRequest bad = new PolygonZoneCreateRequest("bad",
                square(0, 0, 10, 10),
                List.of(new PolygonRingDto(square(0, 1, 5, 5))), requestId);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.createPolygonZone(bad));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("INVALID_POLYGON", ex.code());
        assertEquals(0L, currentVersion());
        assertNull(findDedup(requestId));
        assertEquals(0, count("polygon_zone"));
        assertEquals(0, count("polygon_zone_ring"));
        assertEquals(0, count("zone_identity"));
        // 键可复用为合法请求
        service.createPolygonZone(headlineZone("bad", requestId));
        assertEquals(1L, currentVersion());
    }

    @Test
    @DisplayName("创建/撤销推进版本；撤销后环顶点冻结不变，历史结论不变，当前 STALE")
    void revokeAdvancesVersionAndFreezesRings() {
        MutationResponse created = service.createPolygonZone(headlineZone("fr", req("pz")));
        PolygonZoneResult result = objectMapper.convertValue(
                created.data(), PolygonZoneResult.class);
        assertEquals("POLYGON", result.kind());
        assertEquals(4, result.outer().size());
        assertEquals(1L, result.airspaceVersion());

        createRoute("rr", 2, 5, 8, 5);
        ReviewResultDto blocked = review("rr", 1);
        assertEquals("BLOCKED", blocked.conclusion());

        service.revokeZone(new ZoneRevokeRequest("fr", req("revoke")));
        assertEquals(2L, currentVersion());
        // 撤销后旧 BLOCKED 对当前查询立即失效（STALE），历史结论保留
        assertEquals("STALE", service.getCurrentReview("rr").conclusion());
        // 撤销不参与审核：旧版本 409，新版本 CLEAR
        ApiException stale = assertThrows(ApiException.class,
                () -> service.review(new ReviewRequest("rr", 1, 1L, req("review"))));
        assertEquals(HttpStatus.CONFLICT, stale.status());
        assertEquals("CLEAR", review("rr", 2).conclusion());
        // 环顶点仍冻结为创建时的 8 行（外环4+孔洞4），撤销不改变历史
        assertEquals(8, jdbc.queryForObject(
                "SELECT COUNT(*) FROM polygon_zone_ring WHERE zone_id = 'fr'",
                Integer.class));
        assertEquals("REVOKED", jdbc.queryForObject(
                "SELECT status FROM polygon_zone WHERE zone_id = 'fr'", String.class));
        assertEquals("BLOCKED", service.getReview(blocked.reviewId()).conclusion());
        // 重复撤销 409
        ApiException twice = assertThrows(ApiException.class,
                () -> service.revokeZone(new ZoneRevokeRequest("fr", req("revoke2"))));
        assertEquals(HttpStatus.CONFLICT, twice.status());
    }

    @Test
    @DisplayName("幂等：同键同参回首次完整响应并冻结环；异参/异种操作 409")
    void idempotentReplayAndParamConflict() {
        String requestId = "poly-idem-1";
        MutationResponse first = service.createPolygonZone(headlineZone("pi", requestId));
        assertFalse(first.replayed());
        PolygonZoneResult firstData = objectMapper.convertValue(
                first.data(), PolygonZoneResult.class);

        // 同键同参重放：完整响应一致，不重复推进版本
        MutationResponse replay = service.createPolygonZone(headlineZone("pi", requestId));
        assertTrue(replay.replayed());
        PolygonZoneResult replayData = objectMapper.convertValue(
                replay.data(), PolygonZoneResult.class);
        assertEquals(firstData.airspaceVersion(), replayData.airspaceVersion());
        assertEquals(firstData.outer(), replayData.outer());
        assertEquals(firstData.holes(), replayData.holes());
        assertEquals(1L, currentVersion());
        assertEquals(1, count("polygon_zone"));

        // 同键异参（孔洞形状不同）→ 409
        PolygonZoneCreateRequest different = new PolygonZoneCreateRequest("pi",
                square(0, 0, 10, 10),
                List.of(new PolygonRingDto(square(2, 2, 8, 8))), requestId);
        ApiException diff = assertThrows(ApiException.class,
                () -> service.createPolygonZone(different));
        assertEquals(HttpStatus.CONFLICT, diff.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", diff.code());
        // 冻结的环不随异参请求改变
        assertEquals(7, jdbc.queryForObject(
                "SELECT x FROM polygon_zone_ring WHERE zone_id = 'pi' AND ring_no = 1 AND seq = 1",
                Integer.class));

        // 同键用于撤销（异种操作）→ 409
        ApiException diffKind = assertThrows(ApiException.class,
                () -> service.revokeZone(new ZoneRevokeRequest("pi", requestId)));
        assertEquals(HttpStatus.CONFLICT, diffKind.status());
    }

    @Test
    @DisplayName("矩形与多边形共用 zoneId：跨类型重复创建 409，并发同号只有一方生效")
    void crossTypeIdConflictSequentialAndConcurrent() throws Exception {
        service.createPolygonZone(headlineZone("shared", req("pz")));
        ApiException rectConflict = assertThrows(ApiException.class,
                () -> service.createZone(new ZoneCreateRequest(
                        "shared", 0, 0, 10, 10, req("rect"))));
        assertEquals(HttpStatus.CONFLICT, rectConflict.status());
        assertEquals(1, count("zone_identity"));

        // 并发：矩形与多边形用同一新 zoneId 同时创建
        String raceId = "shared-race";
        int threads = 6;
        java.util.concurrent.CyclicBarrier barrier =
                new java.util.concurrent.CyclicBarrier(threads);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<Object>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                final boolean polygon = i % 2 == 0;
                futures.add(pool.submit(() -> {
                    barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    try {
                        if (polygon) {
                            return service.createPolygonZone(
                                    headlineZone(raceId, "race-poly-" + idx));
                        }
                        return service.createZone(new ZoneCreateRequest(
                                raceId, 0, 0, 10, 10, "race-rect-" + idx));
                    } catch (ApiException ex) {
                        return ex;
                    }
                }));
            }
            int success = 0;
            int conflict = 0;
            for (java.util.concurrent.Future<Object> f : futures) {
                Object outcome = f.get(15, java.util.concurrent.TimeUnit.SECONDS);
                if (outcome instanceof MutationResponse mr) {
                    success++;
                    assertFalse(mr.replayed(), "不同 requestId 不允许互相重放");
                } else {
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) outcome).status());
                    conflict++;
                }
            }
            assertEquals(1, success, "同 zoneId 并发只能创建一次");
            assertEquals(threads - 1, conflict);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM zone_identity WHERE zone_id = ?",
                    Integer.class, raceId));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("审核返回矩形与多边形的全部命中区域（字典序去重）")
    void reviewReturnsAllHitsAcrossBothKinds() {
        // 矩形封锁 y=50 水平线
        service.createZone(new ZoneCreateRequest(
                "rect-zone", 40, 48, 60, 52, req("rect")));
        // 题干多边形
        service.createPolygonZone(headlineZone("poly-zone", req("pz")));
        // 水平 y=50 命中矩形；航线再加一段穿过多边形实体
        createRoute("both", 0, 50, 100, 50, 2, 5, 8, 5);
        ReviewResultDto dto = review("both", 2);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(List.of("poly-zone", "rect-zone"), dto.hitZoneIds());

        // 只穿孔洞内部的航线不命中多边形，也不碰矩形
        createRoute("holeOnly", 4, 4, 6, 6);
        assertEquals("CLEAR", review("holeOnly", 2).conclusion());
    }

    @Test
    @DisplayName("并发：多边形建区与审核串行化，旧版本审核只能 CLEAR 或 409")
    void concurrentPolygonCreateAndReviewNeverMixesVersions() throws Exception {
        int iterations = 10;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < iterations; i++) {
                String routeId = "cr-" + i;
                String zoneId = "cz-" + i;
                int y = 100 + i * 100;
                // 每轮航线在隔离的 y 高度，且放在 y..y 的位置：建区前 CLEAR，建区后必 BLOCKED
                createRoute(routeId, 0, y, 100, y);
                long versionBefore = currentVersion();

                java.util.concurrent.CyclicBarrier barrier =
                        new java.util.concurrent.CyclicBarrier(2);
                java.util.concurrent.Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    try {
                        return service.review(new ReviewRequest(
                                routeId, 1, versionBefore, "race-review-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                java.util.concurrent.Future<Object> zoneFuture = pool.submit(() -> {
                    barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    try {
                        PolygonZoneCreateRequest z = new PolygonZoneCreateRequest(
                                zoneId,
                                List.of(pt(40, y - 5), pt(60, y - 5), pt(60, y + 5),
                                        pt(40, y + 5)),
                                List.of(), "race-pz-" + zoneId);
                        return service.createPolygonZone(z);
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reviewResult = reviewFuture.get(15, java.util.concurrent.TimeUnit.SECONDS);
                Object zoneResult = zoneFuture.get(15, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(zoneResult instanceof MutationResponse, "建区必须成功");
                if (reviewResult instanceof MutationResponse mr) {
                    ReviewResultDto dto = objectMapper.convertValue(
                            mr.data(), ReviewResultDto.class);
                    assertEquals("CLEAR", dto.conclusion(),
                            "并发审核携带旧空域版本时不得使用新建多边形判定");
                    assertEquals(versionBefore, dto.airspaceVersion());
                } else {
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) reviewResult).status());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private long currentVersion() {
        Long v = jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id = 1", Long.class);
        return v == null ? 0L : v;
    }

    private int count(String table) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return c == null ? 0 : c;
    }

    private Object findDedup(String requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT request_id FROM request_dedup WHERE request_id = ?", requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
