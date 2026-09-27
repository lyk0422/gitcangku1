package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.CorridorCreateRequest;
import com.example.starter.api.dto.CorridorCreateResult;
import com.example.starter.api.dto.CorridorDetailDto;
import com.example.starter.api.dto.CorridorRevokeRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyCreateRequest;
import com.example.starter.api.dto.OccupancyDetailDto;
import com.example.starter.api.dto.OccupancyResult;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.TakeoffRequest;
import com.example.starter.api.dto.TakeoffResult;
import com.example.starter.api.dto.YieldRecordDto;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.service.AirspaceReviewService;
import com.example.starter.service.EmergencyCorridorService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空域应急走廊预留与常规占用强制让渡的 H2 数据库测试（MODE=MySQL）。
 * 覆盖主流程、失败分支、左闭右开边界、不可变历史、幂等与真实并发互斥。
 */
@SpringBootTest
@DisplayName("应急走廊与强制让渡（H2）")
class EmergencyCorridorServiceH2Test {

    @Autowired
    private EmergencyCorridorService corridorService;
    @Autowired
    private AirspaceReviewService reviewService;
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
        jdbc.update("DELETE FROM corridor_yield");
        jdbc.update("DELETE FROM occupancy");
        jdbc.update("DELETE FROM emergency_corridor");
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

    private long globalVersion() {
        Long v = jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id = 1", Long.class);
        return v == null ? -1 : v;
    }

    private void createRoute(String routeId, String routeType, int... xy) {
        reviewService.createRoute(new RouteCreateRequest(
                routeId, pts(xy), routeType, "req-" + rid("route")));
    }

    private ReviewResultDto review(String routeId, int routeVersion) {
        MutationResponse resp = reviewService.review(new ReviewRequest(
                routeId, routeVersion, globalVersion(), "req-" + rid("review")));
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    private ReviewResultDto reviewClear(String routeId, int routeVersion) {
        ReviewResultDto dto = review(routeId, routeVersion);
        assertEquals("CLEAR", dto.conclusion());
        return dto;
    }

    /** 默认走廊请求：区域 (40,5)-(60,15)，调度员 ops-lead。 */
    private CorridorCreateRequest corridorReq(String id, int priority,
                                              long startAt, long endAt,
                                              int altLower, int altUpper) {
        return new CorridorCreateRequest(id, 40, 5, 60, 15, startAt, endAt,
                altLower, altUpper, priority, "ops-lead", null, "req-" + rid("corridor"));
    }

    private OccupancyCreateRequest occupancyReq(String id, String routeId,
                                                long startAt, long endAt,
                                                int altLower, int altUpper, boolean yieldable) {
        return new OccupancyCreateRequest(id, routeId, 1, startAt, endAt,
                altLower, altUpper, yieldable, "req-" + rid("occ"));
    }

    private CorridorCreateResult corridorData(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), CorridorCreateResult.class);
    }

    private OccupancyResult occupancyData(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), OccupancyResult.class);
    }

    // ============================ 主流程 ============================

    @Test
    @DisplayName("主流程：走廊创建同事务让渡相交常规占用并固化原审查版本与原因")
    void corridorCreateYieldsIntersectingRegularOccupancy() {
        long now = System.currentTimeMillis();
        createRoute("r1", "REGULAR", 0, 10, 100, 10);
        ReviewResultDto rv = reviewClear("r1", 1);

        // 相交占用 o1（高度 [100,200) 与走廊 [50,250) 重叠）
        MutationResponse o1 = corridorService.createOccupancy(
                occupancyReq("o1", "r1", now - 1000, now + 3_600_000, 100, 200, true));
        assertFalse(o1.replayed());
        OccupancyResult o1d = occupancyData(o1);
        assertEquals("ACTIVE", o1d.status());
        assertEquals("REGULAR", o1d.routeType());
        assertEquals(3_601_000L, o1d.durationMillis());
        // 不相交占用 o2（高度 [300,400) 在走廊之上）
        corridorService.createOccupancy(
                occupancyReq("o2", "r1", now - 1000, now + 3_600_000, 300, 400, true));

        MutationResponse c = corridorService.createCorridor(
                corridorReq("c1", 1, now - 2000, now + 7_200_000, 50, 250));
        CorridorCreateResult cd = corridorData(c);
        assertEquals("ACTIVE", cd.status());
        assertEquals(1, cd.yieldedCount());
        assertEquals(List.of("o1"), cd.yieldedOccupancyIds());
        assertEquals(7_202_000L, cd.durationMillis());
        assertEquals(1L, cd.airspaceVersion());

        // o1 已让渡，让渡记录固化原审查版本与原因
        OccupancyDetailDto o1After = corridorService.getOccupancy("o1");
        assertEquals("YIELDED", o1After.status());
        assertNotNull(o1After.yield());
        assertEquals("c1", o1After.yield().corridorId());
        assertEquals(1L, o1After.yield().corridorVersion());
        assertEquals(rv.reviewId(), o1After.yield().reviewId());
        assertEquals(1, o1After.yield().routeVersion());
        assertEquals(0L, o1After.yield().airspaceVersion());
        assertEquals("EMERGENCY_CORRIDOR_PREEMPTION", o1After.yield().reason());
        // o2 高度不相交，保持 ACTIVE
        assertEquals("ACTIVE", corridorService.getOccupancy("o2").status());

        // 走廊明细：生效中，撤销字段为 null
        CorridorDetailDto detail = corridorService.getCorridor("c1");
        assertEquals("ACTIVE", detail.status());
        assertTrue(detail.effective());
        assertNull(detail.revokedVersion());
        assertNull(detail.revokedAt());
        assertEquals(1L, detail.createdVersion());
        assertEquals("ops-lead", detail.dispatcherId());

        // 让渡历史查询与诊断
        List<YieldRecordDto> yields = corridorService.getCorridorYields("c1");
        assertEquals(1, yields.size());
        assertEquals("o1", yields.get(0).occupancyId());
        assertEquals(0, corridorService.diagnoseCorridorConflicts().violationCount());
    }

    @Test
    @DisplayName("让渡后必须重新审查并显式重新申请，不自动恢复")
    void yieldedOccupancyNotAutoRestoredRequiresReReviewAndReapply() {
        long now = System.currentTimeMillis();
        createRoute("r1", "REGULAR", 0, 10, 100, 10);
        reviewClear("r1", 1);
        corridorService.createOccupancy(
                occupancyReq("o1", "r1", now - 1000, now + 3_600_000, 100, 200, true));
        corridorService.createCorridor(corridorReq("c1", 1, now - 2000, now + 7_200_000, 50, 250));
        assertEquals("YIELDED", corridorService.getOccupancy("o1").status());

        // 走廊创建已推进空域版本：旧审查失效，直接重新申请 → 409 缺少当前 CLEAR 审查
        ApiException noReview = assertThrows(ApiException.class, () ->
                corridorService.createOccupancy(
                        occupancyReq("o2", "r1", now - 1000, now + 3_600_000, 100, 200, true)));
        assertEquals(HttpStatus.CONFLICT, noReview.status());
        assertEquals("NO_CURRENT_CLEAR_REVIEW", noReview.code());

        // 走廊仍有效：常规航线重新审查命中走廊 → 409 并给出走廊标识
        ApiException corridorHit = assertThrows(ApiException.class, () ->
                reviewService.review(new ReviewRequest("r1", 1, globalVersion(),
                        "req-" + rid("review"))));
        assertEquals(HttpStatus.CONFLICT, corridorHit.status());
        assertEquals("CORRIDOR_CONFLICT", corridorHit.code());
        assertEquals("c1", corridorHit.details().get("corridorId"));

        // 撤销走廊 → 版本再推进；已让渡占用不自动恢复
        corridorService.revokeCorridor(new CorridorRevokeRequest("c1", "ops-lead",
                "req-" + rid("revoke")));
        assertEquals("YIELDED", corridorService.getOccupancy("o1").status());

        // 重新审查 → CLEAR，显式重新申请新占用 → 成功；历史保留两条占用
        reviewClear("r1", 1);
        MutationResponse o2 = corridorService.createOccupancy(
                occupancyReq("o2", "r1", now - 1000, now + 3_600_000, 100, 200, true));
        assertEquals("ACTIVE", occupancyData(o2).status());
        List<OccupancyDetailDto> history = corridorService.getRouteOccupancies("r1");
        assertEquals(2, history.size());
        assertEquals("o1", history.get(0).occupancyId());
        assertEquals("YIELDED", history.get(0).status());
        assertEquals("o2", history.get(1).occupancyId());
        assertEquals("ACTIVE", history.get(1).status());
        assertNull(history.get(1).yield());
    }

    // ============================ 422 强制让渡失败 ============================

    @Test
    @DisplayName("不可让渡占用存在时整次 422，事务回滚且不占幂等键")
    void nonYieldableOccupancyBlocksWholeCorridorCreation() {
        long now = System.currentTimeMillis();
        createRoute("r1", "REGULAR", 0, 10, 100, 10);
        reviewClear("r1", 1);
        corridorService.createOccupancy(
                occupancyReq("o1", "r1", now - 1000, now + 3_600_000, 100, 200, false));

        String requestId = "req-corridor-422";
        ApiException ex = assertThrows(ApiException.class, () ->
                corridorService.createCorridor(new CorridorCreateRequest("c1", 40, 5, 60, 15,
                        now - 2000, now + 7_200_000, 50, 250, 1, "ops-lead", null, requestId)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("CORRIDOR_PREEMPTION_BLOCKED", ex.code());
        assertEquals(1, ex.details().get("blockingCount"));
        assertEquals(0, ex.details().get("yieldableCount"));
        assertEquals(List.of("o1"), ex.details().get("blockingOccupancyIds"));

        // 整体回滚：无走廊、无让渡、占用仍 ACTIVE、版本未推进、失败不占键
        ApiException notFound = assertThrows(ApiException.class,
                () -> corridorService.getCorridor("c1"));
        assertEquals(HttpStatus.NOT_FOUND, notFound.status());
        assertEquals("ACTIVE", corridorService.getOccupancy("o1").status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM corridor_yield", Integer.class));
        assertEquals(0L, globalVersion());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                Integer.class, requestId));

        // 同一 requestId 随后可用于合法请求（不冲突、不重放）
        MutationResponse ok = corridorService.createCorridor(new CorridorCreateRequest("c1",
                500, 500, 600, 600, now - 2000, now + 7_200_000, 50, 250, 1,
                "ops-lead", null, requestId));
        assertFalse(ok.replayed());
        assertEquals(0, corridorData(ok).yieldedCount());
    }

    @Test
    @DisplayName("已起飞占用存在时整次 422")
    void departedOccupancyBlocksCorridorCreation() {
        long now = System.currentTimeMillis();
        createRoute("r1", "REGULAR", 0, 10, 100, 10);
        reviewClear("r1", 1);
        corridorService.createOccupancy(
                occupancyReq("o1", "r1", now - 1000, now + 3_600_000, 100, 200, true));
        MutationResponse takeoff = corridorService.registerTakeoff(
                new TakeoffRequest("o1", "req-" + rid("takeoff")));
        assertTrue(objectMapper.convertValue(takeoff.data(), TakeoffResult.class).departed());

        ApiException ex = assertThrows(ApiException.class, () ->
                corridorService.createCorridor(
                        corridorReq("c1", 1, now - 2000, now + 7_200_000, 50, 250)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("CORRIDOR_PREEMPTION_BLOCKED", ex.code());
        assertEquals("ACTIVE", corridorService.getOccupancy("o1").status());
        assertTrue(corridorService.getOccupancy("o1").departed());
    }

    // ============================ 走廊重叠与左闭右开边界 ============================

    @Test
    @DisplayName("同优先级有效走廊时空重叠 409；异优先级、撤销后、相邻边界均允许")
    void corridorOverlapRules() {
        long now = System.currentTimeMillis();
        corridorService.createCorridor(corridorReq("c1", 1, now, now + 3_600_000, 100, 200));

        // 同优先级时空重叠 → 409 并给出冲突走廊标识
        ApiException overlap = assertThrows(ApiException.class, () ->
                corridorService.createCorridor(new CorridorCreateRequest("c2", 50, 8, 70, 20,
                        now + 1000, now + 4_000_000, 150, 250, 1, "ops-lead", null,
                        "req-" + rid("corridor"))));
        assertEquals(HttpStatus.CONFLICT, overlap.status());
        assertEquals("CORRIDOR_OVERLAP", overlap.code());
        assertEquals("c1", overlap.details().get("corridorId"));

        // 不同优先级同时空 → 允许
        MutationResponse otherPriority = corridorService.createCorridor(
                new CorridorCreateRequest("c3", 40, 5, 60, 15, now, now + 3_600_000,
                        100, 200, 2, "ops-lead", null, "req-" + rid("corridor")));
        assertFalse(otherPriority.replayed());

        // 时间相邻（左闭右开）：c4 起点 = c1 终点 → 不重叠
        corridorService.createCorridor(new CorridorCreateRequest("c4", 40, 5, 60, 15,
                now + 3_600_000, now + 7_200_000, 100, 200, 1, "ops-lead", null,
                "req-" + rid("corridor")));
        // 高度相邻（左闭右开）：c5 下限 = c1 上限 → 不重叠
        corridorService.createCorridor(new CorridorCreateRequest("c5", 40, 5, 60, 15,
                now, now + 3_600_000, 200, 300, 1, "ops-lead", null,
                "req-" + rid("corridor")));

        // 撤销后同优先级同时空可重建
        corridorService.revokeCorridor(new CorridorRevokeRequest("c1", "ops-lead",
                "req-" + rid("revoke")));
        MutationResponse recreated = corridorService.createCorridor(
                new CorridorCreateRequest("c6", 40, 5, 60, 15, now, now + 3_600_000,
                        100, 200, 1, "ops-lead", null, "req-" + rid("corridor")));
        assertFalse(recreated.replayed());
    }

    @Test
    @DisplayName("左闭右开边界：时间或高度仅相邻的占用不被让渡")
    void halfOpenBoundariesDoNotTriggerYield() {
        long now = System.currentTimeMillis();
        createRoute("r1", "REGULAR", 0, 10, 100, 10);
        reviewClear("r1", 1);
        // 高度相邻：占用 [200,300) 与走廊 [100,200) 仅端点相接
        corridorService.createOccupancy(
                occupancyReq("oAlt", "r1", now, now + 3_600_000, 200, 300, true));
        // 时间相邻：占用起点 = 走廊终点
        corridorService.createOccupancy(
                occupancyReq("oTime", "r1", now + 7_200_000, now + 8_200_000, 100, 200, true));

        MutationResponse c = corridorService.createCorridor(
                corridorReq("c1", 1, now, now + 7_200_000, 100, 200));
        assertEquals(0, corridorData(c).yieldedCount());
        assertEquals("ACTIVE", corridorService.getOccupancy("oAlt").status());
        assertEquals("ACTIVE", corridorService.getOccupancy("oTime").status());
    }

    // ============================ 审查与占用的走廊命中 ============================

    @Test
    @DisplayName("常规航线审查命中生效走廊 409；未来时段走廊不影响当前审查")
    void regularReviewHitsOnlyEffectiveCorridor() {
        long now = System.currentTimeMillis();
        // 当前生效走廊
        corridorService.createCorridor(corridorReq("c1", 1, now - 60_000, now + 3_600_000, 0, 1000));
        // 未来生效走廊（不同区域 y 55）
        corridorService.createCorridor(new CorridorCreateRequest("c2", 40, 50, 60, 60,
                now + 100_000_000L, now + 100_003_600_000L, 0, 1000, 1, "ops-lead", null,
                "req-" + rid("corridor")));

        createRoute("r1", "REGULAR", 0, 10, 100, 10);
        ApiException hit = assertThrows(ApiException.class, () ->
                reviewService.review(new ReviewRequest("r1", 1, globalVersion(),
                        "req-" + rid("review"))));
        assertEquals(HttpStatus.CONFLICT, hit.status());
        assertEquals("CORRIDOR_CONFLICT", hit.code());
        assertEquals("c1", hit.details().get("corridorId"));

        // 只穿越未来走廊区域的航线：当前审查不受影响
        createRoute("r2", "REGULAR", 0, 55, 100, 55);
        reviewClear("r2", 1);
    }

    @Test
    @DisplayName("应急航线不受走廊限制，但仍须通过既有禁飞审查")
    void emergencyRouteSkipsCorridorButStillChecksZones() {
        long now = System.currentTimeMillis();
        corridorService.createCorridor(corridorReq("c1", 1, now - 60_000, now + 3_600_000, 0, 1000));

        // 应急航线穿越走廊区域：审查不受走廊影响
        createRoute("er", "EMERGENCY", 0, 10, 100, 10);
        reviewClear("er", 1);

        // 禁飞区仍对应急航线生效
        reviewService.createZone(new ZoneCreateRequest("z1", 40, 5, 60, 15,
                "req-" + rid("zone")));
        ReviewResultDto blocked = review("er", 1);
        assertEquals("BLOCKED", blocked.conclusion());
        assertEquals(List.of("z1"), blocked.hitZoneIds());
    }

    @Test
    @DisplayName("常规占用命中有效走廊 409；应急占用可在走廊内；审查版本必须当前")
    void regularOccupancyHitsCorridorEmergencyAllowed() {
        long now = System.currentTimeMillis();
        // 未来生效走廊（当前不影响审查）
        corridorService.createCorridor(new CorridorCreateRequest("c1", 40, 5, 60, 15,
                now + 3_600_000, now + 7_200_000, 100, 200, 1, "ops-lead", null,
                "req-" + rid("corridor")));

        createRoute("r1", "REGULAR", 0, 10, 100, 10);
        reviewClear("r1", 1);
        // 占用窗口与走廊重叠 → 409 并给出走廊标识
        ApiException hit = assertThrows(ApiException.class, () ->
                corridorService.createOccupancy(occupancyReq("o1", "r1",
                        now + 3_700_000, now + 3_800_000, 100, 200, true)));
        assertEquals(HttpStatus.CONFLICT, hit.status());
        assertEquals("CORRIDOR_CONFLICT", hit.code());
        assertEquals("c1", hit.details().get("corridorId"));
        // 占用窗口在走廊之前 → 允许
        MutationResponse before = corridorService.createOccupancy(
                occupancyReq("o2", "r1", now, now + 3_000_000, 100, 200, true));
        assertEquals("ACTIVE", occupancyData(before).status());

        // 应急航线占用走廊时空 → 允许
        createRoute("er", "EMERGENCY", 0, 10, 100, 10);
        reviewClear("er", 1);
        MutationResponse emergency = corridorService.createOccupancy(
                occupancyReq("o3", "er", now + 3_700_000, now + 3_800_000, 100, 200, true));
        assertEquals("EMERGENCY", occupancyData(emergency).routeType());
        assertEquals("ACTIVE", occupancyData(emergency).status());
        // 应急占用不被走廊让渡：再建同区域更高优先级走廊，o3 保持 ACTIVE
        corridorService.createCorridor(new CorridorCreateRequest("c2", 40, 5, 60, 15,
                now + 3_600_000, now + 7_200_000, 100, 200, 2, "ops-lead", null,
                "req-" + rid("corridor")));
        assertEquals("ACTIVE", corridorService.getOccupancy("o3").status());
    }

    // ============================ 起飞登记 ============================

    @Test
    @DisplayName("起飞登记：ACTIVE 可登记；重复、已让渡、不存在分别 409/409/404")
    void takeoffRegistrationRules() {
        long now = System.currentTimeMillis();
        createRoute("r1", "REGULAR", 0, 10, 100, 10);
        reviewClear("r1", 1);
        corridorService.createOccupancy(
                occupancyReq("o1", "r1", now, now + 3_600_000, 100, 200, true));

        MutationResponse ok = corridorService.registerTakeoff(
                new TakeoffRequest("o1", "req-" + rid("takeoff")));
        TakeoffResult result = objectMapper.convertValue(ok.data(), TakeoffResult.class);
        assertTrue(result.departed());
        assertTrue(result.departedAt() > 0);
        OccupancyDetailDto after = corridorService.getOccupancy("o1");
        assertTrue(after.departed());
        assertNotNull(after.departedAt());

        ApiException twice = assertThrows(ApiException.class, () ->
                corridorService.registerTakeoff(new TakeoffRequest("o1", "req-" + rid("takeoff"))));
        assertEquals(HttpStatus.CONFLICT, twice.status());
        assertEquals("OCCUPANCY_ALREADY_DEPARTED", twice.code());

        ApiException missing = assertThrows(ApiException.class, () ->
                corridorService.registerTakeoff(
                        new TakeoffRequest("ghost", "req-" + rid("takeoff"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        // 已让渡占用不能起飞
        corridorService.createOccupancy(
                occupancyReq("o2", "r1", now, now + 3_600_000, 300, 400, true));
        corridorService.createCorridor(new CorridorCreateRequest("c1", 40, 5, 60, 15,
                now - 1000, now + 7_200_000, 250, 450, 1, "ops-lead", null,
                "req-" + rid("corridor")));
        assertEquals("YIELDED", corridorService.getOccupancy("o2").status());
        ApiException yielded = assertThrows(ApiException.class, () ->
                corridorService.registerTakeoff(new TakeoffRequest("o2", "req-" + rid("takeoff"))));
        assertEquals(HttpStatus.CONFLICT, yielded.status());
        assertEquals("OCCUPANCY_NOT_ACTIVE", yielded.code());
    }

    // ============================ 参数校验与授权 ============================

    @Test
    @DisplayName("走廊创建：退化区域/非法时段/非法高度 400，未授权调度员 403，重复 409")
    void corridorValidationAndAuthorization() {
        long now = System.currentTimeMillis();
        ApiException badRect = assertThrows(ApiException.class, () ->
                corridorService.createCorridor(new CorridorCreateRequest("b1", 10, 10, 10, 20,
                        now, now + 1000, 0, 100, 1, "ops-lead", null, "req-" + rid("corridor"))));
        assertEquals(HttpStatus.BAD_REQUEST, badRect.status());
        assertEquals("INVALID_CORRIDOR_RECTANGLE", badRect.code());

        ApiException badWindow = assertThrows(ApiException.class, () ->
                corridorService.createCorridor(new CorridorCreateRequest("b2", 0, 0, 10, 10,
                        now + 1000, now + 1000, 0, 100, 1, "ops-lead", null,
                        "req-" + rid("corridor"))));
        assertEquals(HttpStatus.BAD_REQUEST, badWindow.status());
        assertEquals("INVALID_TIME_WINDOW", badWindow.code());

        ApiException badAltitude = assertThrows(ApiException.class, () ->
                corridorService.createCorridor(new CorridorCreateRequest("b3", 0, 0, 10, 10,
                        now, now + 1000, 200, 200, 1, "ops-lead", null,
                        "req-" + rid("corridor"))));
        assertEquals(HttpStatus.BAD_REQUEST, badAltitude.status());
        assertEquals("INVALID_ALTITUDE_RANGE", badAltitude.code());

        ApiException unauthorized = assertThrows(ApiException.class, () ->
                corridorService.createCorridor(new CorridorCreateRequest("b4", 0, 0, 10, 10,
                        now, now + 1000, 0, 100, 1, "nobody", null, "req-" + rid("corridor"))));
        assertEquals(HttpStatus.FORBIDDEN, unauthorized.status());
        assertEquals("DISPATCHER_NOT_AUTHORIZED", unauthorized.code());

        corridorService.createCorridor(corridorReq("dup", 1, now, now + 1000, 0, 100));
        ApiException duplicate = assertThrows(ApiException.class, () ->
                corridorService.createCorridor(corridorReq("dup", 1, now, now + 1000, 0, 100)));
        assertEquals(HttpStatus.CONFLICT, duplicate.status());
        assertEquals("CORRIDOR_ALREADY_EXISTS", duplicate.code());

        ApiException revokeMissing = assertThrows(ApiException.class, () ->
                corridorService.revokeCorridor(new CorridorRevokeRequest("ghost", "ops-lead",
                        "req-" + rid("revoke"))));
        assertEquals(HttpStatus.NOT_FOUND, revokeMissing.status());

        ApiException revokeUnauthorized = assertThrows(ApiException.class, () ->
                corridorService.revokeCorridor(new CorridorRevokeRequest("dup", "nobody",
                        "req-" + rid("revoke"))));
        assertEquals(HttpStatus.FORBIDDEN, revokeUnauthorized.status());

        corridorService.revokeCorridor(new CorridorRevokeRequest("dup", "ops-lead",
                "req-" + rid("revoke")));
        ApiException revokeTwice = assertThrows(ApiException.class, () ->
                corridorService.revokeCorridor(new CorridorRevokeRequest("dup", "ops-lead",
                        "req-" + rid("revoke"))));
        assertEquals(HttpStatus.CONFLICT, revokeTwice.status());
        assertEquals("CORRIDOR_ALREADY_REVOKED", revokeTwice.code());
    }

    @Test
    @DisplayName("占用创建：无航线 404、版本错位 409、无当前 CLEAR 审查 409、参数 400、重复 409")
    void occupancyCreationFailures() {
        long now = System.currentTimeMillis();
        ApiException noRoute = assertThrows(ApiException.class, () ->
                corridorService.createOccupancy(occupancyReq("x1", "ghost",
                        now, now + 1000, 0, 100, true)));
        assertEquals(HttpStatus.NOT_FOUND, noRoute.status());

        createRoute("r1", "REGULAR", 0, 10, 100, 10);
        // 尚未审查 → 409 NO_CURRENT_CLEAR_REVIEW
        ApiException noReview = assertThrows(ApiException.class, () ->
                corridorService.createOccupancy(occupancyReq("x2", "r1",
                        now, now + 1000, 0, 100, true)));
        assertEquals(HttpStatus.CONFLICT, noReview.status());
        assertEquals("NO_CURRENT_CLEAR_REVIEW", noReview.code());

        // BLOCKED 审查同样不满足
        reviewService.createZone(new ZoneCreateRequest("z1", 40, 5, 60, 15,
                "req-" + rid("zone")));
        ReviewResultDto blocked = review("r1", 1);
        assertEquals("BLOCKED", blocked.conclusion());
        ApiException blockedReview = assertThrows(ApiException.class, () ->
                corridorService.createOccupancy(occupancyReq("x3", "r1",
                        now, now + 1000, 0, 100, true)));
        assertEquals("NO_CURRENT_CLEAR_REVIEW", blockedReview.code());

        // 航线版本错位 → 409 VERSION_CONFLICT
        ApiException badVersion = assertThrows(ApiException.class, () ->
                corridorService.createOccupancy(new OccupancyCreateRequest("x4", "r1", 9,
                        now, now + 1000, 0, 100, true, "req-" + rid("occ"))));
        assertEquals(HttpStatus.CONFLICT, badVersion.status());
        assertEquals("VERSION_CONFLICT", badVersion.code());

        // 非法时段/高度 → 400
        ApiException badWindow = assertThrows(ApiException.class, () ->
                corridorService.createOccupancy(occupancyReq("x5", "r1",
                        now + 1000, now, 0, 100, true)));
        assertEquals(HttpStatus.BAD_REQUEST, badWindow.status());
        assertEquals("INVALID_TIME_WINDOW", badWindow.code());
        ApiException badAltitude = assertThrows(ApiException.class, () ->
                corridorService.createOccupancy(occupancyReq("x6", "r1",
                        now, now + 1000, 100, 100, true)));
        assertEquals(HttpStatus.BAD_REQUEST, badAltitude.status());
        assertEquals("INVALID_ALTITUDE_RANGE", badAltitude.code());

        // 合法占用后重复占用标识 → 409
        reviewService.revokeZone(new com.example.starter.api.dto.ZoneRevokeRequest("z1",
                "req-" + rid("revoke")));
        reviewClear("r1", 1);
        corridorService.createOccupancy(occupancyReq("x7", "r1", now, now + 1000, 0, 100, true));
        ApiException duplicate = assertThrows(ApiException.class, () ->
                corridorService.createOccupancy(occupancyReq("x7", "r1",
                        now, now + 2000, 0, 100, true)));
        assertEquals(HttpStatus.CONFLICT, duplicate.status());
        assertEquals("OCCUPANCY_ALREADY_EXISTS", duplicate.code());
    }

    // ============================ 幂等 ============================

    @Test
    @DisplayName("幂等：同键同参重放首次结果，异参 409，重放不重复让渡")
    void corridorIdempotency() {
        long now = System.currentTimeMillis();
        createRoute("r1", "REGULAR", 0, 10, 100, 10);
        reviewClear("r1", 1);
        corridorService.createOccupancy(
                occupancyReq("o1", "r1", now - 1000, now + 3_600_000, 100, 200, true));

        String requestId = "idem-corridor-1";
        CorridorCreateRequest req = new CorridorCreateRequest("c1", 40, 5, 60, 15,
                now - 2000, now + 7_200_000, 50, 250, 1, "ops-lead", null, requestId);
        MutationResponse first = corridorService.createCorridor(req);
        assertFalse(first.replayed());
        assertEquals(1, corridorData(first).yieldedCount());

        // 同键同参重放：不再次让渡、不推进版本、结果一致
        long versionAfterFirst = globalVersion();
        MutationResponse replay = corridorService.createCorridor(req);
        assertTrue(replay.replayed());
        assertEquals(1, corridorData(replay).yieldedCount());
        assertEquals(List.of("o1"), corridorData(replay).yieldedOccupancyIds());
        assertEquals(versionAfterFirst, globalVersion());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM emergency_corridor", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM corridor_yield", Integer.class));

        // 同键异参 → 409
        ApiException mismatch = assertThrows(ApiException.class, () ->
                corridorService.createCorridor(new CorridorCreateRequest("c1", 41, 5, 60, 15,
                        now - 2000, now + 7_200_000, 50, 250, 1, "ops-lead", null, requestId)));
        assertEquals(HttpStatus.CONFLICT, mismatch.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", mismatch.code());

        // 占用创建幂等重放
        String occKey = "idem-occ-1";
        createRoute("r2", "REGULAR", 0, 55, 100, 55);
        reviewClear("r2", 1);
        OccupancyCreateRequest occReq = new OccupancyCreateRequest("o9", "r2", 1,
                now, now + 1000, 0, 100, true, occKey);
        MutationResponse occFirst = corridorService.createOccupancy(occReq);
        MutationResponse occReplay = corridorService.createOccupancy(occReq);
        assertFalse(occFirst.replayed());
        assertTrue(occReplay.replayed());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM occupancy WHERE occupancy_id = 'o9'", Integer.class));
    }

    // ============================ 历史与诊断查询 ============================

    @Test
    @DisplayName("历史不可变：撤销后让渡记录与占用状态保持，查询 null 语义稳定")
    void historyImmutableAndNullSemantics() {
        long now = System.currentTimeMillis();
        createRoute("r1", "REGULAR", 0, 10, 100, 10);
        reviewClear("r1", 1);
        corridorService.createOccupancy(
                occupancyReq("o1", "r1", now - 1000, now + 3_600_000, 100, 200, true));
        corridorService.createOccupancy(
                occupancyReq("o2", "r1", now - 1000, now + 3_600_000, 120, 180, true));
        corridorService.createCorridor(
                corridorReq("c1", 1, now - 2000, now + 7_200_000, 50, 250));

        // 让渡历史按创建时间、yieldId 稳定排序，两条记录均可关联原占用与走廊版本
        List<YieldRecordDto> yields = corridorService.getCorridorYields("c1");
        assertEquals(2, yields.size());
        assertEquals(List.of("o1", "o2"),
                yields.stream().map(YieldRecordDto::occupancyId).sorted().toList());
        for (YieldRecordDto y : yields) {
            assertEquals("c1", y.corridorId());
            assertEquals(1L, y.corridorVersion());
            assertEquals(0L, y.airspaceVersion());
            assertTrue(y.createdAt() > 0);
        }

        // 撤销走廊：只影响后续占用，让渡历史与占用状态不改写
        corridorService.revokeCorridor(new CorridorRevokeRequest("c1", "ops-lead",
                "req-" + rid("revoke")));
        CorridorDetailDto revoked = corridorService.getCorridor("c1");
        assertEquals("REVOKED", revoked.status());
        assertFalse(revoked.effective());
        assertEquals(2L, revoked.revokedVersion());
        assertNotNull(revoked.revokedAt());
        assertEquals(1L, revoked.createdVersion());
        assertEquals("YIELDED", corridorService.getOccupancy("o1").status());
        assertEquals(2, corridorService.getCorridorYields("c1").size());

        // 自然到期：时间窗已过的走廊不再生效（状态仍 ACTIVE）
        corridorService.createCorridor(new CorridorCreateRequest("c2", 500, 500, 600, 600,
                now - 10_000, now - 5_000, 0, 100, 1, "ops-lead", null,
                "req-" + rid("corridor")));
        CorridorDetailDto expired = corridorService.getCorridor("c2");
        assertEquals("ACTIVE", expired.status());
        assertFalse(expired.effective());

        // 查询 404 与诊断
        assertThrows(ApiException.class, () -> corridorService.getCorridor("ghost"));
        assertThrows(ApiException.class, () -> corridorService.getCorridorYields("ghost"));
        assertThrows(ApiException.class, () -> corridorService.getOccupancy("ghost"));
        assertThrows(ApiException.class, () -> corridorService.getRouteOccupancies("ghost"));
        assertEquals(0, corridorService.diagnoseCorridorConflicts().violationCount());
        assertTrue(corridorService.diagnoseCorridorConflicts().violations().isEmpty());
    }

    // ============================ 并发 ============================

    @Test
    @DisplayName("并发：走廊创建与常规占用按提交顺序裁决，不产生未让渡冲突")
    void concurrentCorridorCreateAndOccupancyCreate() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                String suffix = "-" + i;
                int y = 10 + i * 100;
                createRoute("r" + suffix, "REGULAR", 0, y, 100, y);
                reviewClear("r" + suffix, 1);
                long now = System.currentTimeMillis();
                String corridorId = "cc" + suffix;
                String occupancyId = "oc" + suffix;
                CyclicBarrier barrier = new CyclicBarrier(2);

                Future<Object> corridorFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return corridorService.createCorridor(new CorridorCreateRequest(
                                corridorId, 40, y - 5, 60, y + 5, now - 1000, now + 7_200_000,
                                50, 250, 1, "ops-lead", null, "req-corridor" + suffix));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> occupancyFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return corridorService.createOccupancy(new OccupancyCreateRequest(
                                occupancyId, "r" + suffix, 1, now - 500, now + 3_600_000,
                                100, 200, true, "req-occ" + suffix));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object corridorResult = corridorFuture.get(15, TimeUnit.SECONDS);
                Object occupancyResult = occupancyFuture.get(15, TimeUnit.SECONDS);
                // 走廊创建总是成功（占用可让渡）
                assertTrue(corridorResult instanceof MutationResponse,
                        "走廊创建必须成功: " + corridorResult);
                if (occupancyResult instanceof MutationResponse) {
                    // 占用先提交：随后被走廊同事务让渡
                    assertEquals("YIELDED",
                            corridorService.getOccupancy(occupancyId).status());
                } else {
                    // 走廊先提交：版本推进使旧审查失效，占用 409 且不存在
                    ApiException ex = (ApiException) occupancyResult;
                    assertEquals(HttpStatus.CONFLICT, ex.status());
                    assertThrows(ApiException.class,
                            () -> corridorService.getOccupancy(occupancyId));
                }
                // 不变量：任何时刻不存在“有效走廊 + 未让渡常规占用”
                assertEquals(0, corridorService.diagnoseCorridorConflicts().violationCount());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发：走廊创建与起飞登记互斥，恰一方成功")
    void concurrentCorridorCreateAndTakeoff() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                String suffix = "-" + i;
                int y = 10 + i * 100;
                createRoute("r" + suffix, "REGULAR", 0, y, 100, y);
                reviewClear("r" + suffix, 1);
                long now = System.currentTimeMillis();
                String occupancyId = "tk" + suffix;
                corridorService.createOccupancy(new OccupancyCreateRequest(
                        occupancyId, "r" + suffix, 1, now - 500, now + 3_600_000,
                        100, 200, true, "req-occ" + suffix));
                CyclicBarrier barrier = new CyclicBarrier(2);

                Future<Object> corridorFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return corridorService.createCorridor(new CorridorCreateRequest(
                                "tc" + suffix, 40, y - 5, 60, y + 5, now - 1000, now + 7_200_000,
                                50, 250, 1, "ops-lead", null, "req-corridor" + suffix));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> takeoffFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return corridorService.registerTakeoff(
                                new TakeoffRequest(occupancyId, "req-takeoff" + suffix));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object corridorResult = corridorFuture.get(15, TimeUnit.SECONDS);
                Object takeoffResult = takeoffFuture.get(15, TimeUnit.SECONDS);
                boolean corridorOk = corridorResult instanceof MutationResponse;
                boolean takeoffOk = takeoffResult instanceof MutationResponse;
                // 起飞先提交 → 走廊 422；走廊先提交 → 占用已让渡，起飞 409。恰一方成功。
                assertTrue(corridorOk ^ takeoffOk,
                        "恰一方成功: corridor=" + corridorResult + ", takeoff=" + takeoffResult);
                if (!corridorOk) {
                    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
                            ((ApiException) corridorResult).status());
                    assertTrue(corridorService.getOccupancy(occupancyId).departed());
                    assertEquals("ACTIVE", corridorService.getOccupancy(occupancyId).status());
                } else {
                    assertEquals("YIELDED", corridorService.getOccupancy(occupancyId).status());
                    assertFalse(corridorService.getOccupancy(occupancyId).departed());
                }
                assertEquals(0, corridorService.diagnoseCorridorConflicts().violationCount());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发：同 requestId 走廊创建仅一次生效，其余重放")
    void concurrentSameRequestIdCorridorCreate() throws Exception {
        long now = System.currentTimeMillis();
        String requestId = "concurrent-corridor-key";
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return corridorService.createCorridor(new CorridorCreateRequest(
                            "ck", 40, 5, 60, 15, now, now + 3_600_000, 100, 200, 1,
                            "ops-lead", null, requestId));
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
            assertEquals(1, firstCount, "仅一次请求真正执行业务");
            assertEquals(n - 1, replayCount);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM emergency_corridor", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                    Integer.class, requestId));
        } finally {
            pool.shutdownNow();
        }
    }
}
