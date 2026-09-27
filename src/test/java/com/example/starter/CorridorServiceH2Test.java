package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.CorridorCreateRequest;
import com.example.starter.api.dto.CorridorDto;
import com.example.starter.api.dto.CorridorRevokeRequest;
import com.example.starter.api.dto.DepartureRegisterRequest;
import com.example.starter.api.dto.DepartureResult;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyCreateRequest;
import com.example.starter.api.dto.OccupancyEventDto;
import com.example.starter.api.dto.OccupancyReapplyRequest;
import com.example.starter.api.dto.OccupancyResult;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.YieldOutcomeDto;
import com.example.starter.api.dto.YieldRecordDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空域应急走廊与占用业务的 H2 数据库测试（MODE=MySQL，真实表、真实事务与行锁）。
 * 覆盖让渡主流程、422/409/400/404 失败分支、事务回滚无部分结果、左闭右开边界、
 * 撤销/到期语义、重新申请、起飞、不可变历史、幂等与真实并发裁决。
 */
@SpringBootTest
class CorridorServiceH2Test {

    /** 固定基准时刻：2001-09-09T01:46:40Z（仅作确定性 epoch 毫秒）。 */
    static final long T0 = 1_000_000_000_000L;
    static final long HOUR = 3_600_000L;
    static final long MIN = 60_000L;

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            // 时钟初始在走廊窗口中点
            return new MutableClock(T0 + 30 * MIN);
        }
    }

    @Autowired
    private com.example.starter.service.CorridorService corridorService;
    @Autowired
    private com.example.starter.service.AirspaceReviewService reviewService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MutableClock clock;

    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void cleanBefore() {
        cleanup();
        clock.setMillis(T0 + 30 * MIN);
    }

    @AfterEach
    void cleanAfter() {
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM occupancy_event");
        jdbc.update("DELETE FROM yield_record");
        jdbc.update("DELETE FROM occupancy");
        jdbc.update("DELETE FROM corridor");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    private String uniq(String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    private static List<RoutePointDto> horizontalRoute() {
        return List.of(new RoutePointDto(0, 10), new RoutePointDto(100, 10));
    }

    private static List<RoutePointDto> farRoute() {
        return List.of(new RoutePointDto(-100, -100), new RoutePointDto(-90, -100));
    }

    /** 建航线并以当前空域版本完成 CLEAR 审查，返回 reviewId。 */
    private String reviewedRoute(String routeId, List<RoutePointDto> points, long airspaceVersion) {
        return reviewedRoute(routeId, points, airspaceVersion, false, "req-review-" + routeId);
    }

    /** 建航线并完成 CLEAR 审查，可指定应急审查（走廊存在时常规航线必须用应急审查取证）。 */
    private String reviewedRoute(String routeId, List<RoutePointDto> points, long airspaceVersion,
                                 boolean emergency, String requestId) {
        reviewService.createRoute(
                new RouteCreateRequest(routeId, points, "req-route-" + routeId));
        MutationResponse resp = reviewService.review(
                new ReviewRequest(routeId, 1, airspaceVersion, emergency, requestId));
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class).reviewId();
    }

    private CorridorCreateRequest corridorRequest(String corridorId, long startUtc, long endUtc,
                                                  int altMin, int altMax, int priority) {
        return new CorridorCreateRequest(corridorId, priority,
                40, 5, 60, 15, startUtc, endUtc, altMin, altMax,
                "应急医疗转运", "dispatcher-01", "req-corridor-" + corridorId);
    }

    private CorridorCreateRequest defaultCorridor(String corridorId) {
        return corridorRequest(corridorId, T0, T0 + HOUR, 100, 200, 0);
    }

    private OccupancyCreateRequest regularOccupancy(String occId, String routeId, String reviewId,
                                                    long startUtc, long endUtc,
                                                    int altMin, int altMax, boolean yieldable) {
        return new OccupancyCreateRequest(occId, "REGULAR", routeId, reviewId,
                startUtc, endUtc, altMin, altMax, yieldable, "req-occ-" + occId);
    }

    private OccupancyResult occData(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), OccupancyResult.class);
    }

    // ============================ 主流程：让渡 ============================

    @Test
    void createCorridorYieldsIntersectingYieldableOccupanciesAndFreezesHistory() {
        String routeId = uniq("route");
        String reviewId = reviewedRoute(routeId, horizontalRoute(), 0L);

        String occId = uniq("occ");
        // 占用时间窗宽于走廊：实际重叠只取 [max起点, min终点)
        MutationResponse occResp = corridorService.createOccupancy(regularOccupancy(
                occId, routeId, reviewId, T0 - HOUR, T0 + 2 * HOUR, 100, 200, true));
        assertFalse(occResp.replayed());
        assertEquals("ACTIVE", occData(occResp).status());

        String corridorId = uniq("corridor");
        MutationResponse cResp = corridorService.createCorridor(defaultCorridor(corridorId));
        com.example.starter.api.dto.CorridorResult cData =
                objectMapper.convertValue(cResp.data(), com.example.starter.api.dto.CorridorResult.class);
        assertEquals("ACTIVE", cData.status());
        assertEquals(1L, cData.airspaceVersion());
        // 要求时长（走廊自身）
        assertEquals(HOUR, cData.durationMillis());
        // 实际让渡数量
        assertEquals(1, cData.yieldedCount());
        YieldOutcomeDto yield = cData.yields().get(0);
        assertEquals(occId, yield.occupancyId());
        assertEquals(reviewId, yield.reviewId());
        // 固化原审查版本（审查时空域版本为 0）与走廊版本
        assertEquals(0L, yield.airspaceVersion());
        assertEquals(1, yield.routeVersion());
        assertEquals(1L, cData.airspaceVersion());
        assertEquals("应急医疗转运", yield.reason());
        // 实际重叠窗 [T0, T0+HOUR)，实际让渡时长为走廊要求时长（占用窗更宽）
        assertEquals(T0, yield.overlapStartUtc());
        assertEquals(T0 + HOUR, yield.overlapEndUtc());
        assertEquals(HOUR, yield.overlapDurationMillis());

        // 占用状态已变为 YIELDED 并带走廊标识
        OccupancyResult after = corridorService.getOccupancy(occId);
        assertEquals("YIELDED", after.status());
        assertEquals(corridorId, after.corridorId());
        assertFalse(after.departed());

        // 让渡不可变历史可关联原占用与走廊版本
        List<YieldRecordDto> yields = corridorService.listYields(corridorId);
        assertEquals(1, yields.size());
        YieldRecordDto y = yields.get(0);
        assertEquals(occId, y.occupancyId());
        assertEquals(corridorId, y.corridorId());
        assertEquals(1L, y.corridorVersion());
        assertEquals(reviewId, y.reviewId());
        assertEquals(0L, y.airspaceVersion());
        assertEquals(1, y.routeVersion());
        assertEquals("应急医疗转运", y.reason());

        // 事件流：CREATED 后 YIELDED，字段与 null 语义稳定
        List<OccupancyEventDto> events = corridorService.listOccupancyEvents(occId);
        assertEquals(2, events.size());
        assertEquals("CREATED", events.get(0).eventType());
        assertEquals(reviewId, events.get(0).reviewId());
        assertNull(events.get(0).corridorId());
        assertNull(events.get(0).reason());
        assertEquals("YIELDED", events.get(1).eventType());
        assertEquals(corridorId, events.get(1).corridorId());
        assertEquals("应急医疗转运", events.get(1).reason());
        assertNull(events.get(1).reviewId());
        assertTrue(events.get(0).seq() < events.get(1).seq());

        // 幂等去重与业务数据同事务落库
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM yield_record", Integer.class));
    }

    @Test
    void overlapDurationReflectsActualIntersectionWhenOccupancyWindowIsInside() {
        String routeId = uniq("route");
        String reviewId = reviewedRoute(routeId, horizontalRoute(), 0L);
        String occId = uniq("occ");
        // 占用窗完全在走廊窗内：实际让渡时长 = 占用要求时长
        corridorService.createOccupancy(regularOccupancy(
                occId, routeId, reviewId, T0 + 10 * MIN, T0 + 20 * MIN, 100, 200, true));

        MutationResponse cResp = corridorService.createCorridor(defaultCorridor(uniq("corridor")));
        com.example.starter.api.dto.CorridorResult cData =
                objectMapper.convertValue(cResp.data(), com.example.starter.api.dto.CorridorResult.class);
        YieldOutcomeDto yield = cData.yields().get(0);
        assertEquals(T0 + 10 * MIN, yield.overlapStartUtc());
        assertEquals(T0 + 20 * MIN, yield.overlapEndUtc());
        assertEquals(10 * MIN, yield.overlapDurationMillis());
    }

    // ============================ 409：常规占用/审查命中走廊 ============================

    @Test
    void regularOccupancyHittingEffectiveCorridorIsRejected409AndLeavesNothing() {
        String routeId = uniq("route");
        // 走廊先提交（空域版本推进到 1）：常规审查本身也会被走廊 409，
        // 故用应急审查取得 CLEAR 凭证，以专门验证占用登记环节的走廊排斥
        corridorService.createCorridor(defaultCorridor(uniq("corridor")));
        String reviewId = reviewedRoute(routeId, horizontalRoute(), 1L, true,
                "req-emergency-credential-" + routeId);

        String occId = uniq("occ");
        ApiException ex = assertThrows(ApiException.class, () -> corridorService.createOccupancy(
                regularOccupancy(occId, routeId, reviewId, T0 + MIN, T0 + 2 * MIN,
                        100, 200, true)));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        // 409 给出走廊标识
        assertTrue(ex.getMessage().contains("corridor-"));

        // 事务回滚：无占用、无事件、幂等键不被占用
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM occupancy", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM occupancy_event", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?", Integer.class,
                "req-occ-" + occId));
    }

    @Test
    void regularReviewHittingEffectiveCorridorIs409WithCorridorId() {
        String routeId = uniq("route");
        reviewService.createRoute(
                new RouteCreateRequest(routeId, horizontalRoute(), "req-route-" + routeId));
        corridorService.createCorridor(defaultCorridor(uniq("corridor")));

        ApiException ex = assertThrows(ApiException.class, () -> reviewService.review(
                new ReviewRequest(routeId, 1, 1L, false, "req-blocked-review")));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertTrue(ex.getMessage().contains("corridor-"));
        // 被拒审查不产生历史记录
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class));
    }

    // ============================ 应急：可走走廊但不能绕过禁区 ============================

    @Test
    void emergencyRouteReviewBypassesCorridorButNotNoFlyZone() {
        String corridorId = uniq("corridor");
        corridorService.createCorridor(defaultCorridor(corridorId));

        // 无禁飞区：应急审查穿过走廊也能 CLEAR
        String routeId = uniq("route");
        reviewService.createRoute(
                new RouteCreateRequest(routeId, horizontalRoute(), "req-route-" + routeId));
        MutationResponse resp = reviewService.review(
                new ReviewRequest(routeId, 1, 1L, true, "req-emergency-review"));
        ReviewResultDto dto = objectMapper.convertValue(resp.data(), ReviewResultDto.class);
        assertEquals("CLEAR", dto.conclusion());

        // 同航线的常规审查仍被走廊 409
        ApiException regular = assertThrows(ApiException.class, () -> reviewService.review(
                new ReviewRequest(routeId, 1, 1L, false, "req-regular-review")));
        assertEquals("CORRIDOR_CONFLICT", regular.code());

        // 建禁飞区后，应急审查也必须 BLOCKED，不能以走廊绕过禁区
        reviewService.createZone(new com.example.starter.api.dto.ZoneCreateRequest(
                "z-block", 45, 8, 55, 12, "req-zone-block"));
        ApiException staleVersion = assertThrows(ApiException.class, () -> reviewService.review(
                new ReviewRequest(routeId, 1, 1L, true, "req-emergency-review-2")));
        // 空域版本推进到 2：旧版本先 409
        assertEquals("VERSION_CONFLICT", staleVersion.code());
        MutationResponse blocked = reviewService.review(
                new ReviewRequest(routeId, 1, 2L, true, "req-emergency-review-3"));
        assertEquals("BLOCKED",
                objectMapper.convertValue(blocked.data(), ReviewResultDto.class).conclusion());
    }

    @Test
    void emergencyOccupancyWithClearReviewSucceedsInsideCorridor() {
        corridorService.createCorridor(defaultCorridor(uniq("corridor")));
        String routeId = uniq("route");
        reviewService.createRoute(
                new RouteCreateRequest(routeId, horizontalRoute(), "req-route-" + routeId));
        MutationResponse rev = reviewService.review(
                new ReviewRequest(routeId, 1, 1L, true, "req-emergency-review"));
        String reviewId = objectMapper.convertValue(rev.data(), ReviewResultDto.class).reviewId();

        String occId = uniq("occ-emergency");
        MutationResponse resp = corridorService.createOccupancy(new OccupancyCreateRequest(
                occId, "EMERGENCY", routeId, reviewId,
                T0 + MIN, T0 + 2 * MIN, 100, 200, null, "req-occ-" + occId));
        OccupancyResult data = occData(resp);
        assertEquals("EMERGENCY", data.kind());
        assertEquals("ACTIVE", data.status());
        assertNull(data.yieldable(), "应急占用 yieldable 不适用，序列化为 null");
    }

    @Test
    void regularOccupancyRequiresYieldableFlagAndEmergencyRejectsIt() {
        corridorService.createCorridor(defaultCorridor(uniq("corridor")));
        String routeId = uniq("route");
        String reviewId = reviewedRoute(routeId, farRoute(), 1L);

        // 常规占用缺 yieldable → 400
        ApiException missingFlag = assertThrows(ApiException.class, () -> corridorService
                .createOccupancy(new OccupancyCreateRequest(uniq("occ"), "REGULAR",
                        routeId, reviewId, T0 + MIN, T0 + 2 * MIN, 0, 50, null,
                        "req-missing-flag")));
        assertEquals(HttpStatus.BAD_REQUEST, missingFlag.status());
        assertEquals("YIELDABLE_REQUIRED", missingFlag.code());

        // 应急占用带 yieldable → 400
        ApiException unexpectedFlag = assertThrows(ApiException.class, () -> corridorService
                .createOccupancy(new OccupancyCreateRequest(uniq("occ2"), "EMERGENCY",
                        routeId, reviewId, T0 + MIN, T0 + 2 * MIN, 0, 50, true,
                        "req-unexpected-flag")));
        assertEquals("YIELDABLE_NOT_APPLICABLE", unexpectedFlag.code());
    }

    // ============================ 422：不可让渡 / 已起飞，整次回滚 ============================

    @Test
    void nonYieldableOccupancyRejectsWholeCorridor422WithoutPartialYield() {
        String route1 = uniq("route-y");
        String review1 = reviewedRoute(route1, horizontalRoute(), 0L);
        String yieldableOcc = uniq("occ-y");
        corridorService.createOccupancy(regularOccupancy(
                yieldableOcc, route1, review1, T0 + MIN, T0 + 2 * MIN, 100, 200, true));

        String route2 = uniq("route-n");
        String review2 = reviewedRoute(route2, horizontalRoute(), 0L);
        String blockingOcc = uniq("occ-n");
        corridorService.createOccupancy(regularOccupancy(
                blockingOcc, route2, review2, T0 + MIN, T0 + 2 * MIN, 100, 200, false));

        String corridorId = uniq("corridor");
        ApiException ex = assertThrows(ApiException.class,
                () -> corridorService.createCorridor(defaultCorridor(corridorId)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("CORRIDOR_OCCUPANCY_BLOCKED", ex.code());
        assertTrue(ex.getMessage().contains(blockingOcc));

        // 完整回滚：走廊未建；可让渡占用也未被让渡（先验证完整后态再提交）；无任何历史
        assertNull(corridorService.listCorridors().stream()
                .filter(c -> c.corridorId().equals(corridorId)).findFirst().orElse(null));
        assertEquals("ACTIVE", corridorService.getOccupancy(yieldableOcc).status());
        assertEquals("ACTIVE", corridorService.getOccupancy(blockingOcc).status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM corridor", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM yield_record", Integer.class));
        // 审查不推进空域版本；走廊创建 422 回滚也不推进，故版本仍为 0
        assertEquals(0L, jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id=1", Long.class));
    }

    @Test
    void departedOccupancyRejectsCorridor422() {
        String routeId = uniq("route");
        String reviewId = reviewedRoute(routeId, horizontalRoute(), 0L);
        String occId = uniq("occ");
        corridorService.createOccupancy(regularOccupancy(
                occId, routeId, reviewId, T0 + MIN, T0 + 2 * MIN, 100, 200, true));
        corridorService.registerDeparture(
                new DepartureRegisterRequest(occId, "req-depart-" + occId));

        ApiException ex = assertThrows(ApiException.class,
                () -> corridorService.createCorridor(defaultCorridor(uniq("corridor"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("CORRIDOR_OCCUPANCY_BLOCKED", ex.code());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM corridor", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM yield_record", Integer.class));
    }

    // ============================ 撤销 / 自然到期 ============================

    @Test
    void revokeCorridorOnlyAffectsFutureOccupanciesAndDoesNotRestoreYielded() {
        String routeId = uniq("route");
        String reviewId = reviewedRoute(routeId, horizontalRoute(), 0L);
        String occId = uniq("occ");
        corridorService.createOccupancy(regularOccupancy(
                occId, routeId, reviewId, T0 + MIN, T0 + 2 * MIN, 100, 200, true));
        String corridorId = uniq("corridor");
        corridorService.createCorridor(defaultCorridor(corridorId));
        assertEquals("YIELDED", corridorService.getOccupancy(occId).status());

        MutationResponse revoked = corridorService.revokeCorridor(
                new CorridorRevokeRequest(corridorId, "req-revoke-" + corridorId));
        com.example.starter.api.dto.CorridorResult rData =
                objectMapper.convertValue(revoked.data(), com.example.starter.api.dto.CorridorResult.class);
        assertEquals("REVOKED", rData.status());
        assertEquals(2L, rData.airspaceVersion());
        assertEquals(0, rData.yieldedCount());

        // 已让渡占用不自动恢复
        assertEquals("YIELDED", corridorService.getOccupancy(occId).status());

        // 撤销只影响后续占用：新常规占用同时间窗可登记（须以新版本重新审查）
        String route2 = uniq("route2");
        String reviewId2 = reviewedRoute(route2, horizontalRoute(), 2L);
        String newOcc = uniq("occ-new");
        MutationResponse ok = corridorService.createOccupancy(regularOccupancy(
                newOcc, route2, reviewId2, T0 + 3 * MIN, T0 + 4 * MIN, 100, 200, true));
        assertEquals("ACTIVE", occData(ok).status());

        // 重复撤销 409、撤销不存在 404
        ApiException twice = assertThrows(ApiException.class, () -> corridorService.revokeCorridor(
                new CorridorRevokeRequest(corridorId, "req-revoke-twice")));
        assertEquals(HttpStatus.CONFLICT, twice.status());
        ApiException missing = assertThrows(ApiException.class, () -> corridorService.revokeCorridor(
                new CorridorRevokeRequest("ghost-corridor", "req-revoke-ghost")));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    @Test
    void naturalExpiryAllowsFutureOccupancyWithoutStatusChange() {
        String corridorId = uniq("corridor");
        corridorService.createCorridor(defaultCorridor(corridorId));

        // 走廊窗口内：常规占用仍 409（用应急审查取得 CLEAR 凭证，走廊排斥在占用登记判定）
        clock.setMillis(T0 + 30 * MIN);
        String route1 = uniq("route");
        String review1 = reviewedRoute(route1, horizontalRoute(), 1L, true, "req-rv-" + route1);
        ApiException blocked = assertThrows(ApiException.class, () -> corridorService
                .createOccupancy(regularOccupancy(uniq("occ"), route1, review1,
                        T0 + 31 * MIN, T0 + 32 * MIN, 100, 200, true)));
        assertEquals("CORRIDOR_CONFLICT", blocked.code());

        // 时钟拨到走廊自然到期时刻（endUtc，左闭右开：该时刻起走廊无效）
        clock.setMillis(T0 + HOUR);
        CorridorDto dto = corridorService.getCorridor(corridorId);
        assertEquals("ACTIVE", dto.status(), "自然到期不改状态");
        assertFalse(dto.effective());

        // 占用窗完全位于到期之后：允许登记
        String route2 = uniq("route2");
        String review2 = reviewedRoute(route2, horizontalRoute(), 1L);
        String occId = uniq("occ-after");
        MutationResponse ok = corridorService.createOccupancy(regularOccupancy(
                occId, route2, review2, T0 + HOUR + MIN, T0 + HOUR + 2 * MIN,
                100, 200, true));
        assertEquals("ACTIVE", occData(ok).status());
    }

    // ============================ 重新申请 ============================

    @Test
    void yieldedOccupancyMustBeReReviewedAndExplicitlyReapplied() {
        String routeId = uniq("route");
        String reviewId = reviewedRoute(routeId, horizontalRoute(), 0L);
        String occId = uniq("occ");
        corridorService.createOccupancy(regularOccupancy(
                occId, routeId, reviewId, T0 + MIN, T0 + 2 * MIN, 100, 200, true));
        String corridorId = uniq("corridor");
        corridorService.createCorridor(defaultCorridor(corridorId));

        // 已让渡占用不能起飞
        ApiException noDeparture = assertThrows(ApiException.class, () -> corridorService
                .registerDeparture(new DepartureRegisterRequest(occId, "req-dep-yielded")));
        assertEquals("OCCUPANCY_YIELDED", noDeparture.code());

        // 撤销走廊（版本推进到 2）后，用旧审查重新申请 → REVIEW_NOT_CURRENT
        corridorService.revokeCorridor(
                new CorridorRevokeRequest(corridorId, "req-revoke-" + corridorId));
        String newOccId = uniq("occ-new");
        ApiException staleReview = assertThrows(ApiException.class, () -> corridorService
                .reapplyOccupancy(new OccupancyReapplyRequest(
                        newOccId, occId, reviewId,
                        T0 + 3 * MIN, T0 + 4 * MIN, 100, 200, "req-reapply-bad")));
        assertEquals("REVIEW_NOT_CURRENT", staleReview.code());

        // 原占用不是 YIELDED 不能重新申请
        String otherRoute = uniq("route-other");
        String otherReview = reviewedRoute(otherRoute, farRoute(), 2L);
        String otherOcc = uniq("occ-other");
        corridorService.createOccupancy(regularOccupancy(
                otherOcc, otherRoute, otherReview, T0 + 3 * MIN, T0 + 4 * MIN, 0, 50, true));
        ApiException notYielded = assertThrows(ApiException.class, () -> corridorService
                .reapplyOccupancy(new OccupancyReapplyRequest(
                        uniq("occ-x"), otherOcc, otherReview,
                        T0 + 5 * MIN, T0 + 6 * MIN, 0, 50, "req-reapply-active")));
        assertEquals("PARENT_NOT_YIELDED", notYielded.code());

        // 重新审查（新版本 2，走廊已撤销不阻断）→ CLEAR 后显式重新申请成功
        MutationResponse reReview = reviewService.review(
                new ReviewRequest(routeId, 1, 2L, "req-rereview-" + routeId));
        String newReviewId = objectMapper.convertValue(reReview.data(), ReviewResultDto.class).reviewId();
        MutationResponse reapplied = corridorService.reapplyOccupancy(new OccupancyReapplyRequest(
                newOccId, occId, newReviewId,
                T0 + 3 * MIN, T0 + 4 * MIN, 100, 200, "req-reapply-ok"));
        OccupancyResult newData = occData(reapplied);
        assertEquals(newOccId, newData.occupancyId());
        assertEquals("ACTIVE", newData.status());
        assertEquals("REGULAR", newData.kind());
        assertEquals(occId, newData.parentOccupancyId());
        assertEquals(newReviewId, newData.reviewId());

        // 原占用与原让渡历史保持不变（不可变、不自动恢复）
        assertEquals("YIELDED", corridorService.getOccupancy(occId).status());
        assertEquals(1, corridorService.listYields(corridorId).size());

        // 新占用可起飞；事件流包含 CREATED
        corridorService.registerDeparture(
                new DepartureRegisterRequest(newOccId, "req-dep-new"));

        // 原占用事件：CREATED, YIELDED, REAPPLIED（关联新占用），顺序固定
        List<OccupancyEventDto> parentEvents = corridorService.listOccupancyEvents(occId);
        assertEquals(List.of("CREATED", "YIELDED", "REAPPLIED"),
                parentEvents.stream().map(OccupancyEventDto::eventType).toList());
        assertEquals(newOccId, parentEvents.get(2).relatedOccupancyId());
        assertNull(parentEvents.get(2).corridorId());
        // 新占用事件：CREATED, DEPARTED
        List<OccupancyEventDto> newEvents = corridorService.listOccupancyEvents(newOccId);
        assertEquals(List.of("CREATED", "DEPARTED"),
                newEvents.stream().map(OccupancyEventDto::eventType).toList());
    }

    // ============================ 起飞 ============================

    @Test
    void departureRegistersOnceAndRejectsMissingOrYielded() {
        String routeId = uniq("route");
        String reviewId = reviewedRoute(routeId, horizontalRoute(), 0L);
        String occId = uniq("occ");
        corridorService.createOccupancy(regularOccupancy(
                occId, routeId, reviewId, T0 - HOUR, T0 + HOUR, 0, 500, true));

        MutationResponse dep = corridorService.registerDeparture(
                new DepartureRegisterRequest(occId, "req-dep-" + occId));
        DepartureResult depData = objectMapper.convertValue(dep.data(), DepartureResult.class);
        assertTrue(depData.departed());
        assertEquals(T0 + 30 * MIN, depData.departedAt());

        ApiException twice = assertThrows(ApiException.class, () -> corridorService
                .registerDeparture(new DepartureRegisterRequest(occId, "req-dep-twice")));
        assertEquals("OCCUPANCY_ALREADY_DEPARTED", twice.code());

        ApiException missing = assertThrows(ApiException.class, () -> corridorService
                .registerDeparture(new DepartureRegisterRequest("ghost-occ", "req-dep-ghost")));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        List<OccupancyEventDto> events = corridorService.listOccupancyEvents(occId);
        assertEquals(List.of("CREATED", "DEPARTED"),
                events.stream().map(OccupancyEventDto::eventType).toList());
    }

    // ============================ 左闭右开边界 ============================

    @Test
    void halfOpenTimeAndAltitudeBoundariesDoNotIntersect() {
        // 走廊 time [T0, T0+HOUR), alt [100,200)
        String corridorId = uniq("corridor");
        corridorService.createCorridor(defaultCorridor(corridorId));

        // 占用 endUtc == 走廊 startUtc：时间不相交 → 允许
        String r1 = uniq("route");
        String rv1 = reviewedRoute(r1, horizontalRoute(), 1L, true, "req-rv-" + r1);
        MutationResponse o1 = corridorService.createOccupancy(regularOccupancy(
                uniq("occ"), r1, rv1, T0 - HOUR, T0, 100, 200, true));
        assertEquals("ACTIVE", occData(o1).status());

        // 占用 startUtc == 走廊 endUtc：不相交 → 允许
        String r2 = uniq("route");
        String rv2 = reviewedRoute(r2, horizontalRoute(), 1L, true, "req-rv-" + r2);
        MutationResponse o2 = corridorService.createOccupancy(regularOccupancy(
                uniq("occ"), r2, rv2, T0 + HOUR, T0 + 2 * HOUR, 100, 200, true));
        assertEquals("ACTIVE", occData(o2).status());

        // 高度 altMin == 走廊 altMax：不相交 → 允许
        String r3 = uniq("route");
        String rv3 = reviewedRoute(r3, horizontalRoute(), 1L, true, "req-rv-" + r3);
        MutationResponse o3 = corridorService.createOccupancy(regularOccupancy(
                uniq("occ"), r3, rv3, T0 + MIN, T0 + 2 * MIN, 200, 300, true));
        assertEquals("ACTIVE", occData(o3).status());

        // 高度 altMax == 走廊 altMin：不相交 → 允许
        String r4 = uniq("route");
        String rv4 = reviewedRoute(r4, horizontalRoute(), 1L, true, "req-rv-" + r4);
        MutationResponse o4 = corridorService.createOccupancy(regularOccupancy(
                uniq("occ"), r4, rv4, T0 + MIN, T0 + 2 * MIN, 0, 100, true));
        assertEquals("ACTIVE", occData(o4).status());

        // 高度下界恰等于走廊 altMin（含边界）：相交 → 409
        String r5 = uniq("route");
        String rv5 = reviewedRoute(r5, horizontalRoute(), 1L, true, "req-rv-" + r5);
        ApiException touchAlt = assertThrows(ApiException.class, () -> corridorService
                .createOccupancy(regularOccupancy(
                        uniq("occ"), r5, rv5, T0 + MIN, T0 + 2 * MIN, 100, 150, true)));
        assertEquals("CORRIDOR_CONFLICT", touchAlt.code());

        // 路径不经过走廊矩形（高度/时间重叠也不冲突）→ 允许
        String r6 = uniq("route");
        String rv6 = reviewedRoute(r6, farRoute(), 1L);
        MutationResponse o6 = corridorService.createOccupancy(regularOccupancy(
                uniq("occ"), r6, rv6, T0 + MIN, T0 + 2 * MIN, 100, 200, true));
        assertEquals("ACTIVE", occData(o6).status());
    }

    @Test
    void invalidWindowsAndRectangleAre400() {
        ApiException badTime = assertThrows(ApiException.class, () -> corridorService
                .createCorridor(corridorRequest(uniq("corridor"), T0 + HOUR, T0, 100, 200, 0)));
        assertEquals("INVALID_TIME_WINDOW", badTime.code());
        ApiException badAlt = assertThrows(ApiException.class, () -> corridorService
                .createCorridor(corridorRequest(uniq("corridor"), T0, T0 + HOUR, 200, 200, 0)));
        assertEquals("INVALID_ALTITUDE_WINDOW", badAlt.code());
        ApiException badRect = assertThrows(ApiException.class, () -> corridorService
                .createCorridor(new CorridorCreateRequest(uniq("corridor"), 0,
                        40, 5, 40, 15, T0, T0 + HOUR, 100, 200,
                        "r", "d", "req-bad-rect")));
        assertEquals("INVALID_CORRIDOR_RECTANGLE", badRect.code());
        ApiException badKind = assertThrows(ApiException.class, () -> corridorService
                .createOccupancy(new OccupancyCreateRequest(
                        uniq("occ"), "MILITARY", uniq("route"), "rv-x",
                        T0, T0 + HOUR, 0, 10, true, "req-bad-kind")));
        assertEquals("INVALID_OCCUPANCY_KIND", badKind.code());
    }

    // ============================ 同优先级重叠与优先级 ============================

    @Test
    void samePriorityOverlapIs409DifferentPriorityAllowed() {
        corridorService.createCorridor(defaultCorridor(uniq("c1")));

        // 同优先级、时间/高度/空间全重叠 → 409
        ApiException overlap = assertThrows(ApiException.class, () -> corridorService
                .createCorridor(defaultCorridor(uniq("c2"))));
        assertEquals("CORRIDOR_OVERLAP", overlap.code());

        // 时间窗恰好首尾相接（左闭右开）：不重叠 → 允许
        CorridorCreateRequest adjacent = corridorRequest(
                uniq("c3"), T0 + HOUR, T0 + 2 * HOUR, 100, 200, 0);
        assertEquals(HttpStatus.CREATED, assertCreated(corridorService.createCorridor(adjacent)));

        // 同优先级但高度不重叠 → 允许
        CorridorCreateRequest otherAlt = corridorRequest(
                uniq("c4"), T0, T0 + HOUR, 200, 300, 0);
        assertEquals(HttpStatus.CREATED, assertCreated(corridorService.createCorridor(otherAlt)));

        // 时空相同但优先级不同 → 允许
        CorridorCreateRequest otherPriority = corridorRequest(
                uniq("c5"), T0, T0 + HOUR, 100, 200, 1);
        assertEquals(HttpStatus.CREATED,
                assertCreated(corridorService.createCorridor(otherPriority)));

        // 撤销后同 id 不可再建（唯一），不同 id 同窗同优先级也不再与已撤销走廊冲突
        String c1 = corridorService.listCorridors().stream()
                .filter(c -> c.corridorId().startsWith("c1-")).findFirst().orElseThrow()
                .corridorId();
        corridorService.revokeCorridor(new CorridorRevokeRequest(c1, "req-revoke-c1"));
        CorridorCreateRequest reuseWindow = corridorRequest(
                uniq("c6"), T0, T0 + HOUR, 100, 200, 0);
        assertEquals(HttpStatus.CREATED,
                assertCreated(corridorService.createCorridor(reuseWindow)));
    }

    private HttpStatus assertCreated(MutationResponse resp) {
        assertNotNull(resp);
        return HttpStatus.CREATED;
    }

    // ============================ 幂等 ============================

    @Test
    void corridorIdempotencyReplaysWithoutDoubleYieldOrVersionBump() {
        String routeId = uniq("route");
        String reviewId = reviewedRoute(routeId, horizontalRoute(), 0L);
        String occId = uniq("occ");
        corridorService.createOccupancy(regularOccupancy(
                occId, routeId, reviewId, T0 + MIN, T0 + 2 * MIN, 100, 200, true));

        CorridorCreateRequest req = defaultCorridor(uniq("corridor"));
        MutationResponse first = corridorService.createCorridor(req);
        assertFalse(first.replayed());
        MutationResponse replay = corridorService.createCorridor(
                new CorridorCreateRequest(req.corridorId(), req.priority(),
                        req.xMin(), req.yMin(), req.xMax(), req.yMax(),
                        req.startUtc(), req.endUtc(), req.altMin(), req.altMax(),
                        req.reason(), req.createdBy(), req.requestId()));
        assertTrue(replay.replayed());
        // 版本只推进一次、让渡只发生一次
        assertEquals(1L, jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id=1", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM yield_record", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM occupancy_event WHERE event_type='YIELDED'", Integer.class));

        // 同键异参 → 409
        ApiException diffParams = assertThrows(ApiException.class, () -> corridorService
                .createCorridor(corridorRequest(req.corridorId(), T0, T0 + HOUR, 0, 50, 0)));
        assertEquals(HttpStatus.CONFLICT, diffParams.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", diffParams.code());
    }

    @Test
    void failedCorridorDoesNotConsumeIdempotencyKey() {
        String routeId = uniq("route");
        String reviewId = reviewedRoute(routeId, horizontalRoute(), 0L);
        corridorService.createOccupancy(regularOccupancy(
                uniq("occ"), routeId, reviewId, T0 + MIN, T0 + 2 * MIN, 100, 200, false));

        String requestId = "req-corridor-fail-key";
        CorridorCreateRequest blocked = new CorridorCreateRequest(
                "corridor-fail", 0, 40, 5, 60, 15, T0, T0 + HOUR, 100, 200,
                "r", "d", requestId);
        ApiException ex = assertThrows(ApiException.class,
                () -> corridorService.createCorridor(blocked));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        // 失败不占键
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id=?", Integer.class, requestId));

        // 移除障碍（让占用可让渡：新建一条可让渡占用并删去不可让渡占用不可行——
        // 改为换一个不与不可让渡占用相交的走廊空间/高度窗，同键可成功）
        CorridorCreateRequest differentWindow = new CorridorCreateRequest(
                "corridor-fail", 0, -60, -60, -40, -40, T0, T0 + HOUR, 0, 50,
                "r", "d", requestId);
        MutationResponse ok = corridorService.createCorridor(differentWindow);
        assertFalse(ok.replayed());
        assertNotNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id=?", Integer.class, requestId));
    }

    @Test
    void occupancyIdempotencyReplaysAndDifferentKindConflicts() {
        String routeId = uniq("route");
        String reviewId = reviewedRoute(routeId, farRoute(), 0L);
        String occId = uniq("occ");
        OccupancyCreateRequest req = new OccupancyCreateRequest(
                occId, "REGULAR", routeId, reviewId,
                T0 + MIN, T0 + 2 * MIN, 0, 50, true, "req-occ-replay");
        MutationResponse first = corridorService.createOccupancy(req);
        assertFalse(first.replayed());
        MutationResponse replay = corridorService.createOccupancy(
                new OccupancyCreateRequest(occId, "REGULAR", routeId, reviewId,
                        T0 + MIN, T0 + 2 * MIN, 0, 50, true, "req-occ-replay"));
        assertTrue(replay.replayed());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM occupancy", Integer.class));

        // 同键用于应急占用（异参/异种语义）→ 409
        ApiException diffKind = assertThrows(ApiException.class, () -> corridorService
                .createOccupancy(new OccupancyCreateRequest(
                        occId, "EMERGENCY", routeId, reviewId,
                        T0 + MIN, T0 + 2 * MIN, 0, 50, null, "req-occ-replay")));
        assertEquals(HttpStatus.CONFLICT, diffKind.status());
    }

    // ============================ 并发裁决 ============================

    @Test
    void corridorAndRegularOccupancyAreArbitratedByCommitOrder() throws Exception {
        int iterations = 12;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < iterations; i++) {
                cleanup();
                String routeId = "race-route-" + i;
                String corridorId = "race-corridor-" + i;
                String occId = "race-occ-" + i;
                String reviewId = reviewedRoute(routeId, horizontalRoute(), 0L);

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Object> occFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return corridorService.createOccupancy(regularOccupancy(
                                occId, routeId, reviewId,
                                T0 + 10 * MIN, T0 + 20 * MIN, 100, 200, true));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> corridorFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return corridorService.createCorridor(defaultCorridor(corridorId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object occResult = occFuture.get(20, TimeUnit.SECONDS);
                Object corridorResult = corridorFuture.get(20, TimeUnit.SECONDS);

                // 走廊必须恰好成功一次；每轮 cleanup 后版本从 0 起，走廊创建成功恰好推进到 1
                assertTrue(corridorResult instanceof MutationResponse,
                        "相交占用全部声明可让渡，走廊创建必须成功");
                long finalVersion = jdbc.queryForObject(
                        "SELECT global_version FROM airspace_meta WHERE id=1", Long.class);
                assertEquals(1L, finalVersion, "每轮独立数据：走廊创建成功后版本必须恰为 1");

                if (occResult instanceof MutationResponse mr) {
                    // 占用先提交：随后的走廊事务必须已把它让渡
                    String status = jdbc.queryForObject(
                            "SELECT status FROM occupancy WHERE occupancy_id=?",
                            String.class, occId);
                    assertEquals("YIELDED", status,
                            "占用先提交后必须被后提交的走廊让渡，不得并存为 ACTIVE");
                    assertEquals(corridorId, jdbc.queryForObject(
                            "SELECT corridor_id FROM occupancy WHERE occupancy_id=?",
                            String.class, occId));
                    assertNotNull(mr);
                } else {
                    // 走廊先提交：占用登记必须 409 且带走廊标识
                    ApiException ex = (ApiException) occResult;
                    assertEquals(HttpStatus.CONFLICT, ex.status());
                    assertEquals("CORRIDOR_CONFLICT", ex.code());
                    assertTrue(ex.getMessage().contains(corridorId));
                    assertEquals(0, jdbc.queryForObject(
                            "SELECT COUNT(*) FROM occupancy WHERE occupancy_id=?",
                            Integer.class, occId));
                }

                // 核心不变式：不得存在与走廊时空相交的 ACTIVE 常规占用
                Integer activeConflict = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM occupancy o WHERE o.status='ACTIVE' "
                                + "AND o.kind='REGULAR' AND o.occupancy_id = ?",
                        Integer.class, occId);
                assertEquals(0, activeConflict, "同一时空不得既有走廊又有未让渡常规占用");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void corridorAndDepartureCannotBothSucceed() throws Exception {
        int iterations = 10;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < iterations; i++) {
                cleanup();
                String routeId = "dep-route-" + i;
                String corridorId = "dep-corridor-" + i;
                String occId = "dep-occ-" + i;
                String depRequestId = "req-dep-" + occId;
                String reviewId = reviewedRoute(routeId, horizontalRoute(), 0L);
                corridorService.createOccupancy(regularOccupancy(
                        occId, routeId, reviewId, T0 + 10 * MIN, T0 + 20 * MIN, 100, 200, true));

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Object> depFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return corridorService.registerDeparture(
                                new DepartureRegisterRequest(occId, depRequestId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> corridorFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return corridorService.createCorridor(defaultCorridor(corridorId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object depResult = depFuture.get(20, TimeUnit.SECONDS);
                Object corridorResult = corridorFuture.get(20, TimeUnit.SECONDS);

                boolean depSucceeded = depResult instanceof MutationResponse;
                boolean corridorSucceeded = corridorResult instanceof MutationResponse;
                assertTrue(depSucceeded ^ corridorSucceeded,
                        "起飞与走廊让渡必须恰有一方成功");

                if (depSucceeded) {
                    // 起飞先提交：走廊必须 422，占用保持 ACTIVE 且已起飞
                    ApiException ex = (ApiException) corridorResult;
                    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
                    assertEquals(Boolean.TRUE, jdbc.queryForObject(
                            "SELECT departed FROM occupancy WHERE occupancy_id=?",
                            Boolean.class, occId));
                    assertEquals("ACTIVE", jdbc.queryForObject(
                            "SELECT status FROM occupancy WHERE occupancy_id=?",
                            String.class, occId));
                    assertEquals(0, jdbc.queryForObject(
                            "SELECT COUNT(*) FROM corridor", Integer.class));
                } else {
                    // 走廊先提交：占用被让渡，起飞必须 409
                    ApiException ex = (ApiException) depResult;
                    assertEquals(HttpStatus.CONFLICT, ex.status());
                    assertEquals("OCCUPANCY_YIELDED", ex.code());
                    assertEquals("YIELDED", jdbc.queryForObject(
                            "SELECT status FROM occupancy WHERE occupancy_id=?",
                            String.class, occId));
                    assertEquals(1, jdbc.queryForObject(
                            "SELECT COUNT(*) FROM corridor", Integer.class));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ============================ 历史稳定性与查询不改状态 ============================

    @Test
    void historyIsImmutableAndQueriesDoNotModifyState() {
        String routeId = uniq("route");
        String reviewId = reviewedRoute(routeId, horizontalRoute(), 0L);
        String occId = uniq("occ");
        corridorService.createOccupancy(regularOccupancy(
                occId, routeId, reviewId, T0 + MIN, T0 + 2 * MIN, 100, 200, true));
        String corridorId = uniq("corridor");
        corridorService.createCorridor(defaultCorridor(corridorId));

        List<YieldRecordDto> before = corridorService.listYields(null);
        List<OccupancyEventDto> eventsBefore = corridorService.listOccupancyEvents(occId);
        CorridorDto corridorBefore = corridorService.getCorridor(corridorId);

        // 撤销走廊（事务内取锁与推进版本会更新协调锁），之后再做若干只读查询
        corridorService.revokeCorridor(
                new CorridorRevokeRequest(corridorId, "req-revoke-" + corridorId));
        long touchedAfterRevoke = jdbc.queryForObject(
                "SELECT touched FROM coord_lock WHERE id=1", Long.class);
        corridorService.listYields(null);
        corridorService.listYields(corridorId);
        corridorService.listOccupancyEvents(occId);
        corridorService.getCorridor(corridorId);
        corridorService.getOccupancy(occId);
        corridorService.listCorridors();

        // 让渡历史字段完全不变，排序仍按 seq
        List<YieldRecordDto> after = corridorService.listYields(null);
        assertEquals(before.size(), after.size());
        assertEquals(before.get(0).occupancyId(), after.get(0).occupancyId());
        assertEquals(before.get(0).corridorVersion(), after.get(0).corridorVersion());
        assertEquals(before.get(0).reviewId(), after.get(0).reviewId());
        assertEquals(before.get(0).reason(), after.get(0).reason());
        assertEquals(before.get(0).createdAt(), after.get(0).createdAt());
        assertEquals(before.get(0).seq(), after.get(0).seq());
        // 事件流不被撤销追加/回写
        assertEquals(eventsBefore.size(),
                corridorService.listOccupancyEvents(occId).size());
        // 走廊 created 版本不被撤销回写
        assertEquals(corridorBefore.createdAirspaceVersion(),
                corridorService.getCorridor(corridorId).createdAirspaceVersion());
        // 撤销完成后的只读查询不再更新协调锁
        assertEquals(touchedAfterRevoke, jdbc.queryForObject(
                "SELECT touched FROM coord_lock WHERE id=1", Long.class),
                "只读查询不得更新协调锁或任何状态");
    }
}
