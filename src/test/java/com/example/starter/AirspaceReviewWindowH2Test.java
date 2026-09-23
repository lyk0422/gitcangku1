package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.domain.ZoneWindow;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限时禁飞窗口与时空一致审核的 H2 数据库测试（MODE=MySQL）。
 * 覆盖窗口相交判定、非法窗口 400、替换推进版本与失效、快照不可变、
 * 既有历史缺省窗口按全时解释、窗口参与幂等指纹，以及审核与撤销/改期并发不混搭版本。
 */
@SpringBootTest
@DisplayName("限时空域窗口审核 H2 测试")
class AirspaceReviewWindowH2Test {

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

    private ReviewResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    /** 建一条水平穿越 y=10 的航线，可带窗口。 */
    private void createRoute(String routeId, Long ws, Long we) {
        service.createRoute(new RouteCreateRequest(
                routeId, pts(0, 10, 100, 10), ws, we, "req-" + rid("route")));
    }

    /** 建一个与航线相交的禁飞区（40~60, 5~15），可带窗口。 */
    private void createHitZone(String zoneId, Long ws, Long we) {
        service.createZone(new ZoneCreateRequest(
                zoneId, 40, 5, 60, 15, ws, we, "req-" + rid("zone")));
    }

    private ReviewResultDto review(String routeId, int routeVersion, long airspaceVersion) {
        return dataOf(service.review(
                new ReviewRequest(routeId, routeVersion, airspaceVersion, "req-" + rid("review"))));
    }

    // ============================ 窗口相交判定 ============================

    @Test
    @DisplayName("窗口相交且几何命中才计入 BLOCKED")
    void overlappingWindowsBlock() {
        createRoute("rw1", 1000L, 2000L);
        createHitZone("zw1", 1500L, 2500L);
        ReviewResultDto dto = review("rw1", 1, 1L);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(List.of("zw1"), dto.hitZoneIds());
        // 快照保存双方窗口
        assertEquals(1000L, dto.routeWindowStart());
        assertEquals(2000L, dto.routeWindowEnd());
        assertEquals(List.of(new ZoneWindow("zw1", 1500L, 2500L)), dto.zoneWindows());
    }

    @Test
    @DisplayName("窗口不相交：几何命中也 CLEAR")
    void disjointWindowsClear() {
        createRoute("rw2", 1000L, 2000L);
        createHitZone("zw2", 3000L, 4000L);
        ReviewResultDto dto = review("rw2", 1, 1L);
        assertEquals("CLEAR", dto.conclusion());
        assertTrue(dto.hitZoneIds().isEmpty());
    }

    @Test
    @DisplayName("时间仅端点相接不相交（左闭右开）")
    void endpointTouchingWindowsDoNotIntersect() {
        // 航线 [1000,2000)，区域 [2000,3000)：端点相接不相交
        createRoute("rw3", 1000L, 2000L);
        createHitZone("zw3", 2000L, 3000L);
        assertEquals("CLEAR", review("rw3", 1, 1L).conclusion());

        // 反向：区域 [0,1000)，航线 [1000,2000)
        createRoute("rw4", 1000L, 2000L);
        createHitZone("zw4", 0L, 1000L);
        assertEquals("CLEAR", review("rw4", 1, 2L).conclusion());
    }

    @Test
    @DisplayName("任一方全时则时间必相交")
    void alwaysValidSideAlwaysIntersects() {
        // 航线全时 + 区域带窗口：时间必相交，几何命中即 BLOCKED
        createRoute("rw5", null, null);
        createHitZone("zw5", 9000L, 9999L);
        assertEquals("BLOCKED", review("rw5", 1, 1L).conclusion());

        // 航线带窗口 + 区域全时：时间必相交
        createRoute("rw6", 1000L, 2000L);
        createHitZone("zw6", null, null);
        ReviewResultDto dto = review("rw6", 1, 2L);
        assertEquals("BLOCKED", dto.conclusion());
        // 快照包含审核时刻全部有效区域（zw5 与 zw6），按 zoneId 排序
        assertEquals(List.of(new ZoneWindow("zw5", 9000L, 9999L),
                new ZoneWindow("zw6", null, null)), dto.zoneWindows());
    }

    @Test
    @DisplayName("旧请求（不带窗口）沿用全时语义")
    void legacyRequestsWithoutWindowAreAlwaysValid() {
        // 旧式构造（无窗口参数）创建航线与区域
        service.createRoute(new RouteCreateRequest("rw7", pts(0, 10, 100, 10), "req-" + rid("route")));
        service.createZone(new ZoneCreateRequest("zw7", 40, 5, 60, 15, "req-" + rid("zone")));
        ReviewResultDto dto = review("rw7", 1, 1L);
        assertEquals("BLOCKED", dto.conclusion());
        assertNull(dto.routeWindowStart());
        assertNull(dto.routeWindowEnd());
        assertEquals(List.of(new ZoneWindow("zw7", null, null)), dto.zoneWindows());
    }

    // ============================ 非法窗口 400 ============================

    @Test
    @DisplayName("时间参数非法：缺对、相等、先后颠倒均 400，且失败不占键")
    void invalidWindowRejected() {
        // 只给起始
        ApiException onlyStart = assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest("bz1", 0, 0, 10, 10, 100L, null, "req-" + rid("zone"))));
        assertEquals(HttpStatus.BAD_REQUEST, onlyStart.status());
        assertEquals("INVALID_TIME_WINDOW", onlyStart.code());

        // 只给结束
        ApiException onlyEnd = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("br1", pts(0, 0, 10, 10), null, 100L, "req-" + rid("route"))));
        assertEquals(HttpStatus.BAD_REQUEST, onlyEnd.status());

        // 开始等于结束（非严格早于）
        ApiException equal = assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest("bz2", 0, 0, 10, 10, 100L, 100L, "req-" + rid("zone"))));
        assertEquals(HttpStatus.BAD_REQUEST, equal.status());

        // 开始晚于结束
        ApiException reversed = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("br2", pts(0, 0, 10, 10), 200L, 100L, "req-" + rid("route"))));
        assertEquals(HttpStatus.BAD_REQUEST, reversed.status());

        // 替换时窗口非法同样 400
        createRoute("br3", null, null);
        ApiException replaceBad = assertThrows(ApiException.class, () -> service.replaceRoute(
                new RouteReplaceRequest("br3", 1, pts(0, 0, 10, 10), 100L, null,
                        "req-" + rid("replace"))));
        assertEquals(HttpStatus.BAD_REQUEST, replaceBad.status());

        // 失败不占键：同一 requestId 随后可用于合法请求
        String requestId = "req-window-fail-reuse";
        assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest("bz3", 0, 0, 10, 10, 300L, 100L, requestId)));
        MutationResponse ok = service.createZone(
                new ZoneCreateRequest("bz3", 0, 0, 10, 10, 100L, 300L, requestId));
        assertFalse(ok.replayed());
    }

    // ============================ 替换与版本 ============================

    @Test
    @DisplayName("仅窗口改变的替换也推进版本并使当前结论失效")
    void windowOnlyReplaceAdvancesVersionAndStales() {
        createRoute("rw8", 1000L, 2000L);
        createHitZone("zw8", 1000L, 2000L);
        ReviewResultDto blocked = review("rw8", 1, 1L);
        assertEquals("BLOCKED", blocked.conclusion());
        assertEquals("BLOCKED", service.getCurrentReview("rw8").conclusion());

        // 点列不变，仅把窗口改到不相交的 [5000,6000)
        MutationResponse replaced = service.replaceRoute(new RouteReplaceRequest(
                "rw8", 1, pts(0, 10, 100, 10), 5000L, 6000L, "req-" + rid("replace")));
        assertEquals(2, objectMapper.convertValue(replaced.data(), java.util.Map.class).get("version"));

        // 当前结论立即失效
        assertEquals("STALE", service.getCurrentReview("rw8").conclusion());
        // 历史保留原结论与原窗口
        ReviewResultDto history = service.getReview(blocked.reviewId());
        assertEquals("BLOCKED", history.conclusion());
        assertEquals(1000L, history.routeWindowStart());

        // 新版本重新审核：窗口不相交 → CLEAR
        ReviewResultDto cleared = review("rw8", 2, 1L);
        assertEquals("CLEAR", cleared.conclusion());
        assertEquals(5000L, cleared.routeWindowStart());
    }

    @Test
    @DisplayName("旧替换请求省略窗口时明确设为全时")
    void replaceOmittingWindowSetsAlwaysValid() {
        // 航线窗口 [1000,2000) 与区域窗口 [5000,6000) 不相交 → CLEAR
        createRoute("rw9", 1000L, 2000L);
        createHitZone("zw9", 5000L, 6000L);
        assertEquals("CLEAR", review("rw9", 1, 1L).conclusion());

        // 旧式替换（省略窗口）：窗口明确变为全时，与区域窗口必相交 → BLOCKED
        service.replaceRoute(new RouteReplaceRequest(
                "rw9", 1, pts(0, 10, 100, 10), "req-" + rid("replace")));
        ReviewResultDto dto = review("rw9", 2, 1L);
        assertEquals("BLOCKED", dto.conclusion());
        assertNull(dto.routeWindowStart());
        assertNull(dto.routeWindowEnd());
    }

    // ============================ 快照与历史 ============================

    @Test
    @DisplayName("历史快照不随窗口修改改变；缺窗口的既有历史按全时解释")
    void snapshotImmutableAndLegacyHistoryAlwaysValid() {
        createRoute("rw10", 1000L, 2000L);
        createHitZone("zw10", 1500L, 2500L);
        ReviewResultDto blocked = review("rw10", 1, 1L);
        assertEquals("BLOCKED", blocked.conclusion());

        // 改期（替换窗口）与区域撤销后，历史仍保留原双方窗口与命中结果
        service.replaceRoute(new RouteReplaceRequest(
                "rw10", 1, pts(0, 10, 100, 10), 9000L, 9999L, "req-" + rid("replace")));
        service.revokeZone(new com.example.starter.api.dto.ZoneRevokeRequest(
                "zw10", "req-" + rid("revoke")));
        ReviewResultDto history = service.getReview(blocked.reviewId());
        assertEquals("BLOCKED", history.conclusion());
        assertEquals(List.of("zw10"), history.hitZoneIds());
        assertEquals(1000L, history.routeWindowStart());
        assertEquals(2000L, history.routeWindowEnd());
        assertEquals(List.of(new ZoneWindow("zw10", 1500L, 2500L)), history.zoneWindows());

        // 模拟既有历史：直接写入缺少窗口列的审核记录，按全时（null）解释
        jdbc.update("INSERT INTO review (review_id, route_id, route_version, airspace_version, "
                        + "conclusion, hit_zone_ids, points_snapshot, route_window_start, "
                        + "route_window_end, zone_windows, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "rv_legacy", "rw10", 2, 2L, "BLOCKED", "lz", "0,10;100,10",
                null, null, "lz::", "req-legacy", 1L);
        ReviewResultDto legacy = service.getReview("rv_legacy");
        assertEquals("BLOCKED", legacy.conclusion());
        assertNull(legacy.routeWindowStart());
        assertNull(legacy.routeWindowEnd());
        assertEquals(List.of(new ZoneWindow("lz", null, null)), legacy.zoneWindows());
    }

    // ============================ 幂等指纹包含窗口 ============================

    @Test
    @DisplayName("幂等指纹包含窗口：同窗重放，异窗 409")
    void idempotencyFingerprintIncludesWindow() {
        String requestId = "req-window-idem";
        MutationResponse first = service.createZone(
                new ZoneCreateRequest("izw", 0, 0, 10, 10, 100L, 200L, requestId));
        assertFalse(first.replayed());

        // 同键同参（含相同窗口）→ 重放
        MutationResponse replay = service.createZone(
                new ZoneCreateRequest("izw", 0, 0, 10, 10, 100L, 200L, requestId));
        assertTrue(replay.replayed());

        // 同键仅窗口不同 → 409
        ApiException diffWindow = assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest("izw", 0, 0, 10, 10, 100L, 300L, requestId)));
        assertEquals(HttpStatus.CONFLICT, diffWindow.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", diffWindow.code());

        // 同键省略窗口 → 409
        ApiException noWindow = assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest("izw", 0, 0, 10, 10, requestId)));
        assertEquals(HttpStatus.CONFLICT, noWindow.status());

        // 航线创建指纹同样包含窗口
        String routeReq = "req-route-window-idem";
        service.createRoute(new RouteCreateRequest("irw", pts(0, 0, 10, 10), 100L, 200L, routeReq));
        assertTrue(service.createRoute(
                new RouteCreateRequest("irw", pts(0, 0, 10, 10), 100L, 200L, routeReq)).replayed());
        assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("irw", pts(0, 0, 10, 10), 100L, 201L, routeReq)));
    }

    // ============================ 并发 ============================

    @Test
    @DisplayName("审核与区域撤销并发：要么旧版本一致 BLOCKED，要么 409，不混搭版本")
    void concurrentZoneRevokeAndReviewNeverMixes() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                cleanup();
                String routeId = "crr" + i;
                String zoneId = "czr" + i;
                createRoute(routeId, 1000L, 2000L);
                createHitZone(zoneId, 1000L, 2000L);
                // 当前空域版本 1（建区后）

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.review(new ReviewRequest(
                                routeId, 1, 1L, "req-rv-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> revokeFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.revokeZone(
                                new com.example.starter.api.dto.ZoneRevokeRequest(
                                        zoneId, "req-zr-" + zoneId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object revokeResult = revokeFuture.get(15, TimeUnit.SECONDS);
                assertTrue(revokeResult instanceof MutationResponse, "撤销必须成功");

                if (reviewResult instanceof MutationResponse mr) {
                    // 审核先拿锁：看到撤销前一致快照，命中且携带撤销前版本与窗口
                    ReviewResultDto dto = dataOf(mr);
                    assertEquals("BLOCKED", dto.conclusion());
                    assertEquals(1L, dto.airspaceVersion());
                    assertEquals(List.of(new ZoneWindow(zoneId, 1000L, 2000L)), dto.zoneWindows());
                } else {
                    // 撤销先提交：空域版本推进到 2，旧版本审核必须 409
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) reviewResult).status());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("审核与航线改期并发：要么旧版本旧窗口一致结论，要么 409")
    void concurrentRouteRescheduleAndReviewSerialized() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                cleanup();
                String routeId = "crs" + i;
                createRoute(routeId, 1000L, 2000L);
                createHitZone("czs" + i, null, null);
                // 航线版本 1，空域版本 1

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.review(new ReviewRequest(
                                routeId, 1, 1L, "req-rv-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> replaceFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        // 仅改期：点列不变，窗口改到 [5000,6000)
                        return service.replaceRoute(new RouteReplaceRequest(
                                routeId, 1, pts(0, 10, 100, 10), 5000L, 6000L,
                                "req-rp-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object replaceResult = replaceFuture.get(15, TimeUnit.SECONDS);
                assertTrue(replaceResult instanceof MutationResponse, "改期必须成功");

                if (reviewResult instanceof MutationResponse mr) {
                    // 审核先拿锁：必须是版本 1 的旧窗口快照，不能混入新窗口
                    ReviewResultDto dto = dataOf(mr);
                    assertEquals(1, dto.routeVersion());
                    assertEquals(1000L, dto.routeWindowStart());
                    assertEquals(2000L, dto.routeWindowEnd());
                    assertEquals("BLOCKED", dto.conclusion());
                } else {
                    // 改期先提交：航线版本已是 2，旧版本审核必须 409
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) reviewResult).status());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
