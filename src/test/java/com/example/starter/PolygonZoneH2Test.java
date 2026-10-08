package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.PolygonRingDto;
import com.example.starter.api.dto.PolygonZoneCreateRequest;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 带孔洞多边形禁飞区的真实 H2（MODE=MySQL）业务测试。
 * 覆盖题设可观察结果、非法 400、与矩形共同命中、版本/STALE、撤销冻结、
 * 幂等重放与同键并发竞争。
 */
@SpringBootTest
@DisplayName("带孔洞多边形区域：H2 业务与事务测试")
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
        jdbc.update("DELETE FROM zone_ring");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
    }

    private String req(String prefix) {
        return "req-" + prefix + "-" + seq.incrementAndGet();
    }

    private static List<RoutePointDto> pts(int... xy) {
        return java.util.stream.IntStream.range(0, xy.length / 2)
                .mapToObj(i -> new RoutePointDto(xy[2 * i], xy[2 * i + 1]))
                .toList();
    }

    /** 题设外环正方形 [0,10]。 */
    private static List<RoutePointDto> outerSquare10() {
        return pts(0, 0, 10, 0, 10, 10, 0, 10);
    }

    /** 题设孔洞正方形 [3,7]。 */
    private static PolygonRingDto holeSquare37() {
        return new PolygonRingDto(pts(3, 3, 7, 3, 7, 7, 3, 7));
    }

    private PolygonZoneCreateRequest polygonZone(String zoneId) {
        return new PolygonZoneCreateRequest(zoneId, outerSquare10(),
                List.of(holeSquare37()), req("poly"));
    }

    private ReviewResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    private MutationResponse createRoute(String routeId, int... xy) {
        return service.createRoute(new RouteCreateRequest(routeId, pts(xy), req("route")));
    }

    private MutationResponse review(String routeId, int routeVersion, long airspaceVersion) {
        return service.review(new ReviewRequest(
                routeId, routeVersion, airspaceVersion, req("review")));
    }

    // ============================ 题设可观察结果 ============================

    @Test
    @DisplayName("孔洞内 CLEAR；触孔边 BLOCKED；穿实体 BLOCKED")
    void observableClearAndBlockedResults() {
        createRoute("r-inside", 4, 4, 6, 6);
        createRoute("r-touch", 4, 4, 7, 6);
        createRoute("r-cross", 2, 5, 8, 5);
        MutationResponse zoneResp = service.createPolygonZone(polygonZone("ph"));
        assertFalse(zoneResp.replayed());
        assertEquals(1, ((Number) objectMapper.convertValue(zoneResp.data(), java.util.Map.class)
                .get("airspaceVersion")).intValue());

        assertEquals("CLEAR", dataOf(review("r-inside", 1, 1L)).conclusion());
        ReviewResultDto touch = dataOf(review("r-touch", 1, 1L));
        assertEquals("BLOCKED", touch.conclusion());
        assertEquals(List.of("ph"), touch.hitZoneIds());
        ReviewResultDto cross = dataOf(review("r-cross", 1, 1L));
        assertEquals("BLOCKED", cross.conclusion());
        assertEquals(List.of("ph"), cross.hitZoneIds());
    }

    @Test
    @DisplayName("不只检查航点：航点都在孔洞内、连线穿出实体仍 BLOCKED")
    void segmentLeavingHoleBetweenWaypointsIsBlocked() {
        // (4,4) 与 (4,6) 都在孔洞内，但连线在孔内 —— CLEAR 对照组
        createRoute("r-in", 4, 4, 4, 6);
        // 航点 (4,4) 在孔内、(4,4)→… 折线经过实体：加一个实体带内的航点
        createRoute("r-out", 4, 4, 1, 5);
        service.createPolygonZone(polygonZone("ph2"));

        assertEquals("CLEAR", dataOf(review("r-in", 1, 1L)).conclusion());
        ReviewResultDto blocked = dataOf(review("r-out", 1, 1L));
        assertEquals("BLOCKED", blocked.conclusion());
        assertEquals(List.of("ph2"), blocked.hitZoneIds());
    }

    @Test
    @DisplayName("与矩形区域共同返回全部命中区域（字典序去重）")
    void returnsAllHitsAcrossRectangleAndPolygon() {
        createRoute("r-both", 2, 5, 8, 5);
        service.createZone(new ZoneCreateRequest(
                "rect-a", 7, 4, 9, 6, req("zone")));
        service.createPolygonZone(new PolygonZoneCreateRequest(
                "poly-b", outerSquare10(), List.of(holeSquare37()), req("poly")));
        // 两次建区：空域版本 2
        ReviewResultDto dto = dataOf(review("r-both", 1, 2L));
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(List.of("poly-b", "rect-a"), dto.hitZoneIds());

        // 仅完整位于孔洞内的路线不命中多边形；若也不碰矩形则 CLEAR
        createRoute("r-hole-only", 4, 4, 6, 6);
        assertEquals("CLEAR", dataOf(review("r-hole-only", 1, 2L)).conclusion());
    }

    // ============================ 非法形状 400 ============================

    @Test
    @DisplayName("孔洞贴外环边 / 自交 / 首尾重复 / 顶点越界：400 且不占键、不推进版本")
    void invalidPolygonsRejectedWith400() {
        // 孔洞底边贴外环底边 y=0
        PolygonRingDto touching = new PolygonRingDto(pts(3, 0, 7, 0, 7, 5, 3, 5));
        ApiException touch = assertThrows(ApiException.class, () -> service.createPolygonZone(
                new PolygonZoneCreateRequest("bad1", outerSquare10(),
                        List.of(touching), req("poly"))));
        assertEquals(HttpStatus.BAD_REQUEST, touch.status());
        assertEquals("INVALID_POLYGON_ZONE", touch.code());

        // 蝴蝶结自交外环
        List<RoutePointDto> bowtie = pts(0, 0, 10, 10, 0, 10, 10, 0);
        ApiException selfCross = assertThrows(ApiException.class, () -> service.createPolygonZone(
                new PolygonZoneCreateRequest("bad2", bowtie, List.of(), req("poly"))));
        assertEquals(HttpStatus.BAD_REQUEST, selfCross.status());

        // 首尾重复
        List<RoutePointDto> repeated = pts(0, 0, 10, 0, 10, 10, 0, 10, 0, 0);
        ApiException dup = assertThrows(ApiException.class, () -> service.createPolygonZone(
                new PolygonZoneCreateRequest("bad3", repeated, List.of(), req("poly"))));
        assertEquals(HttpStatus.BAD_REQUEST, dup.status());

        // 失败不推进版本、不落库
        assertEquals(0L, jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id=1", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM no_fly_zone", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM zone_ring", Integer.class));
    }

    @Test
    @DisplayName("孔洞越出外环 / 两孔洞互相接触：400")
    void holeOutsideOrHolesTouchRejected() {
        PolygonRingDto outside = new PolygonRingDto(pts(8, 8, 12, 8, 12, 12, 8, 12));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> service.createPolygonZone(new PolygonZoneCreateRequest(
                        "bad4", outerSquare10(), List.of(outside), req("poly")))).status());

        PolygonRingDto h1 = new PolygonRingDto(pts(1, 4, 5, 4, 5, 8, 1, 8));
        PolygonRingDto h2 = new PolygonRingDto(pts(5, 4, 9, 4, 9, 8, 5, 8));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> service.createPolygonZone(new PolygonZoneCreateRequest(
                        "bad5", outerSquare10(), List.of(h1, h2), req("poly")))).status());
    }

    @Test
    @DisplayName("同 zoneId 重复创建（矩形/多边形互算冲突）：409")
    void duplicateZoneIdConflicts() {
        service.createPolygonZone(polygonZone("same-id"));
        ApiException again = assertThrows(ApiException.class,
                () -> service.createPolygonZone(polygonZone("same-id")));
        assertEquals(HttpStatus.CONFLICT, again.status());
        // 已被多边形占用的 id 不能再建矩形
        ApiException rect = assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest("same-id", 0, 0, 10, 10, req("zone"))));
        assertEquals(HttpStatus.CONFLICT, rect.status());
    }

    // ============================ 版本、撤销与冻结 ============================

    @Test
    @DisplayName("创建/撤销推进空域版本；环顶点冻结，撤销后审核 CLEAR 且历史不变")
    void createAndRevokeAdvanceVersionAndFreezeRings() {
        createRoute("r-rev", 2, 5, 8, 5);
        service.createPolygonZone(polygonZone("fz"));
        ReviewResultDto blocked = dataOf(review("r-rev", 1, 1L));
        assertEquals("BLOCKED", blocked.conclusion());

        // 旧版本审核 409
        ApiException stale = assertThrows(ApiException.class,
                () -> review("r-rev", 1, 0L));
        assertEquals(HttpStatus.CONFLICT, stale.status());

        // 撤销推进版本到 2：旧审核（基于版本 1）对当前查询立即 STALE
        MutationResponse revoked = service.revokeZone(
                new ZoneRevokeRequest("fz", req("revoke")));
        assertEquals(2, ((Number) objectMapper.convertValue(revoked.data(), java.util.Map.class)
                .get("airspaceVersion")).intValue());

        // 历史结论保持 BLOCKED；当前查询 STALE（尚未基于版本 2 重新审核）
        assertEquals("BLOCKED", service.getReview(blocked.reviewId()).conclusion());
        assertEquals("STALE", service.getCurrentReview("r-rev").conclusion());

        // 用新版本重新审核 → CLEAR，恢复当前可用
        assertEquals("CLEAR", dataOf(review("r-rev", 1, 2L)).conclusion());
        assertEquals("CLEAR", service.getCurrentReview("r-rev").conclusion());

        // 环顶点不随撤销改变：仍是 4 外环 + 4 孔洞
        assertEquals(8, jdbc.queryForObject(
                "SELECT COUNT(*) FROM zone_ring WHERE zone_id = 'fz'", Integer.class));
        assertEquals("REVOKED", jdbc.queryForObject(
                "SELECT status FROM no_fly_zone WHERE zone_id = 'fz'", String.class));

        // 撤销两次 409；撤销不存在 404
        ApiException twice = assertThrows(ApiException.class, () -> service.revokeZone(
                new ZoneRevokeRequest("fz", req("revoke"))));
        assertEquals(HttpStatus.CONFLICT, twice.status());
        ApiException missing = assertThrows(ApiException.class, () -> service.revokeZone(
                new ZoneRevokeRequest("ghost", req("revoke"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    // ============================ 幂等 ============================

    @Test
    @DisplayName("同参回首次完整响应（replayed=true），异参 409，失败可复用键")
    void idempotentReplayConflictAndReusableKey() {
        String requestId = "poly-fixed-idem-1";
        PolygonZoneCreateRequest first = new PolygonZoneCreateRequest(
                "iz", outerSquare10(), List.of(holeSquare37()), requestId);
        MutationResponse r1 = service.createPolygonZone(first);
        assertFalse(r1.replayed());

        MutationResponse r2 = service.createPolygonZone(
                new PolygonZoneCreateRequest("iz", outerSquare10(),
                        List.of(holeSquare37()), requestId));
        assertTrue(r2.replayed());
        // 完整响应：冻结环顶点原样返回，版本不重复推进
        com.fasterxml.jackson.databind.JsonNode dataNode =
                objectMapper.convertValue(r2.data(), com.fasterxml.jackson.databind.JsonNode.class);
        assertEquals(1, dataNode.path("airspaceVersion").asInt());
        assertEquals(4, dataNode.path("outerRing").size());
        assertEquals(1, dataNode.path("holes").size());
        assertEquals(4, dataNode.path("holes").get(0).path("points").size());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id=1", Long.class));

        // 同键异参（孔洞不同）→ 409
        PolygonRingDto otherHole = new PolygonRingDto(pts(2, 2, 6, 2, 6, 6, 2, 6));
        ApiException diff = assertThrows(ApiException.class, () -> service.createPolygonZone(
                new PolygonZoneCreateRequest("iz", outerSquare10(),
                        List.of(otherHole), requestId)));
        assertEquals(HttpStatus.CONFLICT, diff.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", diff.code());

        // 失败请求的键可复用：先用 bad 形状失败，再同键建合法区域
        String reusable = "poly-fixed-fail-1";
        ApiException failed = assertThrows(ApiException.class, () -> service.createPolygonZone(
                new PolygonZoneCreateRequest("fz", outerSquare10(),
                        List.of(new PolygonRingDto(pts(0, 0, 2, 0, 2, 2, 0, 2))), reusable)));
        assertEquals(HttpStatus.BAD_REQUEST, failed.status());
        MutationResponse ok = service.createPolygonZone(
                new PolygonZoneCreateRequest("fz", outerSquare10(),
                        List.of(holeSquare37()), reusable));
        assertFalse(ok.replayed());
        assertNotNull(jdbc.queryForObject(
                "SELECT request_id FROM request_dedup WHERE request_id = ?",
                String.class, reusable));
    }

    // ============================ 并发：同键竞争 ============================

    @Test
    @DisplayName("同 requestId 同参并发建多边形区：恰好一次业务生效，其余重放同一结果")
    void concurrentSameRequestIdCreatesExactlyOnce() throws Exception {
        int n = 6;
        String requestId = "poly-concurrent-same-key";
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.createPolygonZone(new PolygonZoneCreateRequest(
                            "ck", outerSquare10(), List.of(holeSquare37()), requestId));
                }));
            }
            int firstCount = 0;
            int replayCount = 0;
            List<String> dataJsons = new ArrayList<>();
            for (Future<MutationResponse> f : futures) {
                MutationResponse resp = f.get(20, TimeUnit.SECONDS);
                if (resp.replayed()) {
                    replayCount++;
                } else {
                    firstCount++;
                }
                dataJsons.add(objectMapper.writeValueAsString(resp.data()));
            }
            // 唯一键竞争的落败方必须拿到赢家业务结果：全部响应数据完全一致
            for (String json : dataJsons) {
                assertEquals(dataJsons.get(0), json);
            }
            assertEquals(1, firstCount, "仅一次请求真正执行业务");
            assertEquals(n - 1, replayCount);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM no_fly_zone WHERE zone_id = 'ck'", Integer.class));
            assertEquals(8, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM zone_ring WHERE zone_id = 'ck'", Integer.class));
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT global_version FROM airspace_meta WHERE id=1", Long.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                    Integer.class, requestId));
        } finally {
            pool.shutdownNow();
        }
    }
}
