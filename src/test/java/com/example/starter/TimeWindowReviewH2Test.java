package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.TimeWindowDto;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.service.AirspaceReviewService;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限时禁飞窗口与时空一致审核的 H2（MODE=MySQL）业务测试。
 * 覆盖时间相交主流程、非法窗口 400、仅窗口改变的版本推进与 STALE、
 * 幂等指纹含窗口、快照不可变、旧历史缺窗按全时解释，以及改期并发一致性。
 */
@SpringBootTest
@DisplayName("限时窗口与时空一致审核")
class TimeWindowReviewH2Test {

    @Autowired
    private AirspaceReviewService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    private static final List<RoutePointDto> CROSS =
            List.of(new RoutePointDto(0, 10), new RoutePointDto(100, 10));
    private static final String ROUTE = "wr";
    private static final String ZONE = "wz";

    private long reqSeq;

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

    private String req(String prefix) {
        return prefix + "-" + (++reqSeq);
    }

    private static TimeWindowDto win(long start, long end) {
        return new TimeWindowDto(start, end);
    }

    private ReviewResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    /** 建空间必命中的区域 [40,5]-[60,15] 与航线 y=10，空域版本推进到 1。 */
    private void setupSpatialHit(TimeWindowDto zoneWindow) {
        service.createZone(new ZoneCreateRequest(
                ZONE, 40, 5, 60, 15, zoneWindow, "req-zone-" + (++reqSeq)));
    }

    private MutationResponse reviewAt(String requestId) {
        return service.review(new ReviewRequest(ROUTE, 1, 1L, requestId));
    }

    // ============================ 时空主流程 ============================

    @Test
    @DisplayName("空间命中且窗口正长度重叠 → BLOCKED，快照含双方窗口")
    void overlappingWindowsBlockedWithSnapshots() {
        setupSpatialHit(win(1000, 2000));
        service.createRoute(new RouteCreateRequest(
                ROUTE, CROSS, win(1500, 2500), req("route")));

        MutationResponse resp = reviewAt(req("review"));
        ReviewResultDto dto = dataOf(resp);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(List.of(ZONE), dto.hitZoneIds());
        assertEquals(win(1500, 2500), dto.routeWindow());
        assertEquals(win(1000, 2000), dto.zoneWindows().get(ZONE));

        // 经真实 H2 持久化后再查，窗口快照保持不变
        ReviewResultDto reloaded = service.getReview(dto.reviewId());
        assertEquals(win(1500, 2500), reloaded.routeWindow());
        assertEquals(win(1000, 2000), reloaded.zoneWindows().get(ZONE));
        assertEquals("BLOCKED", reloaded.conclusion());
    }

    @Test
    @DisplayName("空间命中但时间仅端点相接 → CLEAR（左闭右开）")
    void endpointTouchInTimeIsClear() {
        setupSpatialHit(win(1000, 2000));
        service.createRoute(new RouteCreateRequest(
                ROUTE, CROSS, win(2000, 3000), req("route")));
        assertEquals("CLEAR", dataOf(reviewAt(req("review"))).conclusion());
    }

    @Test
    @DisplayName("空间命中但时间完全分离 → CLEAR")
    void disjointTimeIsClearDespiteSpatialHit() {
        setupSpatialHit(win(1000, 2000));
        service.createRoute(new RouteCreateRequest(
                ROUTE, CROSS, win(2100, 3000), req("route")));
        assertEquals("CLEAR", dataOf(reviewAt(req("review"))).conclusion());
    }

    @Test
    @DisplayName("任一方全时：空间命中即 BLOCKED，全时侧窗口序列化为 null")
    void allTimeEitherSideBlocked() {
        // 区域全时、航线限时
        setupSpatialHit(null);
        service.createRoute(new RouteCreateRequest(
                ROUTE, CROSS, win(100, 200), req("route")));
        ReviewResultDto d1 = dataOf(reviewAt(req("review")));
        assertEquals("BLOCKED", d1.conclusion());
        assertEquals(win(100, 200), d1.routeWindow());
        assertNull(d1.zoneWindows().get(ZONE), "全时区域窗口应为 null");

        cleanup();
        // 区域限时、航线全时
        setupSpatialHit(win(1000, 2000));
        service.createRoute(new RouteCreateRequest(ROUTE, CROSS, null, req("route")));
        ReviewResultDto d2 = dataOf(reviewAt(req("review")));
        assertEquals("BLOCKED", d2.conclusion());
        assertNull(d2.routeWindow());
        assertEquals(win(1000, 2000), d2.zoneWindows().get(ZONE));
    }

    @Test
    @DisplayName("创建/替换结果回显窗口")
    void mutationResultsEchoWindow() {
        MutationResponse zr = service.createZone(new ZoneCreateRequest(
                ZONE, 40, 5, 60, 15, win(1, 2), req("zone")));
        assertEquals(win(1, 2), objectMapper.convertValue(zr.data(),
                com.example.starter.api.dto.ZoneResult.class).window());

        MutationResponse rr = service.createRoute(new RouteCreateRequest(
                ROUTE, CROSS, win(3, 4), req("route")));
        assertEquals(win(3, 4), objectMapper.convertValue(rr.data(),
                com.example.starter.api.dto.RouteResult.class).window());

        MutationResponse replaced = service.replaceRoute(new RouteReplaceRequest(
                ROUTE, 1, CROSS, win(5, 6), req("replace")));
        assertEquals(win(5, 6), objectMapper.convertValue(replaced.data(),
                com.example.starter.api.dto.RouteResult.class).window());
    }

    // ============================ 非法窗口 400 ============================

    @Test
    @DisplayName("start>=end 或单侧为空：建区/建航线/替换均 400，失败不推进版本不占键")
    void invalidWindowsRejected() {
        // 建区：起点等于终点
        ApiException equal = assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest(ZONE, 40, 5, 60, 15, win(100, 100), req("zone"))));
        assertEquals(HttpStatus.BAD_REQUEST, equal.status());
        // 建区：起点晚于终点
        ApiException reversed = assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest(ZONE, 40, 5, 60, 15, win(200, 100), req("zone"))));
        assertEquals(HttpStatus.BAD_REQUEST, reversed.status());
        // 建区：单侧为空（Bean Validation 在 HTTP 层拦截，服务层防御性 400）
        ApiException half = assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest(ZONE, 40, 5, 60, 15,
                        new TimeWindowDto(100L, null), req("zone"))));
        assertEquals(HttpStatus.BAD_REQUEST, half.status());

        service.createRoute(new RouteCreateRequest(ROUTE, CROSS, null, req("route")));
        ApiException badCreate = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("other", CROSS, win(9, 9), req("route2"))));
        assertEquals(HttpStatus.BAD_REQUEST, badCreate.status());

        // 替换携带非法窗口：400 且航线版本仍为 1
        ApiException badReplace = assertThrows(ApiException.class, () -> service.replaceRoute(
                new RouteReplaceRequest(ROUTE, 1, CROSS, win(100, 50), req("replace"))));
        assertEquals(HttpStatus.BAD_REQUEST, badReplace.status());
        assertEquals(1, jdbc.queryForObject("SELECT version FROM route WHERE route_id = ?",
                Integer.class, ROUTE));
    }

    // ============================ 仅窗口改变的版本语义 ============================

    @Test
    @DisplayName("仅替换窗口也推进版本并使当前结论失效；省略窗口明确重置为全时")
    void windowOnlyReplaceAdvancesVersionAndResetsToAllTime() {
        setupSpatialHit(win(1000, 2000));
        service.createRoute(new RouteCreateRequest(
                ROUTE, CROSS, win(1500, 1600), req("route")));

        ReviewResultDto blocked = dataOf(reviewAt(req("review")));
        assertEquals("BLOCKED", blocked.conclusion());
        assertTrue(service.getCurrentReview(ROUTE).current());

        // 点列不变、仅改窗口为时间分离：版本仍推进到 2
        MutationResponse replaced = service.replaceRoute(new RouteReplaceRequest(
                ROUTE, 1, CROSS, win(5000, 6000), req("replace")));
        assertEquals(2, objectMapper.convertValue(replaced.data(),
                com.example.starter.api.dto.RouteResult.class).version());
        // 旧结论 STALE，不能继续当 BLOCKED
        ReviewResultDto stale = service.getCurrentReview(ROUTE);
        assertEquals("STALE", stale.conclusion());
        assertFalse(stale.current());
        // 新窗口下时间不相交 → CLEAR
        assertEquals("CLEAR", dataOf(service.review(
                new ReviewRequest(ROUTE, 2, 1L, req("review2")))).conclusion());

        // 旧替换请求省略窗口 → 明确重置为全时（版本 3），再次 BLOCKED
        MutationResponse reset = service.replaceRoute(new RouteReplaceRequest(
                ROUTE, 2, CROSS, req("replace2")));
        assertEquals(3, objectMapper.convertValue(reset.data(),
                com.example.starter.api.dto.RouteResult.class).version());
        assertNull(objectMapper.convertValue(reset.data(),
                com.example.starter.api.dto.RouteResult.class).window());
        assertEquals("BLOCKED", dataOf(service.review(
                new ReviewRequest(ROUTE, 3, 1L, req("review3")))).conclusion());
    }

    // ============================ 快照不可变与旧历史兼容 ============================

    @Test
    @DisplayName("历史审核不随后续改窗而改变")
    void historyImmutableAfterReschedule() {
        setupSpatialHit(win(1000, 2000));
        service.createRoute(new RouteCreateRequest(
                ROUTE, CROSS, win(1500, 1600), req("route")));
        ReviewResultDto first = dataOf(reviewAt(req("review")));
        assertEquals("BLOCKED", first.conclusion());

        // 航线改期到时间分离，区域撤销推进空域版本
        service.replaceRoute(new RouteReplaceRequest(
                ROUTE, 1, CROSS, win(5000, 6000), req("replace")));
        service.revokeZone(new com.example.starter.api.dto.ZoneRevokeRequest(
                ZONE, req("revoke")));

        // 历史结论与双方窗口快照原样保留
        ReviewResultDto history = service.getReview(first.reviewId());
        assertEquals("BLOCKED", history.conclusion());
        assertEquals(win(1500, 1600), history.routeWindow());
        assertEquals(win(1000, 2000), history.zoneWindows().get(ZONE));
        assertEquals(1, history.routeVersion());
        assertEquals(1L, history.airspaceVersion());
    }

    @Test
    @DisplayName("既有历史缺少窗口列时按全时解释（route_window/zone_windows 为空串）")
    void legacyHistoryWithoutWindowInterpretedAsAllTime() {
        jdbc.update("INSERT INTO no_fly_zone (zone_id, x_min, y_min, x_max, y_max, status, "
                + "created_version, revoked_version, window_start, window_end) "
                + "VALUES ('legacy', 0, 0, 10, 10, 'ACTIVE', 0, NULL, NULL, NULL)");
        // 模拟旧版本写入的审核行：无窗口快照（空串）
        jdbc.update("INSERT INTO review (review_id, route_id, route_version, airspace_version, "
                + "conclusion, hit_zone_ids, points_snapshot, route_window, zone_windows, "
                + "request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, '', '', ?, ?)",
                "rv_legacy", ROUTE, 1, 0L, "BLOCKED", "legacy", "0,0;1,1", "req-legacy", 123L);

        ReviewResultDto history = service.getReview("rv_legacy");
        assertEquals("BLOCKED", history.conclusion());
        assertNull(history.routeWindow(), "旧历史航线窗口缺失应按全时解释");
        assertNull(history.zoneWindows().get("legacy"), "旧历史区域窗口缺失应按全时解释");
    }

    // ============================ 幂等指纹含窗口 ============================

    @Test
    @DisplayName("指纹包含窗口：同键不同窗口 409；等价 UTC 时刻同参重放")
    void fingerprintIncludesWindow() {
        String requestId = "win-idem-1";
        service.createZone(new ZoneCreateRequest(
                ZONE, 40, 5, 60, 15, win(1000, 2000), requestId));
        // 同键仅窗口不同 → 409
        ApiException diffWindow = assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest(ZONE, 40, 5, 60, 15, win(1000, 2001), requestId)));
        assertEquals(HttpStatus.CONFLICT, diffWindow.status());

        // 同键同参（等价 UTC 时刻）→ 重放首次结果，窗口不变，不二次推进版本
        MutationResponse replay = service.createZone(new ZoneCreateRequest(
                ZONE, 40, 5, 60, 15, win(1000, 2000), requestId));
        assertTrue(replay.replayed());
        assertEquals(win(1000, 2000), objectMapper.convertValue(replay.data(),
                com.example.starter.api.dto.ZoneResult.class).window());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id = 1", Long.class));

        // 带窗口与不带窗口语义不同：同键 409
        ApiException vsAllTime = assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest(ZONE, 40, 5, 60, 15, null, requestId)));
        assertEquals(HttpStatus.CONFLICT, vsAllTime.status());
    }

    @Test
    @DisplayName("非法窗口失败回滚不占用 requestId，同键可用于合法窗口")
    void invalidWindowFailureDoesNotConsumeKey() {
        String requestId = "win-fail-key";
        assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest(ZONE, 40, 5, 60, 15, win(100, 100), requestId)));
        Integer dedup = jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?", Integer.class, requestId);
        assertEquals(0, dedup);
        MutationResponse ok = service.createZone(new ZoneCreateRequest(
                ZONE, 40, 5, 60, 15, win(100, 200), requestId));
        assertFalse(ok.replayed());
    }

    // ============================ 改期并发一致性 ============================

    @Test
    @DisplayName("审核与航线改期并发：不混搭版本，胜方窗口快照与版本严格一致")
    void concurrentRescheduleAndReviewNeverMixesVersions() throws Exception {
        setupSpatialHit(win(1000, 2000));

        int iterations = 10;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < iterations; i++) {
                final int idx = i;
                // 每轮独立航线，初始版本 1、窗口 [1500,1600) 与区域窗口正长度重叠
                final String routeId = "wr" + idx;
                service.createRoute(new RouteCreateRequest(
                        routeId, CROSS, win(1500, 1600), "race-route-" + idx));

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.review(new ReviewRequest(
                                routeId, 1, 1L, "race-review-" + idx));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                // 改期方：仅把窗口改为时间分离（空间仍命中）
                Future<Object> replaceFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.replaceRoute(new RouteReplaceRequest(
                                routeId, 1, CROSS, win(5000 + idx, 6000 + idx),
                                "race-replace-" + idx));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object replaceResult = replaceFuture.get(15, TimeUnit.SECONDS);
                assertTrue(replaceResult instanceof MutationResponse, "改期必须成功一次");

                if (reviewResult instanceof MutationResponse mr) {
                    // 审核先拿锁：版本必须为 1，窗口快照必须是旧窗口，结论 BLOCKED；
                    // 绝不允许“版本 1 的审核读到改期后的新窗口而得到 CLEAR”
                    ReviewResultDto dto = dataOf(mr);
                    assertEquals(1, dto.routeVersion());
                    assertEquals(win(1500, 1600), dto.routeWindow());
                    assertEquals("BLOCKED", dto.conclusion());
                } else {
                    // 改期先提交：版本已是 2，旧版本审核必须 409，不产生结论
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) reviewResult).status());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
