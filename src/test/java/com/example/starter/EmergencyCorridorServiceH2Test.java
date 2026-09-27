package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.CorridorCreateRequest;
import com.example.starter.api.dto.CorridorDetail;
import com.example.starter.api.dto.CorridorDiagnostics;
import com.example.starter.api.dto.CorridorResult;
import com.example.starter.api.dto.CorridorRevokeRequest;
import com.example.starter.api.dto.DepartureRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupationCreateRequest;
import com.example.starter.api.dto.OccupationDetail;
import com.example.starter.api.dto.OccupationHistoryDto;
import com.example.starter.api.dto.OccupationResult;
import com.example.starter.api.dto.ReapplyRequest;
import com.example.starter.api.dto.ReapplyResult;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.YieldRecordDto;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.service.EmergencyCorridorService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空域应急走廊与占用裁决的 H2 数据库测试（MODE=MySQL）。
 * 覆盖主流程、失败分支（400/404/409/422）、让渡与重新申请状态机、
 * 幂等边界与真实并发互斥。
 */
@SpringBootTest
class EmergencyCorridorServiceH2Test {

    /** 固定时段基准（UTC epoch 毫秒），测试窗口均基于此偏移。 */
    private static final long W0 = 1_700_000_000_000L;

    @Autowired
    private EmergencyCorridorService corridorService;
    @Autowired
    private com.example.starter.service.AirspaceReviewService reviewService;
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
        jdbc.update("DELETE FROM occupation_reapply");
        jdbc.update("DELETE FROM corridor_yield");
        jdbc.update("DELETE FROM occupation");
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

    /** 创建横穿走廊区域 (40,5)-(60,15) 的航线。 */
    private void createCrossingRoute(String routeId) {
        reviewService.createRoute(
                new RouteCreateRequest(routeId, pts(0, 10, 100, 10), "req-" + rid("route")));
    }

    private CorridorCreateRequest corridorReq(String corridorId, int priority,
                                              long start, long end, int altMin, int altMax) {
        return new CorridorCreateRequest("req-" + rid("corridor"), corridorId,
                40, 5, 60, 15, start, end, altMin, altMax, priority, "dispatcher-1");
    }

    private OccupationCreateRequest occupationReq(String occupationId, String routeId, String kind,
                                                  long start, long end, int altMin, int altMax,
                                                  boolean yieldable) {
        return new OccupationCreateRequest("req-" + rid("occ"), occupationId, routeId, 1, kind,
                start, end, altMin, altMax, yieldable);
    }

    private CorridorResult corridorData(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), CorridorResult.class);
    }

    private OccupationResult occupationData(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), OccupationResult.class);
    }

    // ============================ 主流程 ============================

    @Test
    void corridorCreateYieldsIntersectingRegularOccupationsInSameTransaction() {
        createCrossingRoute("r1");
        // occ1：与走廊时空相交的可让渡常规占用
        MutationResponse occ1 = corridorService.createOccupation(
                occupationReq("occ1", "r1", "REGULAR", W0, W0 + 1000, 0, 100, true));
        assertEquals("ACTIVE", occupationData(occ1).status());
        String occ1ReviewId = occupationData(occ1).reviewId();
        // occ2：几何与时段相交但高度不相交（[100,200) vs [0,100)），不应被让渡
        corridorService.createOccupation(
                occupationReq("occ2", "r1", "REGULAR", W0, W0 + 1000, 100, 200, true));

        MutationResponse created = corridorService.createCorridor(
                corridorReq("c1", 1, W0, W0 + 1000, 0, 100));
        CorridorResult result = corridorData(created);
        assertFalse(created.replayed());
        assertEquals("ACTIVE", result.status());
        assertEquals(1, result.version());
        assertEquals(1, result.yieldedCount());
        assertEquals(List.of("occ1"), result.yieldedOccupationIds());

        // occ1 已让渡，让渡历史固化原审查版本、走廊版本与原因
        OccupationDetail detail = corridorService.getOccupation("occ1");
        assertEquals("YIELDED", detail.status());
        assertNotNull(detail.yield());
        assertEquals("c1", detail.yield().corridorId());
        assertEquals(1, detail.yield().corridorVersion());
        assertEquals(occ1ReviewId, detail.yield().reviewId());
        assertEquals(1, detail.yield().routeVersion());
        assertTrue(detail.yield().reason().contains("c1"));
        // occ2 未受影响
        assertEquals("ACTIVE", corridorService.getOccupation("occ2").status());

        // 走廊明细与让渡历史查询
        CorridorDetail corridor = corridorService.getCorridor("c1");
        assertEquals("ACTIVE", corridor.status());
        assertEquals(1, corridor.version());
        assertNull(corridor.revokedAt());
        List<YieldRecordDto> yields = corridorService.getCorridorYields("c1");
        assertEquals(1, yields.size());
        assertEquals("occ1", yields.get(0).occupationId());
    }

    @Test
    void regularOccupationHittingCorridorIs409ButEmergencyAllowed() {
        createCrossingRoute("r1");
        corridorService.createCorridor(corridorReq("c1", 1, W0, W0 + 1000, 0, 100));

        // 常规占用命中有效走廊 → 409 并给出走廊标识
        ApiException conflict = assertThrows(ApiException.class, () -> corridorService.createOccupation(
                occupationReq("occ-r", "r1", "REGULAR", W0, W0 + 1000, 0, 100, true)));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals("CORRIDOR_CONFLICT", conflict.code());
        assertEquals(List.of("c1"), conflict.details().get("corridorIds"));

        // 应急占用可使用走廊 → 201
        MutationResponse emergency = corridorService.createOccupation(
                occupationReq("occ-e", "r1", "EMERGENCY", W0, W0 + 1000, 0, 100, true));
        assertEquals("EMERGENCY", occupationData(emergency).kind());
        assertEquals("ACTIVE", occupationData(emergency).status());

        // 常规占用在走廊窗外（左闭右开：起点等于终点不重叠）→ 允许
        MutationResponse outside = corridorService.createOccupation(
                occupationReq("occ-out", "r1", "REGULAR", W0 + 1000, W0 + 2000, 0, 100, true));
        assertEquals("ACTIVE", occupationData(outside).status());
    }

    @Test
    void reviewEndpointRejectsRouteHittingCurrentlyEffectiveCorridor() {
        createCrossingRoute("r1");
        long now = System.currentTimeMillis();
        corridorService.createCorridor(new CorridorCreateRequest("req-" + rid("corridor"), "c-now",
                40, 5, 60, 15, now - 1000, now + 60_000, 0, 100, 1, "dispatcher-1"));

        // 新建审查命中生效中的走廊 → 409 并给出走廊标识
        ApiException conflict = assertThrows(ApiException.class, () -> reviewService.review(
                new ReviewRequest("r1", 1, 0L, "req-" + rid("review"))));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals("CORRIDOR_CONFLICT", conflict.code());
        assertEquals(List.of("c-now"), conflict.details().get("corridorIds"));

        // 撤销走廊后审查恢复可用
        corridorService.revokeCorridor(new CorridorRevokeRequest("req-" + rid("revoke"), "c-now"));
        MutationResponse review = reviewService.review(
                new ReviewRequest("r1", 1, 0L, "req-" + rid("review")));
        assertEquals("CLEAR", objectMapper.convertValue(review.data(),
                com.example.starter.api.dto.ReviewResultDto.class).conclusion());
    }

    @Test
    void emergencyOccupationStillBlockedByNoFlyZone() {
        createCrossingRoute("r1");
        reviewService.createZone(new ZoneCreateRequest("z1", 40, 5, 60, 15,
                "req-" + rid("zone")));
        // 应急占用不能以走廊/应急身份绕过禁飞审查
        ApiException blocked = assertThrows(ApiException.class, () -> corridorService.createOccupation(
                occupationReq("occ-e", "r1", "EMERGENCY", W0, W0 + 1000, 0, 100, true)));
        assertEquals(HttpStatus.CONFLICT, blocked.status());
        assertEquals("NO_FLY_BLOCKED", blocked.code());
        assertEquals(List.of("z1"), blocked.details().get("hitZoneIds"));
        // 常规占用同样被禁飞区拒绝
        ApiException regular = assertThrows(ApiException.class, () -> corridorService.createOccupation(
                occupationReq("occ-r", "r1", "REGULAR", W0, W0 + 1000, 0, 100, true)));
        assertEquals("NO_FLY_BLOCKED", regular.code());
    }

    // ============================ 失败分支 ============================

    @Test
    void invalidCorridorParamsRejectedWithActualValues() {
        ApiException rect = assertThrows(ApiException.class, () -> corridorService.createCorridor(
                new CorridorCreateRequest("req-" + rid("corridor"), "bad1",
                        60, 5, 60, 15, W0, W0 + 1000, 0, 100, 1, "op")));
        assertEquals(HttpStatus.BAD_REQUEST, rect.status());
        assertEquals("INVALID_CORRIDOR_RECTANGLE", rect.code());

        ApiException window = assertThrows(ApiException.class, () -> corridorService.createCorridor(
                corridorReq("bad2", 1, W0 + 1000, W0 + 1000, 0, 100)));
        assertEquals(HttpStatus.BAD_REQUEST, window.status());
        assertEquals("INVALID_TIME_WINDOW", window.code());
        assertTrue(window.getMessage().contains("startUtc=" + (W0 + 1000)));

        ApiException alt = assertThrows(ApiException.class, () -> corridorService.createCorridor(
                corridorReq("bad3", 1, W0, W0 + 1000, 100, 100)));
        assertEquals(HttpStatus.BAD_REQUEST, alt.status());
        assertEquals("INVALID_ALTITUDE_RANGE", alt.code());
    }

    @Test
    void samePriorityCorridorOverlapRejectedButBoundaryAndDifferentPriorityAllowed() {
        corridorService.createCorridor(corridorReq("c1", 1, W0, W0 + 1000, 0, 100));

        // 同优先级时空重叠 → 409 并给出冲突走廊标识
        ApiException overlap = assertThrows(ApiException.class, () -> corridorService.createCorridor(
                corridorReq("c2", 1, W0 + 500, W0 + 1500, 0, 100)));
        assertEquals(HttpStatus.CONFLICT, overlap.status());
        assertEquals("CORRIDOR_OVERLAP", overlap.code());
        assertEquals("c1", overlap.details().get("conflictCorridorId"));

        // 不同优先级允许重叠
        MutationResponse other = corridorService.createCorridor(
                corridorReq("c3", 2, W0 + 500, W0 + 1500, 0, 100));
        assertFalse(other.replayed());

        // 左闭右开：时段首尾相接不算重叠
        corridorService.createCorridor(corridorReq("c4", 1, W0 + 1000, W0 + 2000, 0, 100));
        // 高度首尾相接不算重叠
        corridorService.createCorridor(corridorReq("c5", 1, W0, W0 + 1000, 100, 200));
        // 撤销后不再参与重叠判定
        corridorService.revokeCorridor(new CorridorRevokeRequest("req-" + rid("revoke"), "c1"));
        corridorService.createCorridor(corridorReq("c6", 1, W0, W0 + 1000, 0, 100));
    }

    @Test
    void corridorCreateWithDepartedOccupationFails422WithoutSideEffects() {
        createCrossingRoute("r1");
        corridorService.createOccupation(
                occupationReq("occ1", "r1", "REGULAR", W0, W0 + 1000, 0, 100, true));
        corridorService.depart(new DepartureRequest("req-" + rid("depart"), "occ1"));

        String requestId = "req-corridor-422-departed";
        ApiException ex = assertThrows(ApiException.class, () -> corridorService.createCorridor(
                new CorridorCreateRequest(requestId, "c1", 40, 5, 60, 15,
                        W0, W0 + 1000, 0, 100, 1, "op")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("OCCUPATION_NOT_YIELDABLE", ex.code());
        assertEquals(1, ex.details().get("departedCount"));
        assertEquals(0, ex.details().get("nonYieldableCount"));
        assertEquals(List.of("occ1"), ex.details().get("departedOccupationIds"));

        // 查询不到半成品：走廊不存在、占用未被改写、无让渡记录、失败不占幂等键
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM emergency_corridor", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM corridor_yield", Integer.class));
        assertEquals("DEPARTED", corridorService.getOccupation("occ1").status());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?", Integer.class, requestId));
    }

    @Test
    void corridorCreateWithNonYieldableOccupationFails422WithoutSideEffects() {
        createCrossingRoute("r1");
        corridorService.createOccupation(
                occupationReq("occ1", "r1", "REGULAR", W0, W0 + 1000, 0, 100, false));

        ApiException ex = assertThrows(ApiException.class, () -> corridorService.createCorridor(
                corridorReq("c1", 1, W0, W0 + 1000, 0, 100)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals(1, ex.details().get("nonYieldableCount"));
        assertEquals(List.of("occ1"), ex.details().get("nonYieldableOccupationIds"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM emergency_corridor", Integer.class));
        assertEquals("ACTIVE", corridorService.getOccupation("occ1").status());
    }

    @Test
    void occupationVersionAndExistenceChecks() {
        createCrossingRoute("r1");
        // 航线版本不匹配 → 409，报实际值与提交值
        ApiException version = assertThrows(ApiException.class, () -> corridorService.createOccupation(
                new OccupationCreateRequest("req-" + rid("occ"), "occ1", "r1", 9, "REGULAR",
                        W0, W0 + 1000, 0, 100, true)));
        assertEquals(HttpStatus.CONFLICT, version.status());
        assertEquals("VERSION_CONFLICT", version.code());
        assertTrue(version.getMessage().contains("submitted=9"));
        assertTrue(version.getMessage().contains("current=1"));

        // 航线不存在 → 404
        ApiException missing = assertThrows(ApiException.class, () -> corridorService.createOccupation(
                occupationReq("occ2", "ghost", "REGULAR", W0, W0 + 1000, 0, 100, true)));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        // 占用标识重复 → 409
        corridorService.createOccupation(
                occupationReq("occ1", "r1", "REGULAR", W0, W0 + 1000, 0, 100, true));
        ApiException dup = assertThrows(ApiException.class, () -> corridorService.createOccupation(
                occupationReq("occ1", "r1", "REGULAR", W0, W0 + 1000, 0, 100, true)));
        assertEquals(HttpStatus.CONFLICT, dup.status());
        assertEquals("OCCUPATION_ALREADY_EXISTS", dup.code());
    }

    @Test
    void departureOnlyFromActiveAndCorridorRejectsDeparted() {
        createCrossingRoute("r1");
        corridorService.createOccupation(
                occupationReq("occ1", "r1", "REGULAR", W0, W0 + 1000, 0, 100, true));
        // 起飞登记成功
        MutationResponse departed = corridorService.depart(
                new DepartureRequest("req-" + rid("depart"), "occ1"));
        assertEquals("DEPARTED", occupationData(departed).status());
        assertNotNull(corridorService.getOccupation("occ1").departedAt());
        // 重复起飞 → 409
        ApiException twice = assertThrows(ApiException.class, () -> corridorService.depart(
                new DepartureRequest("req-" + rid("depart"), "occ1")));
        assertEquals(HttpStatus.CONFLICT, twice.status());
        assertEquals("OCCUPATION_NOT_ACTIVE", twice.code());
        // 占用不存在 → 404
        ApiException missing = assertThrows(ApiException.class, () -> corridorService.depart(
                new DepartureRequest("req-" + rid("depart"), "ghost")));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    // ============================ 让渡 / 撤销 / 重新申请 ============================

    @Test
    void yieldedOccupationNotAutoRestoredAndExplicitReapplyWorks() {
        createCrossingRoute("r1");
        corridorService.createOccupation(
                occupationReq("occ1", "r1", "REGULAR", W0, W0 + 1000, 0, 100, true));
        corridorService.createCorridor(corridorReq("c1", 1, W0, W0 + 1000, 0, 100));
        assertEquals("YIELDED", corridorService.getOccupation("occ1").status());

        // 撤销走廊只影响后续占用：已让渡占用不自动恢复
        corridorService.revokeCorridor(new CorridorRevokeRequest("req-" + rid("revoke"), "c1"));
        assertEquals("YIELDED", corridorService.getOccupation("occ1").status());
        CorridorDetail revoked = corridorService.getCorridor("c1");
        assertEquals("REVOKED", revoked.status());
        assertEquals(2, revoked.version());
        assertNotNull(revoked.revokedAt());

        // 显式重新申请：重新审查并生成新占用
        MutationResponse reapply = corridorService.reapply(
                new ReapplyRequest("req-" + rid("reapply"), "occ1", "occ1-b"));
        ReapplyResult result = objectMapper.convertValue(reapply.data(), ReapplyResult.class);
        assertEquals("occ1", result.sourceOccupationId());
        assertEquals("occ1-b", result.newOccupationId());
        assertEquals("ACTIVE", result.status());
        assertNotNull(result.reviewId());

        // 原占用关联新占用；新占用历史记录来源
        OccupationDetail source = corridorService.getOccupation("occ1");
        assertEquals("occ1-b", source.supersededBy());
        OccupationHistoryDto sourceHistory = corridorService.getOccupationHistory("occ1");
        // 三类事件齐全（创建、让渡、被重新申请接替）
        assertEquals(
                java.util.Set.of("OCCUPATION_CREATED", "OCCUPATION_YIELDED", "OCCUPATION_REAPPLIED"),
                java.util.Set.copyOf(sourceHistory.events().stream()
                        .map(e -> e.eventType()).toList()));
        // 排序稳定：按 (occurredAt, eventType) 升序
        List<com.example.starter.api.dto.OccupationHistoryEvent> sorted =
                sourceHistory.events().stream().sorted(java.util.Comparator
                        .comparingLong(com.example.starter.api.dto.OccupationHistoryEvent::occurredAt)
                        .thenComparing(com.example.starter.api.dto.OccupationHistoryEvent::eventType))
                        .toList();
        assertEquals(sorted, sourceHistory.events());
        // 让渡事件关联走廊与原因
        com.example.starter.api.dto.OccupationHistoryEvent yieldedEvent = sourceHistory.events()
                .stream().filter(e -> e.eventType().equals("OCCUPATION_YIELDED")).findFirst().orElseThrow();
        assertEquals("c1", yieldedEvent.corridorId());
        assertEquals(1, yieldedEvent.corridorVersion());
        assertTrue(yieldedEvent.reason().contains("c1"));
        OccupationHistoryDto newHistory = corridorService.getOccupationHistory("occ1-b");
        assertEquals("OCCUPATION_CREATED_BY_REAPPLY", newHistory.events().get(0).eventType());
        assertEquals("occ1", newHistory.events().get(0).relatedOccupationId());

        // 让渡历史不可变：撤销与重新申请后仍保留原走廊版本与原因
        List<YieldRecordDto> yields = corridorService.getCorridorYields("c1");
        assertEquals(1, yields.size());
        assertEquals(1, yields.get(0).corridorVersion());
        assertEquals("occ1", yields.get(0).occupationId());
    }

    @Test
    void reapplyRejectedWhileCorridorStillActiveAndForNonYielded() {
        createCrossingRoute("r1");
        corridorService.createOccupation(
                occupationReq("occ1", "r1", "REGULAR", W0, W0 + 1000, 0, 100, true));
        corridorService.createCorridor(corridorReq("c1", 1, W0, W0 + 1000, 0, 100));

        // 走廊仍有效：重新申请仍命中走廊 → 409
        ApiException conflict = assertThrows(ApiException.class, () -> corridorService.reapply(
                new ReapplyRequest("req-" + rid("reapply"), "occ1", "occ1-b")));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals("CORRIDOR_CONFLICT", conflict.code());

        // 非 YIELDED 占用不能重新申请
        corridorService.createOccupation(
                occupationReq("occ2", "r1", "REGULAR", W0 + 2000, W0 + 3000, 0, 100, true));
        ApiException notYielded = assertThrows(ApiException.class, () -> corridorService.reapply(
                new ReapplyRequest("req-" + rid("reapply"), "occ2", "occ2-b")));
        assertEquals(HttpStatus.CONFLICT, notYielded.status());
        assertEquals("OCCUPATION_NOT_YIELDED", notYielded.code());

        // 原占用不存在 → 404
        ApiException missing = assertThrows(ApiException.class, () -> corridorService.reapply(
                new ReapplyRequest("req-" + rid("reapply"), "ghost", "occ-x")));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    @Test
    void revokedCorridorDoesNotBlockLaterOccupations() {
        createCrossingRoute("r1");
        corridorService.createCorridor(corridorReq("c1", 1, W0, W0 + 1000, 0, 100));
        corridorService.revokeCorridor(new CorridorRevokeRequest("req-" + rid("revoke"), "c1"));
        // 撤销后同时空常规占用允许
        MutationResponse occ = corridorService.createOccupation(
                occupationReq("occ1", "r1", "REGULAR", W0, W0 + 1000, 0, 100, true));
        assertEquals("ACTIVE", occupationData(occ).status());
        // 重复撤销 → 409；撤销不存在走廊 → 404
        ApiException twice = assertThrows(ApiException.class, () -> corridorService.revokeCorridor(
                new CorridorRevokeRequest("req-" + rid("revoke"), "c1")));
        assertEquals(HttpStatus.CONFLICT, twice.status());
        ApiException missing = assertThrows(ApiException.class, () -> corridorService.revokeCorridor(
                new CorridorRevokeRequest("req-" + rid("revoke"), "ghost")));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    // ============================ 诊断与只读查询 ============================

    @Test
    void diagnosticsReportsCorridorsAndOccupationsWithoutChangingState() {
        createCrossingRoute("r1");
        corridorService.createOccupation(
                occupationReq("occ1", "r1", "REGULAR", W0, W0 + 1000, 0, 100, true));
        // occ2 与走廊高度不相交（[100,200) vs [0,100)），不会被让渡
        corridorService.createOccupation(
                occupationReq("occ2", "r1", "REGULAR", W0, W0 + 1000, 100, 200, true));
        corridorService.createCorridor(corridorReq("c1", 2, W0, W0 + 1000, 0, 100));
        // 走廊创建后登记 occ2 起飞
        corridorService.depart(new DepartureRequest("req-" + rid("depart"), "occ2"));

        CorridorDiagnostics diag = corridorService.diagnose("r1", W0, W0 + 1000, 0, 200);
        assertEquals(List.of("c1"), diag.hitCorridorIds());
        // occ1 已被走廊创建让渡，不再是 ACTIVE
        assertEquals(List.of(), diag.activeRegularOccupationIds());
        assertEquals(List.of("occ2"), diag.departedOccupationIds());

        // 只读：诊断前后数据行数不变
        int corridors = jdbc.queryForObject("SELECT COUNT(*) FROM emergency_corridor", Integer.class);
        int occupations = jdbc.queryForObject("SELECT COUNT(*) FROM occupation", Integer.class);
        corridorService.diagnose("r1", W0, W0 + 1000, 0, 100);
        corridorService.getCorridor("c1");
        corridorService.getCorridorYields("c1");
        corridorService.getOccupation("occ1");
        corridorService.getOccupationHistory("occ1");
        assertEquals(corridors,
                jdbc.queryForObject("SELECT COUNT(*) FROM emergency_corridor", Integer.class));
        assertEquals(occupations,
                jdbc.queryForObject("SELECT COUNT(*) FROM occupation", Integer.class));

        // 诊断缺失航线 → 404；非法窗口 → 400
        ApiException missing = assertThrows(ApiException.class, () -> corridorService.diagnose(
                "ghost", W0, W0 + 1000, 0, 100));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
        ApiException badWindow = assertThrows(ApiException.class, () -> corridorService.diagnose(
                "r1", W0 + 1000, W0, 0, 100));
        assertEquals(HttpStatus.BAD_REQUEST, badWindow.status());
    }

    // ============================ 幂等 ============================

    @Test
    void corridorCreateIdempotentReplayAndParamMismatch() {
        createCrossingRoute("r1");
        corridorService.createOccupation(
                occupationReq("occ1", "r1", "REGULAR", W0, W0 + 1000, 0, 100, true));
        CorridorCreateRequest request = new CorridorCreateRequest("req-fixed-corridor", "c1",
                40, 5, 60, 15, W0, W0 + 1000, 0, 100, 1, "op");
        MutationResponse first = corridorService.createCorridor(request);
        assertFalse(first.replayed());
        assertEquals(1, corridorData(first).yieldedCount());

        // 同键同参重放：返回首次结果，不重复让渡、不重复建走廊
        MutationResponse replay = corridorService.createCorridor(request);
        assertTrue(replay.replayed());
        assertEquals(1, corridorData(replay).yieldedCount());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM emergency_corridor", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM corridor_yield", Integer.class));

        // 同键异参 → 409
        ApiException mismatch = assertThrows(ApiException.class, () -> corridorService.createCorridor(
                new CorridorCreateRequest("req-fixed-corridor", "c1",
                        40, 5, 60, 15, W0, W0 + 2000, 0, 100, 1, "op")));
        assertEquals(HttpStatus.CONFLICT, mismatch.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", mismatch.code());
    }

    @Test
    void failedCorridorCreateDoesNotConsumeRequestId() {
        createCrossingRoute("r1");
        corridorService.createOccupation(
                occupationReq("occ1", "r1", "REGULAR", W0, W0 + 1000, 0, 100, false));
        String requestId = "req-retry-after-422";
        CorridorCreateRequest request = new CorridorCreateRequest(requestId, "c1",
                40, 5, 60, 15, W0, W0 + 1000, 0, 100, 1, "op");
        assertThrows(ApiException.class, () -> corridorService.createCorridor(request));

        // 失败不占键：排除阻塞因素后同键同参可成功
        jdbc.update("DELETE FROM occupation WHERE occupation_id = 'occ1'");
        MutationResponse ok = corridorService.createCorridor(request);
        assertFalse(ok.replayed());
        assertEquals("ACTIVE", corridorData(ok).status());
    }

    // ============================ 并发 ============================

    @Test
    void concurrentCorridorCreateAndRegularOccupationAreSerialized() throws Exception {
        int iterations = 8;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < iterations; i++) {
                String routeId = "cr-" + i;
                String corridorId = "cc-" + i;
                String occupationId = "co-" + i;
                createCrossingRoute(routeId);
                CyclicBarrier barrier = new CyclicBarrier(2);
                // 每轮使用不同优先级与独立时间窗，避免与历史轮次走廊相互作用
                int priority = i;
                long w0 = W0 + (long) i * 100_000;

                Future<Object> corridorFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return corridorService.createCorridor(new CorridorCreateRequest(
                                "req-c-" + corridorId, corridorId, 40, 5, 60, 15,
                                w0, w0 + 1000, 0, 100, priority, "op"));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> occupationFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return corridorService.createOccupation(new OccupationCreateRequest(
                                "req-o-" + occupationId, occupationId, routeId, 1, "REGULAR",
                                w0, w0 + 1000, 0, 100, true));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object corridorResult = corridorFuture.get(15, TimeUnit.SECONDS);
                Object occupationResult = occupationFuture.get(15, TimeUnit.SECONDS);
                assertTrue(corridorResult instanceof MutationResponse, "走廊创建必须成功");

                if (occupationResult instanceof MutationResponse) {
                    // 占用先提交：走廊创建必须在同一事务内将其让渡
                    assertEquals("YIELDED", corridorService.getOccupation(occupationId).status());
                    assertEquals(1, corridorData((MutationResponse) corridorResult).yieldedCount());
                } else {
                    // 走廊先提交：常规占用必须 409，且不存在未让渡常规占用
                    ApiException ex = (ApiException) occupationResult;
                    assertEquals(HttpStatus.CONFLICT, ex.status());
                    assertEquals("CORRIDOR_CONFLICT", ex.code());
                    assertEquals(0, jdbc.queryForObject(
                            "SELECT COUNT(*) FROM occupation WHERE occupation_id = ?",
                            Integer.class, occupationId));
                }
                // 不变式：不存在同一时空既有有效走廊又有未让渡 ACTIVE 常规占用
                assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM occupation o JOIN emergency_corridor c "
                                + "ON o.status = 'ACTIVE' AND o.kind = 'REGULAR' AND c.status = 'ACTIVE' "
                                + "AND o.start_utc < c.end_utc AND c.start_utc < o.end_utc "
                                + "AND o.alt_min < c.alt_max AND c.alt_min < o.alt_max "
                                + "WHERE c.corridor_id = ?", Integer.class, corridorId));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDepartureAndCorridorCreateAreAdjudicatedByCommitOrder() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 6; i++) {
                String routeId = "dr-" + i;
                String corridorId = "dc-" + i;
                String occupationId = "do-" + i;
                createCrossingRoute(routeId);
                CyclicBarrier barrier = new CyclicBarrier(2);
                // 每轮使用不同优先级与独立时间窗，避免与历史轮次走廊相互作用
                int priority = i;
                long w0 = W0 + (long) i * 100_000;
                corridorService.createOccupation(new OccupationCreateRequest(
                        "req-o-" + occupationId, occupationId, routeId, 1, "REGULAR",
                        w0, w0 + 1000, 0, 100, true));

                Future<Object> departFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return corridorService.depart(
                                new DepartureRequest("req-d-" + occupationId, occupationId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> corridorFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return corridorService.createCorridor(new CorridorCreateRequest(
                                "req-c-" + corridorId, corridorId, 40, 5, 60, 15,
                                w0, w0 + 1000, 0, 100, priority, "op"));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object departResult = departFuture.get(15, TimeUnit.SECONDS);
                Object corridorResult = corridorFuture.get(15, TimeUnit.SECONDS);
                String status = corridorService.getOccupation(occupationId).status();
                if (departResult instanceof MutationResponse) {
                    // 起飞先提交：走廊创建必须整次 422，占用保持 DEPARTED
                    assertEquals("DEPARTED", status);
                    assertTrue(corridorResult instanceof ApiException);
                    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
                            ((ApiException) corridorResult).status());
                    assertEquals(0, jdbc.queryForObject(
                            "SELECT COUNT(*) FROM emergency_corridor WHERE corridor_id = ?",
                            Integer.class, corridorId));
                } else {
                    // 走廊先提交：占用已让渡，起飞登记必须 409
                    assertEquals("YIELDED", status);
                    assertTrue(corridorResult instanceof MutationResponse);
                    ApiException ex = (ApiException) departResult;
                    assertEquals(HttpStatus.CONFLICT, ex.status());
                    assertEquals("OCCUPATION_NOT_ACTIVE", ex.code());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameRequestIdCorridorCreatePlaysBackOneOutcome() throws Exception {
        String requestId = "req-concurrent-corridor";
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return corridorService.createCorridor(new CorridorCreateRequest(
                            requestId, "ck", 40, 5, 60, 15, W0, W0 + 1000, 0, 100, 1, "op"));
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
            assertEquals(1, firstCount, "仅一次请求真正建走廊");
            assertEquals(n - 1, replayCount);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM emergency_corridor",
                    Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                    Integer.class, requestId));
        } finally {
            pool.shutdownNow();
        }
    }
}
