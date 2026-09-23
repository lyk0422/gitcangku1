package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.RouteResult;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.api.dto.ZoneHitDto;
import com.example.starter.api.dto.ZoneResult;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限时禁飞窗口与时空一致审核的 H2 数据库测试（MODE=MySQL）。
 *
 * <p>覆盖：时间窗口相交/端点相接/全时语义、双方窗口快照、仅改窗口也推进版本、
 * 省略窗口重置为全时、窗口非法参数 400、指纹含窗口的幂等边界，以及航线改期与
 * 审核真实并发下不混搭版本。几何上所有用例航线都穿过区域，时间是唯一变量。</p>
 */
@SpringBootTest
class TimeWindowAirspaceReviewH2Test {

    @Autowired
    private com.example.starter.service.AirspaceReviewService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    private final AtomicInteger seq = new AtomicInteger();

    // 航线水平穿过 y=10；区域矩形 x∈[40,60], y∈[5,15]，几何必然命中
    private static final List<RoutePointDto> CROSSING =
            List.of(new RoutePointDto(0, 10), new RoutePointDto(100, 10));

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
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    private String req(String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    private ReviewResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    private void createRoute(String routeId, Long wStart, Long wEnd) {
        service.createRoute(new RouteCreateRequest(
                routeId, CROSSING, req("route"), wStart, wEnd));
    }

    private long createZone(String zoneId, Long wStart, Long wEnd) {
        MutationResponse resp = service.createZone(new ZoneCreateRequest(
                zoneId, 40, 5, 60, 15, req("zone"), wStart, wEnd));
        return objectMapper.convertValue(resp.data(), ZoneResult.class).airspaceVersion();
    }

    private ReviewResultDto review(String routeId, int routeVersion, long airspaceVersion) {
        MutationResponse resp = service.review(new ReviewRequest(
                routeId, routeVersion, airspaceVersion, req("review")));
        return dataOf(resp);
    }

    // ============================ 时空一致主流程 ============================

    @Test
    void overlappingTimeWindowsAndSpatialHitIsBlockedWithSnapshots() {
        createRoute("r1", 1500L, 2500L);
        long airspaceVersion = createZone("z1", 1000L, 2000L);
        assertEquals(1L, airspaceVersion);

        ReviewResultDto dto = review("r1", 1, 1L);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(List.of("z1"), dto.hitZoneIds());
        // 快照保存航线窗口
        assertEquals(1500L, dto.routeWindowStart());
        assertEquals(2500L, dto.routeWindowEnd());
        // 快照保存命中区域窗口
        assertEquals(1, dto.hits().size());
        ZoneHitDto hit = dto.hits().get(0);
        assertEquals("z1", hit.zoneId());
        assertEquals(1000L, hit.windowStart());
        assertEquals(2000L, hit.windowEnd());
    }

    @Test
    void windowsOnlyTouchingAtEndpointIsClear() {
        // 航线 [2000,3000)、区域 [1000,2000)：左闭右开，仅在 2000 相接，不相交
        createRoute("r2", 2000L, 3000L);
        createZone("z2", 1000L, 2000L);
        ReviewResultDto dto = review("r2", 1, 1L);
        assertEquals("CLEAR", dto.conclusion());
        assertTrue(dto.hitZoneIds().isEmpty());
        assertTrue(dto.hits().isEmpty());
        // 即便未命中，航线窗口仍进入快照
        assertEquals(2000L, dto.routeWindowStart());
        assertEquals(3000L, dto.routeWindowEnd());
    }

    @Test
    void disjointTimeWindowsIsClearEvenThoughGeometryCrosses() {
        createRoute("r3", 3000L, 4000L);
        createZone("z3", 1000L, 2000L);
        assertEquals("CLEAR", review("r3", 1, 1L).conclusion());
    }

    @Test
    void eitherSideAllTimeAlwaysIntersectsInTime() {
        // 区域全时、航线限时：命中快照中区域窗口为 null（全时）
        createRoute("r4a", 1000L, 2000L);
        createZone("z4a", null, null);
        ReviewResultDto blockedByAllTimeZone = review("r4a", 1, 1L);
        assertEquals("BLOCKED", blockedByAllTimeZone.conclusion());
        assertEquals(1000L, blockedByAllTimeZone.routeWindowStart());
        assertNull(blockedByAllTimeZone.hits().get(0).windowStart());
        assertNull(blockedByAllTimeZone.hits().get(0).windowEnd());

        cleanup();
        // 航线全时、区域限时：命中快照中航线窗口为 null，区域窗口保留
        createRoute("r4b", null, null);
        createZone("z4b", 1000L, 2000L);
        ReviewResultDto dto = review("r4b", 1, 1L);
        assertEquals("BLOCKED", dto.conclusion());
        assertNull(dto.routeWindowStart());
        assertNull(dto.routeWindowEnd());
        assertEquals(1000L, dto.hits().get(0).windowStart());
        assertEquals(2000L, dto.hits().get(0).windowEnd());
    }

    @Test
    void partialOverlapBoundaryCases() {
        cleanup();
        // 航线窗口起点落在区域窗口内部（相交，长度为正）
        createRoute("r5", 1500L, 1600L);
        createZone("z5", 1000L, 2000L);
        assertEquals("BLOCKED", review("r5", 1, 1L).conclusion());
    }

    @Test
    void onlyTimeOverlappingZonesAreCountedAsHits() {
        // 航线窗口 [1000,2000)，三个区域几何上都穿过，仅时间不同：
        // zOverlap 时间相交；zEndTouch 仅端点相接；zDisjoint 完全错开；zAllTime 全时
        createRoute("rm", 1000L, 2000L);
        createZone("zAllTime", null, null);
        createZone("zDisjoint", 5000L, 6000L);
        createZone("zEndTouch", 2000L, 3000L);
        createZone("zOverlap", 1500L, 2500L);

        ReviewResultDto dto = review("rm", 1, 4L);
        assertEquals("BLOCKED", dto.conclusion());
        // 仅时间相交（含全时）的区域计入，按 zoneId 排序去重
        assertEquals(List.of("zAllTime", "zOverlap"), dto.hitZoneIds());
        assertEquals(2, dto.hits().size());
        assertEquals("zAllTime", dto.hits().get(0).zoneId());
        assertEquals("zOverlap", dto.hits().get(1).zoneId());
    }

    @Test
    void legacyReviewWithoutWindowsIsInterpretedAsAllTime() {
        // 构造一条“既有历史”：审核行存在但窗口列缺失（NULL）、命中快照列为空串，
        // 模拟升级前写入的记录；历史结论必须原样保留，窗口按全时解释
        createRoute("rl", null, null);
        createZone("zl", null, null);
        String legacyReviewId = "rv_legacy_1";
        jdbc.update("INSERT INTO review (review_id, route_id, route_version, airspace_version, "
                        + "conclusion, hit_zone_ids, hit_windows_json, points_snapshot, "
                        + "route_window_start, route_window_end, request_id, created_at) "
                        + "VALUES (?, 'rl', 1, 1, 'BLOCKED', 'zl', '', '0,10;100,10', "
                        + "NULL, NULL, 'legacy-req', 123456789)",
                legacyReviewId);

        ReviewResultDto history = service.getReview(legacyReviewId);
        assertEquals("BLOCKED", history.conclusion());
        assertEquals(List.of("zl"), history.hitZoneIds());
        // 缺少窗口按全时解释（起止均为 null）
        assertNull(history.routeWindowStart());
        assertNull(history.routeWindowEnd());
        assertEquals(2, history.pointsSnapshot().size());
    }

    // ============================ 快照不可变 ============================

    @Test
    void historyKeepsOriginalWindowsAfterWindowOnlyReschedule() {
        createRoute("r6", 1000L, 2000L);
        ReviewResultDto first = review("r6", 1, 0L);
        assertEquals(1000L, first.routeWindowStart());
        assertEquals(2000L, first.routeWindowEnd());

        // 仅改窗口（点列完全相同）也推进版本
        MutationResponse replaced = service.replaceRoute(new RouteReplaceRequest(
                "r6", 1, CROSSING, req("replace"), 5000L, 6000L));
        RouteResult routeResult = objectMapper.convertValue(replaced.data(), RouteResult.class);
        assertEquals(2, routeResult.version());
        assertEquals(5000L, routeResult.windowStart());

        // 当前结论因版本推进而 STALE
        assertEquals("STALE", service.getCurrentReview("r6").conclusion());
        // 历史审核的窗口快照不随改期改变
        ReviewResultDto history = service.getReview(first.reviewId());
        assertEquals(1000L, history.routeWindowStart());
        assertEquals(2000L, history.routeWindowEnd());
    }

    @Test
    void replaceOmittingWindowResetsToAllTime() {
        // 航线限时窗口与区域限时窗口互不相交 → CLEAR
        createRoute("r7", 1000L, 2000L);
        createZone("z7", 5000L, 6000L);
        assertEquals("CLEAR", review("r7", 1, 1L).conclusion());

        // 旧风格替换请求省略窗口 → 明确重置为全时
        MutationResponse replaced = service.replaceRoute(new RouteReplaceRequest(
                "r7", 1, CROSSING, req("replace")));
        RouteResult routeResult = objectMapper.convertValue(replaced.data(), RouteResult.class);
        assertEquals(2, routeResult.version());
        assertNull(routeResult.windowStart());
        assertNull(routeResult.windowEnd());

        // 全时航线与限时区域时间必相交 → BLOCKED
        assertEquals("BLOCKED", review("r7", 2, 1L).conclusion());
    }

    // ============================ 非法时间参数 400 ============================

    @Test
    void unpairedWindowRejectedWith400() {
        ApiException onlyStart = assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest("bad1", 40, 5, 60, 15, req("zone"), 1000L, null)));
        assertEquals(HttpStatus.BAD_REQUEST, onlyStart.status());
        assertEquals("INVALID_TIME_WINDOW", onlyStart.code());

        ApiException onlyEnd = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("bad2", CROSSING, req("route"), null, 1000L)));
        assertEquals(HttpStatus.BAD_REQUEST, onlyEnd.status());

        ApiException replaceOnlyStart = assertThrows(ApiException.class, () -> {
            createRoute("bad3", null, null);
            service.replaceRoute(new RouteReplaceRequest(
                    "bad3", 1, CROSSING, req("replace"), 1000L, null));
        });
        assertEquals(HttpStatus.BAD_REQUEST, replaceOnlyStart.status());
    }

    @Test
    void nonStrictStartBeforeEndRejectedWith400() {
        ApiException equal = assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest("bad4", 40, 5, 60, 15, req("zone"), 1000L, 1000L)));
        assertEquals(HttpStatus.BAD_REQUEST, equal.status());

        ApiException reversed = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("bad5", CROSSING, req("route"), 2000L, 1000L)));
        assertEquals(HttpStatus.BAD_REQUEST, reversed.status());

        // 非法请求回滚，不创建资源
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM no_fly_zone", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM route", Integer.class));
    }

    // ============================ 指纹含窗口的幂等 ============================

    @Test
    void windowPartOfIdempotencyFingerprint() {
        String requestId = req("idem-zone");
        MutationResponse first = service.createZone(new ZoneCreateRequest(
                "wz", 40, 5, 60, 15, requestId, 1000L, 2000L));
        assertFalse(first.replayed());
        ZoneResult zr = objectMapper.convertValue(first.data(), ZoneResult.class);
        assertEquals(1000L, zr.windowStart());

        // 同键同参（含相同 UTC 毫秒窗口）重放
        MutationResponse replay = service.createZone(new ZoneCreateRequest(
                "wz", 40, 5, 60, 15, requestId, 1000L, 2000L));
        assertTrue(replay.replayed());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM no_fly_zone", Integer.class));

        // 窗口相差 1 毫秒即改参 → 409
        ApiException changedWindow = assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest("wz", 40, 5, 60, 15, requestId, 1000L, 2001L)));
        assertEquals(HttpStatus.CONFLICT, changedWindow.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", changedWindow.code());

        // 有窗口与全时（省略窗口）语义不同，也属改参
        ApiException toAllTime = assertThrows(ApiException.class, () -> service.createZone(
                new ZoneCreateRequest("wz", 40, 5, 60, 15, requestId, null, null)));
        assertEquals(HttpStatus.CONFLICT, toAllTime.status());
    }

    @Test
    void invalidWindowFailureDoesNotConsumeIdempotencyKey() {
        String requestId = req("idem-fail");
        ApiException failed = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("wf", CROSSING, requestId, 2000L, 1000L)));
        assertEquals(HttpStatus.BAD_REQUEST, failed.status());
        // 失败不占键，同键随后可成功
        MutationResponse ok = service.createRoute(
                new RouteCreateRequest("wf", CROSSING, requestId, 1000L, 2000L));
        assertFalse(ok.replayed());
    }

    // ============================ 航线改期并发 ============================

    @Test
    void concurrentWindowOnlyRescheduleAndReviewNeverMixesVersions() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                String routeId = "cw-" + i;
                createRoute(routeId, 1000L, 2000L);
                CyclicBarrier barrier = new CyclicBarrier(2);

                Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.review(new ReviewRequest(
                                routeId, 1, 0L, "cw-rv-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> replaceFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        // 仅改窗口
                        return service.replaceRoute(new RouteReplaceRequest(
                                routeId, 1, CROSSING, "cw-rp-" + routeId, 5000L, 6000L));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object replaceResult = replaceFuture.get(15, TimeUnit.SECONDS);
                assertTrue(replaceResult instanceof MutationResponse, "改期必须成功一次");

                if (reviewResult instanceof MutationResponse mr) {
                    // 审核先拿锁：快照必须是版本 1 的旧窗口 [1000,2000)
                    ReviewResultDto dto = dataOf(mr);
                    assertEquals(1, dto.routeVersion());
                    assertEquals(1000L, dto.routeWindowStart());
                    assertEquals(2000L, dto.routeWindowEnd());
                } else {
                    // 改期先提交：版本已是 2，旧版本审核必须 409
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) reviewResult).status());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
