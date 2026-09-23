package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.HitZoneWindowDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.TimeWindowDto;
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
 * 限时禁飞窗口与时空一致审核的 H2（MODE=MySQL）业务测试。
 *
 * <p>覆盖：窗口相交/端点相接/全时语义下的 BLOCKED 判定、双方窗口快照持久化、
 * 历史不随改期改变、仅窗口替换也推进版本、旧替换省略窗口重置为全时、
 * 非法窗口 400 与回滚、指纹含窗口的幂等边界，以及审核与航线改期真实并发不混搭版本。</p>
 */
@SpringBootTest
@DisplayName("限时窗口与时空一致审核")
class TimeWindowReviewH2Test {

    // 固定 UTC 毫秒时刻
    private static final long T0 = 1000L;
    private static final long T1 = 2000L;
    private static final long T2 = 3000L;
    private static final long T3 = 4000L;
    private static final long T4 = 5000L;

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

    private String req(String prefix) {
        return "req-" + prefix + "-" + seq.incrementAndGet();
    }

    private static TimeWindowDto win(Long start, Long end) {
        return new TimeWindowDto(start, end);
    }

    private static List<RoutePointDto> crossingPoints() {
        return List.of(new RoutePointDto(0, 10), new RoutePointDto(100, 10));
    }

    /** 创建水平穿过 y=10 的航线，可带窗口。 */
    private void createRoute(String routeId, TimeWindowDto window) {
        service.createRoute(new RouteCreateRequest(
                routeId, crossingPoints(), window, req("route")));
    }

    /** 创建空间上与航线相交的矩形区域 x∈[40,60], y∈[5,15]，可带窗口。 */
    private void createZone(String zoneId, TimeWindowDto window) {
        service.createZone(new ZoneCreateRequest(
                zoneId, 40, 5, 60, 15, window, req("zone")));
    }

    private ReviewResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    private MutationResponse review(String routeId, int routeVersion, long airspaceVersion) {
        return service.review(new ReviewRequest(
                routeId, routeVersion, airspaceVersion, req("review")));
    }

    // ============================ 主流程：时空一致判定 ============================

    @Test
    @DisplayName("空间相交且窗口正长度交叠 → BLOCKED，结果携带双方窗口")
    void overlappingWindowsBlockedWithSnapshots() {
        createRoute("r1", win(T0, T2));      // [1000,3000)
        createZone("z1", win(T1, T3));       // [2000,4000)，交叠 [2000,3000)

        MutationResponse resp = review("r1", 1, 1L);
        ReviewResultDto dto = dataOf(resp);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(List.of("z1"), dto.hitZoneIds());
        assertEquals(win(T0, T2), dto.routeWindow());
        assertEquals(1, dto.hitZoneWindows().size());
        HitZoneWindowDto hit = dto.hitZoneWindows().get(0);
        assertEquals("z1", hit.zoneId());
        assertEquals(win(T1, T3), hit.window());
        assertTrue(dto.current());

        // 窗口列真实落库（H2 边界验证，非 mock）
        Map<String, Object> zoneRow = jdbc.queryForMap(
                "SELECT window_start, window_end FROM no_fly_zone WHERE zone_id = 'z1'");
        assertEquals(T1, ((Number) zoneRow.get("window_start")).longValue());
        assertEquals(T3, ((Number) zoneRow.get("window_end")).longValue());
        Map<String, Object> routeRow = jdbc.queryForMap(
                "SELECT window_start, window_end FROM route WHERE route_id = 'r1'");
        assertEquals(T0, ((Number) routeRow.get("window_start")).longValue());
        assertEquals(T2, ((Number) routeRow.get("window_end")).longValue());
    }

    @Test
    @DisplayName("时间仅端点相接（一方终点=另一方起点）→ 不相交 → CLEAR")
    void endpointTouchInTimeIsClear() {
        createRoute("r2", win(T0, T1));      // [1000,2000)
        createZone("z2", win(T1, T3));       // [2000,4000)：仅端点 2000 相接
        ReviewResultDto dto = dataOf(review("r2", 1, 1L));
        assertEquals("CLEAR", dto.conclusion());
        assertTrue(dto.hitZoneIds().isEmpty());
        // CLEAR 时航线窗口仍保存，命中区域窗口列表为空
        assertEquals(win(T0, T1), dto.routeWindow());
        assertTrue(dto.hitZoneWindows().isEmpty());
    }

    @Test
    @DisplayName("时间窗口完全分离即使空间相交也不 BLOCKED")
    void disjointTimeWindowsIsClear() {
        createRoute("r3", win(T0, T1));      // [1000,2000)
        createZone("z3", win(T2, T3));       // [3000,4000)
        assertEquals("CLEAR", dataOf(review("r3", 1, 1L)).conclusion());
    }

    @Test
    @DisplayName("任一方全时则时间必相交：全时航线/全时区域均 BLOCKED")
    void alwaysWindowIntersectsAndPersistsAsNulls() {
        // 全时航线 + 限时区域（窗口取 [T2,T3)，与后半段限时航线 r5 的 [T0,T1) 不相交）
        createRoute("r4", null);
        createZone("z4", win(T2, T3));
        ReviewResultDto d1 = dataOf(review("r4", 1, 1L));
        assertEquals("BLOCKED", d1.conclusion());
        assertNull(d1.routeWindow().startUtcMillis());
        assertNull(d1.routeWindow().endUtcMillis());
        assertEquals(win(T2, T3), d1.hitZoneWindows().get(0).window());

        // 限时航线 + 全时区域：z4 时间不相交，仅全时 z5 命中
        createRoute("r5", win(T0, T1));
        createZone("z5", null);
        ReviewResultDto d2 = dataOf(review("r5", 1, 2L));
        assertEquals("BLOCKED", d2.conclusion());
        assertEquals(win(T0, T1), d2.routeWindow());
        assertEquals(List.of("z5"), d2.hitZoneIds());
        assertNull(d2.hitZoneWindows().get(0).window().startUtcMillis());

        // 全时落库为 NULL
        Map<String, Object> zoneRow = jdbc.queryForMap(
                "SELECT window_start, window_end FROM no_fly_zone WHERE zone_id = 'z5'");
        assertNull(zoneRow.get("window_start"));
        assertNull(zoneRow.get("window_end"));
    }

    @Test
    @DisplayName("多个空间命中区域只计入时间相交者，仍按 zoneId 排序去重")
    void mixedTimeWindowsFilterAndSortHits() {
        // 航线窗口 [1000,2000)
        createRoute("r6", win(T0, T1));
        // 三个空间均相交的区域：early 时间相交、late 不相交、zeta 全时相交
        createZone("late", win(T2, T3));
        createZone("zeta", null);
        createZone("early", win(T0, T3));
        ReviewResultDto dto = dataOf(review("r6", 1, 3L));
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(List.of("early", "zeta"), dto.hitZoneIds());
        // 命中窗口快照顺序与 hitZoneIds 对齐
        assertEquals(List.of("early", "zeta"),
                dto.hitZoneWindows().stream().map(HitZoneWindowDto::zoneId).toList());
    }

    // ============================ 快照不可变与改期失效 ============================

    @Test
    @DisplayName("航线改期后历史审核保留旧窗口与旧结论，当前查询 STALE")
    void historyKeepsWindowSnapshotAfterReschedule() {
        createRoute("r7", win(T0, T2));      // 与区域交叠
        createZone("z7", win(T1, T3));
        MutationResponse blocked = review("r7", 1, 1L);
        ReviewResultDto first = dataOf(blocked);
        assertEquals("BLOCKED", first.conclusion());

        // 仅改期为不相交窗口（几何完全不变），版本推进到 2
        MutationResponse replaced = service.replaceRoute(new RouteReplaceRequest(
                "r7", 1, crossingPoints(), win(T3, T4), req("replace")));
        assertEquals(2, objectMapper.convertValue(replaced.data(), Map.class).get("version"));

        // 当前结论失效
        assertEquals("STALE", service.getCurrentReview("r7").conclusion());
        // 历史审核窗口快照与结论不变
        ReviewResultDto history = service.getReview(first.reviewId());
        assertEquals("BLOCKED", history.conclusion());
        assertEquals(win(T0, T2), history.routeWindow());
        assertEquals(win(T1, T3), history.hitZoneWindows().get(0).window());

        // 新版本用新空域版本审核 → CLEAR（时间不相交）
        ReviewResultDto reReviewed = dataOf(review("r7", 2, 1L));
        assertEquals("CLEAR", reReviewed.conclusion());
        assertEquals(win(T3, T4), reReviewed.routeWindow());
        // 当前查询恢复可用，且历史 BLOCKED 仍可查
        assertEquals("CLEAR", service.getCurrentReview("r7").conclusion());
        assertEquals("BLOCKED", service.getReview(first.reviewId()).conclusion());
    }

    @Test
    @DisplayName("仅窗口改变的替换也推进版本：旧版本审核 409，旧结论 STALE")
    void windowOnlyReplaceAdvancesVersion() {
        createRoute("r8", win(T0, T1));
        review("r8", 1, 0L);

        service.replaceRoute(new RouteReplaceRequest(
                "r8", 1, crossingPoints(), win(T1, T2), req("replace")));

        ApiException stale = assertThrows(ApiException.class, () -> review("r8", 1, 0L));
        assertEquals(HttpStatus.CONFLICT, stale.status());
        assertEquals("STALE", service.getCurrentReview("r8").conclusion());
        // 新窗口已落库
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT version, window_start, window_end FROM route WHERE route_id = 'r8'");
        assertEquals(2, ((Number) row.get("version")).intValue());
        assertEquals(T1, ((Number) row.get("window_start")).longValue());
    }

    @Test
    @DisplayName("旧替换请求省略窗口时明确重置为全时")
    void replaceWithoutWindowResetsToAlways() {
        createRoute("r9", win(T0, T1));
        createZone("z9", win(T0, T1));
        assertEquals("BLOCKED", dataOf(review("r9", 1, 1L)).conclusion());

        // 省略窗口的旧风格替换：窗口被重置为全时 → 与限时区域时间必相交
        service.replaceRoute(new RouteReplaceRequest(
                "r9", 1, crossingPoints(), req("replace-legacy")));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT window_start, window_end FROM route WHERE route_id = 'r9'");
        assertNull(row.get("window_start"));
        assertNull(row.get("window_end"));
        ReviewResultDto dto = dataOf(review("r9", 2, 1L));
        assertEquals("BLOCKED", dto.conclusion());
        assertNull(dto.routeWindow().startUtcMillis());
    }

    @Test
    @DisplayName("区域撤销推进空域版本并使旧结论 STALE，历史窗口快照保留")
    void zoneRevokeAdvancesVersionAndInvalidates() {
        createRoute("r10", win(T0, T2));
        createZone("z10", win(T1, T3));
        ReviewResultDto blocked = dataOf(review("r10", 1, 1L));
        assertEquals("BLOCKED", blocked.conclusion());

        service.revokeZone(new ZoneRevokeRequest("z10", req("revoke")));
        assertEquals("STALE", service.getCurrentReview("r10").conclusion());
        assertEquals("BLOCKED", service.getReview(blocked.reviewId()).conclusion());
        assertEquals(win(T1, T3),
                service.getReview(blocked.reviewId()).hitZoneWindows().get(0).window());
        // 新空域版本审核 → CLEAR（区域已撤销）
        assertEquals("CLEAR", dataOf(review("r10", 1, 2L)).conclusion());
    }

    // ============================ 失败分支与回滚 ============================

    @Test
    @DisplayName("非法时间窗口返回 400：单侧缺省、起止相等、起晚于止")
    void invalidWindowsRejected() {
        assertInvalidWindow(null, T1);
        assertInvalidWindow(T0, null);
        assertInvalidWindow(T1, T1);
        assertInvalidWindow(T2, T1);
    }

    private void assertInvalidWindow(Long start, Long end) {
        ApiException ex = assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest("bad", 0, 0, 10, 10, win(start, end), req("bad-zone"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("INVALID_TIME_WINDOW", ex.code());
    }

    @Test
    @DisplayName("创建航线非法窗口 400 并回滚：不产生航线与点列，键不被占用")
    void invalidRouteWindowRollsBack() {
        String requestId = "bad-route-window";
        ApiException ex = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("rr", crossingPoints(), win(T1, T0), requestId)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM route", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM route_point", Integer.class));
        assertNull(findDedup(requestId));
        // 键未占用：同键合法请求可成功
        MutationResponse ok = service.createRoute(
                new RouteCreateRequest("rr", crossingPoints(), win(T0, T1), requestId));
        assertFalse(ok.replayed());
    }

    @Test
    @DisplayName("替换航线非法窗口 400 并回滚：版本、点列与旧窗口均不变")
    void invalidReplaceWindowLeavesStateUntouched() {
        createRoute("r11", win(T0, T1));
        ApiException ex = assertThrows(ApiException.class, () -> service.replaceRoute(
                new RouteReplaceRequest("r11", 1,
                        List.of(new RoutePointDto(0, 0), new RoutePointDto(9, 9)),
                        win(T1, T1), req("bad-replace"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT version, window_start, window_end FROM route WHERE route_id = 'r11'");
        assertEquals(1, ((Number) row.get("version")).intValue());
        assertEquals(T0, ((Number) row.get("window_start")).longValue());
        // 旧点列未被删除改写
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM route_point WHERE route_id = 'r11'", Integer.class));
        Integer y = jdbc.queryForObject(
                "SELECT y FROM route_point WHERE route_id = 'r11' AND seq = 0", Integer.class);
        assertEquals(10, y);
    }

    @Test
    @DisplayName("创建区域非法窗口时不推进空域版本（回滚不留痕）")
    void invalidZoneWindowDoesNotAdvanceGlobalVersion() {
        assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest("zz", 0, 0, 10, 10, win(5L, 5L), req("bad"))));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id = 1", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM no_fly_zone", Integer.class));
    }

    // ============================ 幂等：指纹含窗口 ============================

    @Test
    @DisplayName("同键同窗口重放原结果，不重复推进版本")
    void sameWindowReplays() {
        String requestId = "win-replay";
        ZoneCreateRequest req1 = new ZoneCreateRequest("zi", 0, 0, 10, 10, win(T0, T1), requestId);
        MutationResponse first = service.createZone(req1);
        assertFalse(first.replayed());
        MutationResponse replay = service.createZone(
                new ZoneCreateRequest("zi", 0, 0, 10, 10, win(T0, T1), requestId));
        assertTrue(replay.replayed());
        assertEquals(win(T0, T1), objectMapper.convertValue(
                replay.data(), com.example.starter.api.dto.ZoneResult.class).window());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id = 1", Long.class));
    }

    @Test
    @DisplayName("同键不同窗口视为改参 → 409（指纹包含窗口）")
    void sameKeyDifferentWindowConflicts() {
        String requestId = "win-conflict";
        service.createZone(new ZoneCreateRequest("zc", 0, 0, 10, 10, win(T0, T1), requestId));
        ApiException ex = assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest("zc", 0, 0, 10, 10, win(T0, T2), requestId)));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", ex.code());
        // 空域版本未因失败再推进
        assertEquals(1L, jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id = 1", Long.class));
    }

    @Test
    @DisplayName("缺省窗口与显式全时窗口是等价参数（等价 UTC 语义同参），命中重放")
    void omittedWindowAndExplicitNullWindowAreSameParams() {
        String requestId = "win-equivalent";
        MutationResponse first = service.createRoute(new RouteCreateRequest(
                "eq", crossingPoints(), null, requestId));
        assertFalse(first.replayed());
        // 显式提供 start/end 均为 null 的窗口，与缺省等价 → 重放而非 409
        MutationResponse replay = service.createRoute(new RouteCreateRequest(
                "eq", crossingPoints(), new TimeWindowDto(null, null), requestId));
        assertTrue(replay.replayed());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM route", Integer.class));
    }

    @Test
    @DisplayName("审核同键同参（含双方窗口状态）重放同一条不可变结果")
    void reviewReplayKeepsWindowsAndResult() {
        createRoute("r12", win(T0, T2));
        createZone("z12", win(T1, T3));
        String requestId = "review-win-replay";
        MutationResponse first = service.review(
                new ReviewRequest("r12", 1, 1L, requestId));
        MutationResponse replay = service.review(
                new ReviewRequest("r12", 1, 1L, requestId));
        assertTrue(replay.replayed());
        assertEquals(dataOf(first).reviewId(), dataOf(replay).reviewId());
        assertEquals(win(T0, T2), dataOf(replay).routeWindow());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class));
    }

    private Object findDedup(String requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT request_id FROM request_dedup WHERE request_id = ?", requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ============================ 并发：审核与航线改期不混搭版本 ============================

    @Test
    @DisplayName("审核与仅窗口改期并发：要么旧窗口 BLOCKED 快照，要么 409，不混搭")
    void concurrentReviewAndRescheduleNeverMixesWindows() throws Exception {
        int iterations = 8;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < iterations; i++) {
                String routeId = "cr-" + i;
                String zoneId = "cz-" + i;
                // 每轮使用互不相交的 y 几何带，避免历史轮次已提交区域命中本轮新航线
                int y = 10 + i * 100;
                List<RoutePointDto> points = List.of(
                        new RoutePointDto(0, y), new RoutePointDto(100, y));
                service.createRoute(new RouteCreateRequest(
                        routeId, points, win(T0, T2), "req-route-" + routeId));
                service.createZone(new ZoneCreateRequest(
                        zoneId, 40, y - 5, 60, y + 5, win(T1, T3), "req-zone-" + zoneId));
                // 建区已使空域版本推进，取当前空域版本供审核提交
                long airspaceVersion = jdbc.queryForObject(
                        "SELECT global_version FROM airspace_meta WHERE id = 1", Long.class);

                CyclicBarrier barrier = new CyclicBarrier(2);
                final long submittedAirspace = airspaceVersion;
                Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.review(new ReviewRequest(
                                routeId, 1, submittedAirspace, "req-rv-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> replaceFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        // 改期到不相交窗口（几何不变）
                        return service.replaceRoute(new RouteReplaceRequest(
                                routeId, 1, points, win(T3, T4),
                                "req-rp-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object replaceResult = replaceFuture.get(15, TimeUnit.SECONDS);
                assertTrue(replaceResult instanceof MutationResponse, "改期必须成功一次");

                if (reviewResult instanceof MutationResponse mr) {
                    // 审核先拿锁：必须是版本 1 + 旧窗口 [1000,3000) 的 BLOCKED 快照，
                    // 绝不能出现版本 1 却读到新窗口的 CLEAR（混搭）。
                    ReviewResultDto dto = dataOf(mr);
                    assertEquals(1, dto.routeVersion());
                    assertEquals("BLOCKED", dto.conclusion(),
                            "并发审核携带旧航线版本时不得使用改期后的窗口判定");
                    assertEquals(win(T0, T2), dto.routeWindow());
                    assertEquals(List.of(zoneId), dto.hitZoneIds());
                } else {
                    // 改期先提交：航线版本已是 2，旧版本审核必须 409，不产生结论
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) reviewResult).status());
                }

                // 无论先后，串行化后状态确定：v2 + 新窗口审核必为 CLEAR
                ReviewResultDto after = dataOf(
                        service.review(new ReviewRequest(
                                routeId, 2, submittedAirspace, "req-rv2-" + routeId)));
                assertEquals("CLEAR", after.conclusion());
                assertEquals(win(T3, T4), after.routeWindow());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
