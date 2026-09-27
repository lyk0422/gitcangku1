package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.CorridorCreateRequest;
import com.example.starter.api.dto.CorridorDetailView;
import com.example.starter.api.dto.CorridorRevokeRequest;
import com.example.starter.api.dto.DepartureRequest;
import com.example.starter.api.dto.EmergencyOccupancyRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyResult;
import com.example.starter.api.dto.OccupancyView;
import com.example.starter.api.dto.ReapplyResultDto;
import com.example.starter.api.dto.RegularOccupancyRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.YieldedReapplyRequest;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
 * 空域应急走廊业务的 H2 数据库测试（MODE=MySQL）：
 * 覆盖走廊建立让渡、422/409/400/404 失败分支、撤销与自然到期语义、显式重新申请、
 * 不可变历史、幂等边界，以及走廊建立与占用登记/起飞登记的真实并发裁决。
 * 时间全部使用可控 UTC 时钟，不靠休眠判定到期。
 */
@SpringBootTest
class EmergencyCorridorServiceH2Test {

    /** 固定基准时刻：2001-09-09T01:46:40Z，所有时间窗围绕它取相对毫秒。 */
    private static final long BASE = 1_000_000_000_000L;
    private static final long T1 = BASE + 1_000L;
    private static final long T2 = BASE + 2_000L;
    private static final long T3 = BASE + 3_000L;

    @Autowired
    private com.example.starter.service.EmergencyCorridorService corridorService;
    @Autowired
    private com.example.starter.service.AirspaceReviewService reviewService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MutableClock clock;

    private final AtomicInteger seq = new AtomicInteger();

    /** 覆盖测试上下文的可控 UTC 时钟。 */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant instant = Instant.ofEpochMilli(BASE);

        void set(long epochMillis) {
            this.instant = Instant.ofEpochMilli(epochMillis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @BeforeEach
    void reset() {
        cleanup();
        clock.set(BASE);
    }

    @AfterEach
    void cleanAfter() {
        cleanup();
        clock.set(BASE);
    }

    private void cleanup() {
        jdbc.update("DELETE FROM reapply_history");
        jdbc.update("DELETE FROM yield_history");
        jdbc.update("DELETE FROM route_occupancy");
        jdbc.update("DELETE FROM corridor_event");
        jdbc.update("DELETE FROM emergency_corridor");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    private String uid(String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    private static List<RoutePointDto> horizontal(int y) {
        return List.of(new RoutePointDto(0, y), new RoutePointDto(100, y));
    }

    private String createRoute(String routeId, int y) {
        reviewService.createRoute(new RouteCreateRequest(
                routeId, horizontal(y), "req-route-" + routeId));
        return routeId;
    }

    private CorridorCreateRequest corridorReq(String corridorId, long start, long end,
                                              int priority, String reason) {
        // 走廊矩形覆盖 y=10 的航线
        return new CorridorCreateRequest(corridorId, 40, 5, 60, 15,
                start, end, 0, 1000, priority, reason, "req-corridor-" + corridorId);
    }

    private OccupancyResult dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), OccupancyResult.class);
    }

    private MutationResponse regular(String routeId, long start, long end, boolean yieldable) {
        String requestId = "req-occ-" + uid(routeId);
        return corridorService.registerRegular(new RegularOccupancyRequest(
                routeId, 1, 0L, start, end, 0, 1000, yieldable, requestId));
    }

    // ============================ 主流程：建立让渡 ============================

    @Test
    void corridorCreationYieldsIntersectingOccupanciesAndFreezesReviewVersion() {
        String routeHit = createRoute("r-hit", 10);
        String routeMiss = createRoute("r-miss", 80);
        MutationResponse hitOcc = regular(routeHit, T1, T2, true);
        MutationResponse missOcc = regular(routeMiss, T1, T2, true);

        MutationResponse created = corridorService.createCorridor(
                corridorReq("c1", T1 + 500, T3, 0, "医疗应急通道"));
        assertFalse(created.replayed());
        var result = objectMapper.convertValue(created.data(),
                com.example.starter.api.dto.CorridorResult.class);
        assertEquals("ACTIVE", result.status());
        assertEquals(1, result.version());
        assertEquals(T3 - (T1 + 500), result.durationMs(), "时长返回实际值");
        assertEquals(List.of(dataOf(hitOcc).occupancyId()), result.yieldedOccupancies());

        // 相交占用已让渡，固化走廊与原因；不相交占用保持 ACTIVE
        OccupancyView yielded = corridorService.getOccupancy(dataOf(hitOcc).occupancyId());
        assertEquals("YIELDED", yielded.status());
        assertEquals("c1", yielded.yieldCorridorId());
        assertEquals("医疗应急通道", yielded.yieldReason());
        assertNotNull(yielded.yieldedMs());
        assertEquals("ACTIVE", corridorService.getOccupancy(dataOf(missOcc).occupancyId()).status());

        // 让渡历史固化原审查版本（航线 1 / 空域 0）与让渡原因
        CorridorDetailView detail = corridorService.getCorridorDetail("c1");
        assertEquals(1, detail.yields().size());
        var yield = detail.yields().get(0);
        assertEquals(dataOf(hitOcc).occupancyId(), yield.occupancyId());
        assertEquals(1, yield.routeVersion());
        assertEquals(0L, yield.airspaceVersion());
        assertEquals("医疗应急通道", yield.reason());
        assertEquals("c1", yield.corridorId());
        assertEquals(1, yield.corridorVersion());

        // 走廊不可变事件：建立一条，字段稳定
        assertEquals(1, detail.events().size());
        assertEquals("CREATED", detail.events().get(0).action());
        assertEquals(1, detail.events().get(0).version());
        assertEquals("req-corridor-c1", detail.events().get(0).requestId());
    }

    @Test
    void emergencyOccupancyCoexistsInsideCorridorAndEmergencyReviewBypassesNoZone() {
        createRoute("er", 10);
        corridorService.createCorridor(corridorReq("ce", T1, T3, 0, "应急"));

        MutationResponse resp = corridorService.registerEmergency(new EmergencyOccupancyRequest(
                "er", 1, 0L, T1 + 100, T2, 0, 1000, "ce", "req-em-1"));
        OccupancyResult occ = dataOf(resp);
        assertEquals("EMERGENCY", occ.kind());
        assertEquals("ACTIVE", occ.status());
        assertEquals("ce", occ.corridorId());
        assertEquals(T2 - (T1 + 100), occ.actualDurationMs());

        // 应急审查记录为 EMERGENCY/CLEAR，可经审查历史查询
        ReviewResultDto review = reviewService.getReview(occ.reviewId());
        assertEquals("EMERGENCY", review.reviewKind());
        assertEquals("CLEAR", review.conclusion());

        // 与应急占用时空重叠的不同优先级新走廊可建立：走廊段允许应急航线，
        // 应急占用不参与让渡；同优先级重叠另行禁止
        MutationResponse second = corridorService.createCorridor(
                new CorridorCreateRequest("ce2", 40, 5, 60, 15,
                        T1 + 100, T2, 0, 1000, 1, "另一应急", "req-corridor-ce2"));
        assertEquals("ACTIVE", objectMapper.convertValue(second.data(),
                com.example.starter.api.dto.CorridorResult.class).status());
    }

    // ============================ 失败分支：422 / 409 / 400 / 404 ============================

    @Test
    void unyieldableOccupancyMakesWholeCreation422WithoutPartialResult() {
        String a = createRoute("r-a", 10);
        String b = createRoute("r-b", 10);
        String occYieldable = dataOf(regular(a, T1, T2, true)).occupancyId();
        String occFixed = dataOf(regular(b, T1, T2, false)).occupancyId();

        ApiException ex = assertThrows(ApiException.class, () -> corridorService.createCorridor(
                corridorReq("cf", T1 + 500, T3, 0, "强推走廊")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("OCCUPANCY_NOT_YIELDABLE", ex.code());
        assertTrue(ex.getMessage().contains(occFixed));

        // 整次回滚：无走廊、无事件、无让渡历史，两个占用都还是 ACTIVE
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM emergency_corridor", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM corridor_event", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM yield_history", Integer.class));
        assertEquals("ACTIVE", corridorService.getOccupancy(occYieldable).status());
        assertEquals("ACTIVE", corridorService.getOccupancy(occFixed).status());

        // 失败不占键：同一 requestId 改用于不相交走廊可成功
        String failedRequestId = "req-corridor-cf";
        MutationResponse retry = corridorService.createCorridor(new CorridorCreateRequest(
                "cf2", -60, -60, -40, -40, T1, T2, 0, 1000, 0, "不相交重试", failedRequestId));
        assertFalse(retry.replayed());
    }

    @Test
    void departedOccupancyMakesCreation422() {
        String route = createRoute("r-dep", 10);
        String occId = dataOf(regular(route, T1, T2, true)).occupancyId();
        corridorService.registerDeparture(new DepartureRequest(occId, "req-depart-1"));

        ApiException ex = assertThrows(ApiException.class, () -> corridorService.createCorridor(
                corridorReq("cd", T1 + 500, T3, 0, "与已起飞相交")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("OCCUPANCY_DEPARTED", ex.code());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM emergency_corridor WHERE corridor_id = 'cd'", Integer.class));
        assertEquals("DEPARTED", corridorService.getOccupancy(occId).status());
    }

    @Test
    void samePriorityOverlapIs409DifferentPriorityAllowed() {
        corridorService.createCorridor(corridorReq("co", T1, T3, 0, "p0"));

        ApiException overlap = assertThrows(ApiException.class, () -> corridorService.createCorridor(
                corridorReq("co2", T1, T3, 0, "p0 同优先级重叠")));
        assertEquals(HttpStatus.CONFLICT, overlap.status());
        assertEquals("CORRIDOR_OVERLAP", overlap.code());
        assertTrue(overlap.getMessage().contains("co"));

        // 时间窗左闭右开：恰在 co 结束时开始，不重叠，可建立
        MutationResponse touching = corridorService.createCorridor(
                new CorridorCreateRequest("co3", 40, 5, 60, 15,
                        T3, T3 + 1000, 0, 1000, 0, "端点相接", "req-corridor-co3"));
        assertFalse(touching.replayed());

        // 不同优先级即便重叠也允许
        MutationResponse higher = corridorService.createCorridor(
                new CorridorCreateRequest("co4", 40, 5, 60, 15,
                        T1, T3, 0, 1000, 9, "高优先级", "req-corridor-co4"));
        assertFalse(higher.replayed());
    }

    @Test
    void invalidCorridorShapeWindowAltitudeAre400AndDuplicateIs409() {
        ApiException badRect = assertThrows(ApiException.class, () -> corridorService.createCorridor(
                new CorridorCreateRequest("bad1", 50, 50, 40, 60,
                        T1, T2, 0, 1000, 0, "r", "req-bad1")));
        assertEquals(HttpStatus.BAD_REQUEST, badRect.status());

        ApiException badTime = assertThrows(ApiException.class, () -> corridorService.createCorridor(
                new CorridorCreateRequest("bad2", 0, 0, 10, 10,
                        T2, T1, 0, 1000, 0, "r", "req-bad2")));
        assertEquals(HttpStatus.BAD_REQUEST, badTime.status());
        assertEquals("INVALID_CORRIDOR_WINDOW", badTime.code());

        ApiException badAlt = assertThrows(ApiException.class, () -> corridorService.createCorridor(
                new CorridorCreateRequest("bad3", 0, 0, 10, 10,
                        T1, T2, 1000, 1000, 0, "r", "req-bad3")));
        assertEquals(HttpStatus.BAD_REQUEST, badAlt.status());
        assertEquals("INVALID_CORRIDOR_ALTITUDE", badAlt.code());

        corridorService.createCorridor(corridorReq("dup", T1, T2, 0, "r"));
        // 同标识换 requestId 重复建立 → 409 资源冲突
        ApiException dup2 = assertThrows(ApiException.class, () -> corridorService.createCorridor(
                new CorridorCreateRequest("dup", 40, 5, 60, 15,
                        T1, T2, 0, 1000, 0, "r", "req-corridor-dup-other")));
        assertEquals(HttpStatus.CONFLICT, dup2.status());
        assertEquals("CORRIDOR_ALREADY_EXISTS", dup2.code());
    }

    @Test
    void regularOccupancyHittingActiveCorridorIs409WithCorridorIdAndLeavesNoData() {
        createRoute("rr", 10);
        corridorService.createCorridor(corridorReq("ch", T1, T3, 0, "r"));

        ApiException hit = assertThrows(ApiException.class, () -> regular("rr", T1, T2, true));
        assertEquals(HttpStatus.CONFLICT, hit.status());
        assertEquals("CORRIDOR_CONFLICT", hit.code());
        assertTrue(hit.getMessage().contains("ch"), "409 必须给出走廊标识");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM route_occupancy", Integer.class));
        // 走廊裁决先于审查落库：失败不产生审查记录
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class));
    }

    @Test
    void halfOpenBoundariesDoNotHitCorridor() {
        createRoute("rb", 10);
        corridorService.createCorridor(corridorReq("cb", T1, T2, 0, "r"));

        // 占用恰在走廊结束时刻开始：时间不相交 → 可登记
        MutationResponse atEnd = regular("rb", T2, T3, true);
        assertEquals("ACTIVE", dataOf(atEnd).status());
        // 高度下界恰为走廊上界：高度不相交 → 可登记
        String requestId = "req-occ-alt-" + uid("rb");
        MutationResponse above = corridorService.registerRegular(new RegularOccupancyRequest(
                "rb", 1, 0L, T1, T2, 1000, 2000, true, requestId));
        assertEquals("ACTIVE", dataOf(above).status());
    }

    @Test
    void windowedReviewIs409WithCorridorIdAndStoresRegularKind() {
        createRoute("rw", 10);
        corridorService.createCorridor(corridorReq("cw", T1, T3, 0, "r"));

        ApiException hit = assertThrows(ApiException.class, () -> reviewService.reviewWithWindow(
                "rw", 1, 0L, new com.example.starter.service.SpaceTimeWindow(T1, T2, 0, 1000),
                "req-win-hit"));
        assertEquals(HttpStatus.CONFLICT, hit.status());
        assertTrue(hit.getMessage().contains("cw"));

        // 不命中时空窗：起点恰为走廊终点（左闭右开），审查通过并固化为 REGULAR
        MutationResponse ok = reviewService.reviewWithWindow(
                "rw", 1, 0L,
                new com.example.starter.service.SpaceTimeWindow(T3, T3 + 1000, 0, 1000),
                "req-win-ok");
        assertEquals("REGULAR", dataReview(ok).reviewKind());
        assertEquals("CLEAR", dataReview(ok).conclusion());
    }

    private ReviewResultDto dataReview(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    @Test
    void emergencyOccupancyFailuresAreDistinguished() {
        createRoute("ez", 10);
        corridorService.createCorridor(corridorReq("ecz", T1, T3, 0, "r"));

        // 未命中指定走廊（时间窗在走廊之后）→ 409
        ApiException notHit = assertThrows(ApiException.class, () ->
                corridorService.registerEmergency(new EmergencyOccupancyRequest(
                        "ez", 1, 0L, T3, T3 + 100, 0, 1000, "ecz", "req-em-miss")));
        assertEquals(HttpStatus.CONFLICT, notHit.status());
        assertEquals("CORRIDOR_NOT_HIT", notHit.code());

        // 走廊不存在 → 404
        ApiException noCorridor = assertThrows(ApiException.class, () ->
                corridorService.registerEmergency(new EmergencyOccupancyRequest(
                        "ez", 1, 0L, T1, T2, 0, 1000, "ghost", "req-em-ghost")));
        assertEquals(HttpStatus.NOT_FOUND, noCorridor.status());

        // 先撤销走廊（尚无禁飞区，版本 0）：应急占用 → 409 CORRIDOR_REVOKED
        corridorService.revokeCorridor(
                new CorridorRevokeRequest("ecz", "任务结束", "req-revoke-ecz"));
        ApiException revoked = assertThrows(ApiException.class, () ->
                corridorService.registerEmergency(new EmergencyOccupancyRequest(
                        "ez", 1, 0L, T1, T2, 0, 1000, "ecz", "req-em-revoked")));
        assertEquals(HttpStatus.CONFLICT, revoked.status());
        assertEquals("CORRIDOR_REVOKED", revoked.code());

        // 应急航线穿越禁飞区：不能以走廊绕过禁区 → 422
        reviewService.createZone(new ZoneCreateRequest(
                "nz", 40, 5, 60, 15, "req-zone-1"));
        ApiException blocked = assertThrows(ApiException.class, () ->
                corridorService.registerEmergency(new EmergencyOccupancyRequest(
                        "ez", 1, 1L, T1, T2, 0, 1000, "ecz", "req-em-blocked")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, blocked.status());
        assertEquals("REVIEW_BLOCKED", blocked.code());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM route_occupancy", Integer.class));
    }

    @Test
    void regularOccupancyBlockedByNoFlyZoneIs422AndCreatesNothing() {
        createRoute("rz", 10);
        reviewService.createZone(new ZoneCreateRequest(
                "zb", 40, 5, 60, 15, "req-zone-b"));
        ApiException ex = assertThrows(ApiException.class, () ->
                corridorService.registerRegular(new RegularOccupancyRequest(
                        "rz", 1, 1L, T1, T2, 0, 1000, true, "req-reg-blocked")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("REVIEW_BLOCKED", ex.code());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM route_occupancy", Integer.class));
    }

    // ============================ 撤销 / 到期 / 重新申请 ============================

    @Test
    void revokeOnlyAffectsFutureAndYieldedRouteMustExplicitlyReapply() {
        String route = createRoute("r-rev", 10);
        String oldOccId = dataOf(regular(route, T1, T3, true)).occupancyId();
        corridorService.createCorridor(corridorReq("cr", T1, T3, 0, "r"));
        assertEquals("YIELDED", corridorService.getOccupancy(oldOccId).status());

        // 走廊仍有效：同窗口重新申请仍 409
        ApiException stillBlocked = assertThrows(ApiException.class, () -> reapply(oldOccId, T1, T3));
        assertEquals(HttpStatus.CONFLICT, stillBlocked.status());
        assertEquals("CORRIDOR_CONFLICT", stillBlocked.code());

        // 撤销走廊：旧占用不自动恢复
        corridorService.revokeCorridor(
                new CorridorRevokeRequest("cr", "提前结束", "req-revoke-cr"));
        assertEquals("YIELDED", corridorService.getOccupancy(oldOccId).status());

        // 必须重新审查并显式申请：新占用 ACTIVE，原占用仍 YIELDED，历史关联完整
        MutationResponse approved = reapply(oldOccId, T1, T3);
        ReapplyResultDto dto = objectMapper.convertValue(approved.data(), ReapplyResultDto.class);
        assertEquals("APPROVED", dto.result());
        assertEquals(oldOccId, dto.oldOccupancyId());
        assertEquals("cr", dto.corridorId());
        assertEquals("ACTIVE", corridorService.getOccupancy(dto.newOccupancyId()).status());
        assertEquals("YIELDED", corridorService.getOccupancy(oldOccId).status());

        // 同一原占用可再次显式申请，历史追加且不可变（按 seq 升序）
        MutationResponse again = reapply(oldOccId, T1, T3);
        List<com.example.starter.api.dto.ReapplyHistoryView> history =
                corridorService.listReapplies(oldOccId);
        assertEquals(2, history.size());
        assertEquals(dto.newOccupancyId(), history.get(0).newOccupancyId());
        assertEquals(objectMapper.convertValue(again.data(), ReapplyResultDto.class)
                .newOccupancyId(), history.get(1).newOccupancyId());
        assertTrue(history.get(1).seq() > history.get(0).seq());

        // 撤销事件追加在建立之后，让渡历史不受撤销影响
        CorridorDetailView detail = corridorService.getCorridorDetail("cr");
        assertEquals(2, detail.version());
        assertEquals("REVOKED", detail.status());
        assertEquals(List.of("CREATED", "REVOKED"),
                detail.events().stream().map(com.example.starter.api.dto.CorridorEventView::action).toList());
        assertEquals(1, detail.yields().size());
        assertEquals("提前结束", detail.revokeReason());
    }

    @Test
    void reapplyGuardsAndRevokeGuards() {
        String route = createRoute("r-g", 10);
        String activeOcc = dataOf(regular(route, T1, T2, true)).occupancyId();

        ApiException notYielded = assertThrows(ApiException.class, () -> reapply(activeOcc, T1, T2));
        assertEquals(HttpStatus.CONFLICT, notYielded.status());
        assertEquals("OCCUPANCY_NOT_YIELDED", notYielded.code());

        ApiException missingOcc = assertThrows(ApiException.class, () -> reapply("occ_ghost", T1, T2));
        assertEquals(HttpStatus.NOT_FOUND, missingOcc.status());

        ApiException missingCorridor = assertThrows(ApiException.class, () ->
                corridorService.revokeCorridor(new CorridorRevokeRequest(
                        "ghost", "r", "req-revoke-ghost")));
        assertEquals(HttpStatus.NOT_FOUND, missingCorridor.status());

        corridorService.createCorridor(corridorReq("cg", T1, T2, 0, "r"));
        corridorService.revokeCorridor(new CorridorRevokeRequest("cg", "r", "req-revoke-cg"));
        ApiException twice = assertThrows(ApiException.class, () ->
                corridorService.revokeCorridor(new CorridorRevokeRequest("cg", "r", "req-revoke-cg2")));
        assertEquals(HttpStatus.CONFLICT, twice.status());
        assertEquals("CORRIDOR_ALREADY_REVOKED", twice.code());
    }

    private MutationResponse reapply(String oldOccupancyId, long start, long end) {
        return corridorService.reapply(new YieldedReapplyRequest(
                oldOccupancyId, 1, 0L, start, end, 0, 1000, true,
                "req-reapply-" + uid(oldOccupancyId)));
    }

    @Test
    void naturalExpiryOnlyAffectsFutureDecisionsUsingControllableClock() {
        String route = createRoute("r-exp", 10);
        corridorService.createCorridor(corridorReq("cx", T1, T2, 0, "短走廊"));

        // 到期前：常规占用 409，应急占用可登记
        ApiException blocked = assertThrows(ApiException.class, () -> regular(route, T1, T2, true));
        assertEquals(HttpStatus.CONFLICT, blocked.status());
        MutationResponse emergency = corridorService.registerEmergency(new EmergencyOccupancyRequest(
                "r-exp", 1, 0L, T1, T2, 0, 1000, "cx", "req-em-exp"));
        assertEquals("ACTIVE", dataOf(emergency).status());

        // 可控时钟推进到走廊终点之后：状态不回写，仍为 ACTIVE，但判定为已到期
        clock.set(T2 + 1);
        assertEquals("ACTIVE", corridorService.getCorridorDetail("cx").status());
        MutationResponse regularAfter = regular(route, T1, T2, true);
        assertEquals("ACTIVE", dataOf(regularAfter).status());

        ApiException emergencyExpired = assertThrows(ApiException.class, () ->
                corridorService.registerEmergency(new EmergencyOccupancyRequest(
                        "r-exp", 1, 0L, T1, T2, 0, 1000, "cx", "req-em-exp2")));
        assertEquals(HttpStatus.CONFLICT, emergencyExpired.status());
        assertEquals("CORRIDOR_EXPIRED", emergencyExpired.code());

        // 已到期走廊不再阻止同优先级新走廊重叠
        MutationResponse replacement = corridorService.createCorridor(
                corridorReq("cx2", T1, T2, 0, "接替走廊"));
        assertFalse(replacement.replayed());
    }

    @Test
    void departureBranchesAndCorridorRaceWinnerRules() {
        String route = createRoute("r-dep2", 10);
        String occId = dataOf(regular(route, T1, T3, true)).occupancyId();

        ApiException missing = assertThrows(ApiException.class, () ->
                corridorService.registerDeparture(new DepartureRequest("occ_ghost", "req-d0")));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        MutationResponse departed = corridorService.registerDeparture(
                new DepartureRequest(occId, "req-d1"));
        assertEquals("DEPARTED", objectMapper.convertValue(departed.data(),
                com.example.starter.api.dto.DepartureResult.class).status());

        ApiException twice = assertThrows(ApiException.class, () ->
                corridorService.registerDeparture(new DepartureRequest(occId, "req-d2")));
        assertEquals(HttpStatus.CONFLICT, twice.status());
        assertEquals("OCCUPANCY_ALREADY_DEPARTED", twice.code());
    }

    // ============================ 幂等 ============================

    @Test
    void corridorCreateIdempotentReplayDoesNotYieldTwice() {
        String route = createRoute("r-idem", 10);
        String occId = dataOf(regular(route, T1, T2, true)).occupancyId();
        CorridorCreateRequest req = new CorridorCreateRequest("ci", 40, 5, 60, 15,
                T1, T3, 0, 1000, 0, "r", "fixed-corridor-key");

        MutationResponse first = corridorService.createCorridor(req);
        MutationResponse replay = corridorService.createCorridor(req);
        assertTrue(replay.replayed());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM emergency_corridor WHERE corridor_id = 'ci'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM yield_history WHERE corridor_id = 'ci'", Integer.class));
        assertEquals("YIELDED", corridorService.getOccupancy(occId).status());

        // 同键异参 409
        ApiException diff = assertThrows(ApiException.class, () -> corridorService.createCorridor(
                new CorridorCreateRequest("ci", 40, 5, 60, 16,
                        T1, T3, 0, 1000, 0, "r", "fixed-corridor-key")));
        assertEquals(HttpStatus.CONFLICT, diff.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", diff.code());
    }

    @Test
    void failedDepartureDoesNotConsumeKey() {
        String key = "fixed-departure-fail";
        ApiException missing = assertThrows(ApiException.class, () ->
                corridorService.registerDeparture(new DepartureRequest("occ_ghost", key)));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
        assertEquals(0, jdbc.queryForList(
                "SELECT request_id FROM request_dedup WHERE request_id = ?", key).size());

        String route = createRoute("r-idem-dep", 10);
        String occId = dataOf(regular(route, T1, T2, true)).occupancyId();
        MutationResponse ok = corridorService.registerDeparture(new DepartureRequest(occId, key));
        assertFalse(ok.replayed());
        MutationResponse replay = corridorService.registerDeparture(new DepartureRequest(occId, key));
        assertTrue(replay.replayed());
    }

    // ============================ 真实并发裁决（H2 行锁） ============================

    @Test
    void concurrentCorridorAndRegularOccupancyNeverCoexistActive() throws Exception {
        int rounds = 15;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < rounds; i++) {
                final int round = i;
                String routeId = "crr-" + i;
                String corridorId = "crc-" + i;
                // 每轮用互不相交的 y 几何，避免历轮同优先级走廊互相重叠
                int y = 10 + i * 100;
                createRoute(routeId, y);

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Object> occFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return corridorService.registerRegular(new RegularOccupancyRequest(
                                routeId, 1, 0L, T1, T3, 0, 1000, true,
                                "req-occ-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> corridorFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return corridorService.createCorridor(new CorridorCreateRequest(
                                corridorId, 40, y - 5, 60, y + 5,
                                T1, T3, 0, 1000, 0, "并发走廊-" + round,
                                "req-corridor-" + corridorId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object occResult = occFuture.get(20, TimeUnit.SECONDS);
                Object corridorResult = corridorFuture.get(20, TimeUnit.SECONDS);
                assertTrue(corridorResult instanceof MutationResponse,
                        "可让渡占用下走廊建立只能成功");

                Integer corridorCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM emergency_corridor WHERE corridor_id = ?",
                        Integer.class, corridorId);
                Integer activeCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM route_occupancy WHERE route_id = ? AND status = 'ACTIVE'",
                        Integer.class, routeId);
                Integer yieldedCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM route_occupancy WHERE route_id = ? AND status = 'YIELDED'",
                        Integer.class, routeId);

                if (occResult instanceof MutationResponse) {
                    // 占用先提交：走廊随后必须让渡它
                    assertEquals(1, activeCount + yieldedCount);
                    assertEquals(1, yieldedCount, "占用先成功后必须被走廊让渡");
                    assertEquals(0, activeCount, "不得同时存在有效走廊与未让渡常规占用");
                } else {
                    // 走廊先提交：占用申请必须 409 且不落任何占用
                    ApiException ex = (ApiException) occResult;
                    assertEquals(HttpStatus.CONFLICT, ex.status());
                    assertEquals("CORRIDOR_CONFLICT", ex.code());
                    assertEquals(0, activeCount + yieldedCount);
                }
                assertEquals(1, corridorCount);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentCorridorAndDepartureHasExactlyOneWinner() throws Exception {
        int rounds = 15;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < rounds; i++) {
                final int round = i;
                String routeId = "cdr-" + i;
                String corridorId = "cdc-" + i;
                // 每轮用互不相交的 y 几何，避免历轮同优先级走廊互相重叠
                int y = 10 + i * 100;
                createRoute(routeId, y);
                String occId = dataOf(regular(routeId, T1, T3, true)).occupancyId();

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Object> departFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return corridorService.registerDeparture(
                                new DepartureRequest(occId, "req-dep-" + round));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> corridorFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return corridorService.createCorridor(new CorridorCreateRequest(
                                corridorId, 40, y - 5, 60, y + 5,
                                T1, T3, 0, 1000, 0, "并发走廊-" + round,
                                "req-corridor-" + corridorId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object departResult = departFuture.get(20, TimeUnit.SECONDS);
                Object corridorResult = corridorFuture.get(20, TimeUnit.SECONDS);
                String status = jdbc.queryForObject(
                        "SELECT status FROM route_occupancy WHERE occupancy_id = ?",
                        String.class, occId);
                Integer corridorCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM emergency_corridor WHERE corridor_id = ?",
                        Integer.class, corridorId);

                if (departResult instanceof MutationResponse) {
                    // 起飞先提交：走廊必须 422 回滚，占用 DEPARTED
                    assertTrue(corridorResult instanceof ApiException);
                    ApiException ex = (ApiException) corridorResult;
                    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
                    assertEquals("OCCUPANCY_DEPARTED", ex.code());
                    assertEquals("DEPARTED", status);
                    assertEquals(0, corridorCount);
                    assertEquals(0, jdbc.queryForObject(
                            "SELECT COUNT(*) FROM yield_history WHERE corridor_id = ?",
                            Integer.class, corridorId));
                } else {
                    // 走廊先提交：占用已让渡，起飞必须 409
                    assertTrue(corridorResult instanceof MutationResponse);
                    ApiException ex = (ApiException) departResult;
                    assertEquals(HttpStatus.CONFLICT, ex.status());
                    assertEquals("OCCUPANCY_YIELDED", ex.code());
                    assertEquals("YIELDED", status);
                    assertEquals(1, corridorCount);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
