package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.CapacityConflictException;
import com.example.starter.api.dto.CapacitySetRequest;
import com.example.starter.api.dto.DepartureRequest;
import com.example.starter.api.dto.DepartureResultDto;
import com.example.starter.api.dto.DisplacedRouteDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.PriorityReviewRequest;
import com.example.starter.api.dto.PriorityReviewResultDto;
import com.example.starter.api.dto.PreemptionResultDto;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.SpaceTimePointDto;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.repo.ClearancePo;
import com.example.starter.service.AirspaceReviewService;
import com.example.starter.service.DiversionPriorityService;
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
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 紧急备降优先级与时空容量抢占的 H2 数据库测试（MODE=MySQL）。
 * 覆盖紧急优先级、不可抢占状态（已起飞/另一紧急）、完整受影响集合原子更新、
 * 不可变快照冻结、区域版本失效与并发幂等边界；全部使用真实 H2 与真实事务。
 */
@SpringBootTest
@DisplayName("紧急备降优先级与时空容量抢占")
class DiversionPriorityServiceH2Test {

    /** 基准时刻（epoch 毫秒，UTC），落在时间桶 2833334 内。 */
    private static final long T0 = 1_700_000_040_000L;
    private static final long BUCKET_MS = 10L * 60L * 1000L;

    @Autowired
    private DiversionPriorityService priorityService;
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
        jdbc.update("DELETE FROM preemption_item");
        jdbc.update("DELETE FROM preemption");
        jdbc.update("DELETE FROM clearance_bucket");
        jdbc.update("DELETE FROM clearance");
        jdbc.update("DELETE FROM cell_capacity");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    // ============================ 夹具 ============================

    private String req(String prefix) {
        return "req-" + prefix + "-" + seq.incrementAndGet();
    }

    private void route(String routeId) {
        reviewService.createRoute(new RouteCreateRequest(routeId,
                List.of(new RoutePointDto(0, 0), new RoutePointDto(100, 0)), req("route")));
    }

    /** 单个采样点：时刻 tOffset 个 10 分钟桶之后，坐标 (x, y)。 */
    private static List<SpaceTimePointDto> seg(int x, int y, int tOffset) {
        return List.of(new SpaceTimePointDto(T0 + tOffset * BUCKET_MS, x, y));
    }

    private static List<SpaceTimePointDto> seg(int x, int y, long atMillis) {
        return List.of(new SpaceTimePointDto(atMillis, x, y));
    }

    private PriorityReviewResultDto submit(String routeId, String priority, String eventNo,
                                           List<SpaceTimePointDto> segments, String requestId) {
        MutationResponse resp = priorityService.submitPriorityReview(new PriorityReviewRequest(
                routeId, 1, jdbc.queryForObject(
                        "SELECT global_version FROM airspace_meta WHERE id = 1", Long.class),
                priority, eventNo, segments, requestId));
        assertFalse(resp.replayed(), "首次提交不应是重放");
        return objectMapper.convertValue(resp.data(), PriorityReviewResultDto.class);
    }

    private PriorityReviewResultDto normal(String routeId, int x, int y, int tOffset) {
        return submit(routeId, "NORMAL", null, seg(x, y, tOffset), req("n"));
    }

    private PriorityReviewResultDto emergency(String routeId, String eventNo,
                                              int x, int y, int tOffset) {
        return submit(routeId, "EMERGENCY", eventNo, seg(x, y, tOffset), req("e"));
    }

    private long bucketOf(int tOffset) {
        return Math.floorDiv(T0 + tOffset * BUCKET_MS, BUCKET_MS);
    }

    private long bucketOfMillis(long millis) {
        return Math.floorDiv(millis, BUCKET_MS);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private DepartureResultDto depart(String clearanceId) {
        MutationResponse resp = priorityService.registerDeparture(
                new DepartureRequest(clearanceId, req("dep")));
        return objectMapper.convertValue(resp.data(), DepartureResultDto.class);
    }

    // ============================ 主流程：NORMAL 容量 ============================

    @Test
    @DisplayName("NORMAL 批准后占用容量桶；第二架 NORMAL 同桶 422 并列出占用航线")
    void normalApprovalOccupiesBucketAndSecondNormalIs422() {
        route("n1");
        route("n2");
        PriorityReviewResultDto c1 = normal("n1", 0, 0, 0);

        DiversionPriorityService.CapacityBucketViewResult view =
                priorityService.getCapacityBucket(0, 0, bucketOf(0));
        assertEquals(1, view.capacity());
        assertEquals(1, view.occupied());
        assertEquals("n1", view.occupants().get(0).routeId());
        assertEquals("APPROVED", view.occupants().get(0).clearanceStatus());

        String failKey = req("fail");
        CapacityConflictException ex = assertThrows(CapacityConflictException.class,
                () -> submit("n2", "NORMAL", null, seg(0, 0, 0), failKey));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals(1, ex.blockingRoutes().size());
        assertEquals("n1", ex.blockingRoutes().get(0).routeId());
        assertEquals(c1.clearanceId(), ex.blockingRoutes().get(0).clearanceId());
        assertEquals("APPROVED", ex.blockingRoutes().get(0).status());

        // 失败回滚：不写批件、不占幂等键
        assertEquals(0, count("SELECT COUNT(*) FROM request_dedup WHERE request_id = ?", failKey));
        assertEquals(1, count("SELECT COUNT(*) FROM clearance"));
        // 键未被占用：同键改提交到另一空闲单元可以成功
        PriorityReviewResultDto c2 = submit("n2", "NORMAL", null, seg(5_000, 5_000, 0), failKey);
        assertNotNull(c2.clearanceId());
    }

    @Test
    @DisplayName("不同时间桶或不同格网单元互不挤占；单元容量可配置且跨桶生效")
    void differentBucketsAndCellsAreIndependentAndCapacityIsPerCell() {
        route("n1");
        route("n2");
        route("n3");
        normal("n1", 0, 0, 0);
        // 同单元下一桶：缺省容量 1 也允许
        normal("n2", 0, 0, 1);
        // 不同单元同一桶：允许
        normal("n3", 2_000, 0, 0);

        // 把单元 (0,0) 容量调到 2，两个时间桶都受影响
        priorityService.setCapacity(new CapacitySetRequest(0, 0, 2, req("cap")));
        route("n4");
        route("n5");
        normal("n4", 0, 0, 0);
        normal("n5", 0, 0, 1);
        // 两个桶各自恰好 2 占用
        assertEquals(2, priorityService.getCapacityBucket(0, 0, bucketOf(0)).occupied());
        assertEquals(2, priorityService.getCapacityBucket(0, 0, bucketOf(1)).occupied());

        route("n6");
        CapacityConflictException ex = assertThrows(CapacityConflictException.class,
                () -> normal("n6", 0, 0, 0));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
    }

    // ============================ 紧急抢占主流程 ============================

    @Test
    @DisplayName("EMERGENCY 抢占未起飞 NORMAL：同事务置换、删占用、写不可变快照")
    void emergencyPreemptsApprovedNormalAtomically() {
        route("n1");
        PriorityReviewResultDto n1 = normal("n1", 0, 0, 0);

        route("e1");
        PriorityReviewResultDto e1 = emergency("e1", "EVT-001", 0, 0, 0);

        assertEquals("EMERGENCY", e1.priority());
        assertEquals("EVT-001", e1.eventNo());
        assertNotNull(e1.preemptionId());
        assertEquals(1, e1.displaced().size());
        assertEquals("n1", e1.displaced().get(0).routeId());
        assertEquals(n1.clearanceId(), e1.displaced().get(0).displacedClearanceId());
        assertEquals("PENDING", e1.displaced().get(0).status());

        // 被置换批件状态与占用
        ClearancePo displaced = priorityService.getClearance(n1.clearanceId());
        assertEquals("DISPLACED", displaced.status());
        assertNotNull(displaced.displacedAt());
        DiversionPriorityService.CapacityBucketViewResult view =
                priorityService.getCapacityBucket(0, 0, bucketOf(0));
        assertEquals(1, view.occupied());
        assertEquals("e1", view.occupants().get(0).routeId());
        assertEquals("EMERGENCY", view.occupants().get(0).priority());

        // 不可变抢占快照
        PreemptionResultDto snapshot = priorityService.getPreemption(e1.preemptionId());
        assertEquals("e1", snapshot.emergencyRouteId());
        assertEquals(e1.clearanceId(), snapshot.emergencyClearanceId());
        assertEquals("EVT-001", snapshot.eventNo());
        assertEquals(0L, snapshot.airspaceVersion());
        assertEquals(List.of("n1"), snapshot.displacedRouteIds());
        assertEquals(1, snapshot.items().size());
        assertEquals(1, snapshot.items().get(0).routeVersionSnapshot());
        assertEquals("PENDING", snapshot.items().get(0).status());
        assertNull(snapshot.items().get(0).resolvedClearanceId());

        // 被置换航线查询
        List<DisplacedRouteDto> displacedRoutes = priorityService.getDisplacedRoutes("n1");
        assertEquals(1, displacedRoutes.size());
        assertEquals("PENDING", displacedRoutes.get(0).status());

        // 紧急航线落地后，第二架紧急同桶不可抢占另一 EMERGENCY → 422
        route("e2");
        CapacityConflictException ex = assertThrows(CapacityConflictException.class,
                () -> emergency("e2", "EVT-002", 0, 0, 0));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("EMERGENCY", ex.blockingRoutes().get(0).reason());
        assertEquals("e1", ex.blockingRoutes().get(0).routeId());
        assertEquals(2, count("SELECT COUNT(*) FROM clearance"), "失败不得新增批件");
        assertEquals(1, count("SELECT COUNT(*) FROM preemption"), "失败不得新增快照");
    }

    @Test
    @DisplayName("容量充足时 EMERGENCY 不抢占任何 NORMAL")
    void emergencyDoesNotPreemptWhenCapacitySuffices() {
        priorityService.setCapacity(new CapacitySetRequest(0, 0, 2, req("cap")));
        route("n1");
        normal("n1", 0, 0, 0);
        route("e1");
        PriorityReviewResultDto e1 = emergency("e1", "EVT-009", 0, 0, 0);
        assertNull(e1.preemptionId());
        assertTrue(e1.displaced().isEmpty());
        assertEquals("APPROVED", priorityService.getClearance(e1.clearanceId()).status());
        assertTrue(priorityService.getDisplacedRoutes("n1").isEmpty());
        assertEquals(2, priorityService.getCapacityBucket(0, 0, bucketOf(0)).occupied());
    }

    // ============================ 不可抢占状态 ============================

    @Test
    @DisplayName("已起飞 NORMAL 不可抢占：422 列出 DEPARTED 航线且整次抢占回滚")
    void departedNormalCannotBePreempted() {
        route("n1");
        PriorityReviewResultDto n1 = normal("n1", 0, 0, 0);
        DepartureResultDto departure = depart(n1.clearanceId());
        assertEquals("DEPARTED", departure.status());

        route("e1");
        String key = req("e");
        CapacityConflictException ex = assertThrows(CapacityConflictException.class,
                () -> submit("e1", "EMERGENCY", "EVT-003", seg(0, 0, 0), key));
        assertEquals(1, ex.blockingRoutes().size());
        assertEquals("n1", ex.blockingRoutes().get(0).routeId());
        assertEquals("DEPARTED", ex.blockingRoutes().get(0).status());
        assertEquals("DEPARTED", ex.blockingRoutes().get(0).reason());

        // 整次回滚：紧急批件/快照不存在、幂等键未占、已起飞航线仍持占用
        ApiException notFound = assertThrows(ApiException.class,
                () -> priorityService.getPreemption("pm_does_not_exist"));
        assertEquals(HttpStatus.NOT_FOUND, notFound.status());
        assertEquals(0, count("SELECT COUNT(*) FROM request_dedup WHERE request_id = ?", key));
        assertEquals("DEPARTED", priorityService.getClearance(n1.clearanceId()).status());
        assertEquals(1, priorityService.getCapacityBucket(0, 0, bucketOf(0)).occupied());
    }

    @Test
    @DisplayName("两条 EMERGENCY 互不可抢占；NORMAL 也不能挤走紧急航线")
    void emergencyRoutesCannotPreemptEachOther() {
        priorityService.setCapacity(new CapacitySetRequest(0, 0, 2, req("cap")));
        route("e1");
        emergency("e1", "EVT-1", 0, 0, 0);
        route("e2");
        emergency("e2", "EVT-2", 0, 0, 0);

        route("e3");
        CapacityConflictException byEmergency = assertThrows(CapacityConflictException.class,
                () -> emergency("e3", "EVT-3", 0, 0, 0));
        Set<String> blockerIds = byEmergency.blockingRoutes().stream()
                .map(CapacityConflictException.BlockingRoute::routeId)
                .collect(Collectors.toSet());
        assertTrue(blockerIds.contains("e1") || blockerIds.contains("e2"));
        assertTrue(byEmergency.blockingRoutes().stream()
                .allMatch(b -> "EMERGENCY".equals(b.reason())));

        route("n2");
        CapacityConflictException byNormal = assertThrows(CapacityConflictException.class,
                () -> normal("n2", 0, 0, 0));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, byNormal.status());
    }

    // ============================ 完整集合与原子更新 ============================

    @Test
    @DisplayName("跨多桶完整受影响集合一次性置换；集合内任一不可抢占则全部回滚")
    void completeAffectedSetIsComputedAcrossBucketsAndFailsAtomically() {
        // n1 在单元 A(0,0) 占 b0,b1；n2 在单元 B(2,0) 占 b1,b2（初始互不共桶）。
        // 紧急航线跨 A、B 两单元覆盖 b0,b1,b2：每个桶容量 1 且各有一个 NORMAL，
        // 必须同时置换两条航线
        route("n1");
        route("n2");
        List<SpaceTimePointDto> n1Segs = List.of(
                new SpaceTimePointDto(T0, 0, 0),
                new SpaceTimePointDto(T0 + BUCKET_MS, 0, 0));
        List<SpaceTimePointDto> n2Segs = List.of(
                new SpaceTimePointDto(T0 + BUCKET_MS, 2_000, 0),
                new SpaceTimePointDto(T0 + 2 * BUCKET_MS, 2_000, 0));
        PriorityReviewResultDto c1 = submit("n1", "NORMAL", null, n1Segs, req("n"));
        PriorityReviewResultDto c2 = submit("n2", "NORMAL", null, n2Segs, req("n"));

        route("e1");
        List<SpaceTimePointDto> eSegs = List.of(
                new SpaceTimePointDto(T0, 0, 0),
                new SpaceTimePointDto(T0 + BUCKET_MS, 0, 0),
                new SpaceTimePointDto(T0 + BUCKET_MS + 1_000, 2_000, 0),
                new SpaceTimePointDto(T0 + 2 * BUCKET_MS, 2_000, 0));
        PriorityReviewResultDto e1 = submit("e1", "EMERGENCY", "EVT-MULTI", eSegs, req("e"));
        assertEquals(Set.of("n1", "n2"), e1.displaced().stream()
                .map(DisplacedRouteDto::routeId).collect(Collectors.toSet()));
        assertEquals("DISPLACED", priorityService.getClearance(c1.clearanceId()).status());
        assertEquals("DISPLACED", priorityService.getClearance(c2.clearanceId()).status());
        assertEquals(1, priorityService.getCapacityBucket(0, 0, bucketOf(0)).occupied());
        assertEquals(1, priorityService.getCapacityBucket(0, 0, bucketOf(1)).occupied());
        assertEquals(1, priorityService.getCapacityBucket(2, 0, bucketOf(1)).occupied());
        assertEquals(1, priorityService.getCapacityBucket(2, 0, bucketOf(2)).occupied());
        PreemptionResultDto snap = priorityService.getPreemption(e1.preemptionId());
        assertEquals(List.of("n1", "n2"), snap.displacedRouteIds());
        assertEquals(2, snap.items().size());
    }

    @Test
    @DisplayName("完整集合中存在已起飞航线时，未起飞 NORMAL 也保持 APPROVED（整次回滚）")
    void atomicRollbackWhenOneVictimIsDeparted() {
        route("n1");
        route("n2");
        List<SpaceTimePointDto> n1Segs = List.of(
                new SpaceTimePointDto(T0, 0, 0),
                new SpaceTimePointDto(T0 + BUCKET_MS, 0, 0));
        List<SpaceTimePointDto> n2Segs = List.of(
                new SpaceTimePointDto(T0 + BUCKET_MS, 2_000, 0),
                new SpaceTimePointDto(T0 + 2 * BUCKET_MS, 2_000, 0));
        PriorityReviewResultDto c1 = submit("n1", "NORMAL", null, n1Segs, req("n"));
        PriorityReviewResultDto c2 = submit("n2", "NORMAL", null, n2Segs, req("n"));
        // n1 起飞：紧急航线在 (A,b0) 撞不可抢占，尽管其余桶的 n2 可抢占，也必须整体失败
        depart(c1.clearanceId());

        route("e1");
        List<SpaceTimePointDto> eSegs = List.of(
                new SpaceTimePointDto(T0, 0, 0),
                new SpaceTimePointDto(T0 + BUCKET_MS, 0, 0),
                new SpaceTimePointDto(T0 + BUCKET_MS + 1_000, 2_000, 0),
                new SpaceTimePointDto(T0 + 2 * BUCKET_MS, 2_000, 0));
        CapacityConflictException ex = assertThrows(CapacityConflictException.class,
                () -> submit("e1", "EMERGENCY", "EVT-MULTI-FAIL", eSegs, req("e")));
        assertTrue(ex.blockingRoutes().stream().anyMatch(b -> "n1".equals(b.routeId())
                && "DEPARTED".equals(b.reason())));

        // 原子性：n2 未被置换、没有任何抢占快照、紧急批件不存在、占用全部保留
        assertEquals("APPROVED", priorityService.getClearance(c2.clearanceId()).status());
        assertEquals("DEPARTED", priorityService.getClearance(c1.clearanceId()).status());
        assertEquals(0, count("SELECT COUNT(*) FROM preemption"));
        assertEquals(0, count("SELECT COUNT(*) FROM preemption_item"));
        assertEquals(2, count("SELECT COUNT(*) FROM clearance"));
        assertEquals(1, priorityService.getCapacityBucket(0, 0, bucketOf(0)).occupied());
        assertEquals(1, priorityService.getCapacityBucket(0, 0, bucketOf(1)).occupied());
        assertEquals(1, priorityService.getCapacityBucket(2, 0, bucketOf(1)).occupied());
        assertEquals(1, priorityService.getCapacityBucket(2, 0, bucketOf(2)).occupied());
    }

    // ============================ 被置换航线重新提交 ============================

    @Test
    @DisplayName("被置换航线不自动复原，重新审查批准后抢占记录置 RESOLVED；同一航线至多一条 PENDING")
    void displacedRouteResubmitsManuallyAndOnlyOnePendingRecord() {
        route("n1");
        PriorityReviewResultDto n1Old = normal("n1", 0, 0, 0);
        route("e1");
        PriorityReviewResultDto e1 = emergency("e1", "EVT-A", 0, 0, 0);
        assertEquals("DISPLACED", priorityService.getClearance(n1Old.clearanceId()).status());

        // 同桶重新提交仍被紧急航线占用 → 422，不自动复原
        assertThrows(CapacityConflictException.class,
                () -> submit("n1", "NORMAL", null, seg(0, 0, 0), req("n")));
        assertEquals("DISPLACED", priorityService.getClearance(n1Old.clearanceId()).status());

        // 换到空闲桶重新提交 → 新批件，抢占记录 RESOLVED
        PriorityReviewResultDto n1New = submit("n1", "NORMAL", null, seg(9_000, 0, 0), req("n"));
        assertEquals("APPROVED", priorityService.getClearance(n1New.clearanceId()).status());
        assertEquals("DISPLACED", priorityService.getClearance(n1Old.clearanceId()).status(),
                "旧批件保持 DISPLACED，不自动复原");
        PreemptionResultDto snap = priorityService.getPreemption(e1.preemptionId());
        assertEquals("RESOLVED", snap.items().get(0).status());
        assertEquals(n1New.clearanceId(), snap.items().get(0).resolvedClearanceId());

        // 新批件在另一单元再被紧急抢占：允许产生第二条记录，但全航线仍只有一条 PENDING
        route("e2");
        PriorityReviewResultDto e2 = emergency("e2", "EVT-B", 9_000, 0, 0);
        List<DisplacedRouteDto> items = priorityService.getDisplacedRoutes("n1");
        assertEquals(2, items.size());
        assertEquals(1, items.stream().filter(i -> "PENDING".equals(i.status())).count());
        assertEquals(e2.preemptionId(), priorityService.getPreemption(e2.preemptionId())
                .preemptionId());
    }

    @Test
    @DisplayName("同航线重新提交作废旧 APPROVED 批件；已起飞航线禁止再提交")
    void resubmissionSupersedesOldApprovedAndDepartedIsRejected() {
        route("n1");
        PriorityReviewResultDto old = normal("n1", 0, 0, 0);
        PriorityReviewResultDto now2 = submit("n1", "NORMAL", null, seg(2_000, 0, 0), req("n"));
        assertEquals("SUPERSEDED", priorityService.getClearance(old.clearanceId()).status());
        assertEquals("APPROVED", priorityService.getClearance(now2.clearanceId()).status());
        assertEquals(0, priorityService.getCapacityBucket(0, 0, bucketOf(0)).occupied());
        assertEquals(1, priorityService.getCapacityBucket(2, 0, bucketOf(0)).occupied());

        ApiException staleDeparture = assertThrows(ApiException.class,
                () -> depart(old.clearanceId()));
        assertEquals(HttpStatus.CONFLICT, staleDeparture.status());

        depart(now2.clearanceId());
        ApiException resubmitDeparted = assertThrows(ApiException.class,
                () -> submit("n1", "NORMAL", null, seg(3_000, 0, 0), req("n")));
        assertEquals(HttpStatus.CONFLICT, resubmitDeparted.status());
        assertEquals("ROUTE_ALREADY_DEPARTED", resubmitDeparted.code());
    }

    // ============================ 快照冻结与区域版本失效 ============================

    @Test
    @DisplayName("区域版本更新后抢占快照不改写；已批准未起飞批件按版本失效规则拒绝起飞")
    void snapshotFrozenAndVersionInvalidationAfterZoneUpdate() {
        route("n1");
        PriorityReviewResultDto n1 = normal("n1", 0, 0, 0);
        route("e1");
        PriorityReviewResultDto e1 = emergency("e1", "EVT-FREEZE", 0, 0, 0);
        PreemptionResultDto before = priorityService.getPreemption(e1.preemptionId());

        // 区域更新推进全局版本（远离航线几何，不影响区域审查结论）
        reviewService.createZone(new ZoneCreateRequest("far", -90_000, -90_000, -80_000, -80_000,
                req("zone")));

        PreemptionResultDto after = priorityService.getPreemption(e1.preemptionId());
        assertEquals(before.airspaceVersion(), after.airspaceVersion());
        assertEquals(before.displacedRouteIds(), after.displacedRouteIds());
        assertEquals(before.items().get(0).routeVersionSnapshot(),
                after.items().get(0).routeVersionSnapshot());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id = 1", Long.class));

        // 紧急批件已批准未起飞：版本失效，起飞登记 409，需重新提交审查
        ApiException departureStale = assertThrows(ApiException.class,
                () -> depart(e1.clearanceId()));
        assertEquals(HttpStatus.CONFLICT, departureStale.status());
        assertEquals("VERSION_CONFLICT", departureStale.code());

        // 被置换航线旧批件同样不能起飞
        ApiException displacedDeparture = assertThrows(ApiException.class,
                () -> depart(n1.clearanceId()));
        assertEquals(HttpStatus.CONFLICT, displacedDeparture.status());

        // 用新空域版本重新提交紧急航线可以再次批准（旧 APPROVED 作旧）
        PriorityReviewResultDto e1v2 = submit("e1", "EMERGENCY", "EVT-FREEZE",
                seg(0, 0, 0), req("e"));
        assertEquals(1L, e1v2.airspaceVersion());
        DepartureResultDto departure = depart(e1v2.clearanceId());
        assertEquals("DEPARTED", departure.status());
        // 快照数量不因版本更新而改变（首次抢占仅一条）
        assertEquals(1, count("SELECT COUNT(*) FROM preemption"));
    }

    // ============================ 失败分支与校验 ============================

    @Test
    @DisplayName("EMERGENCY 必须附事件编号；NORMAL 不得携带事件编号；时空段须严格升序")
    void priorityAndSegmentValidation() {
        route("n1");
        ApiException noEvent = assertThrows(ApiException.class,
                () -> submit("n1", "EMERGENCY", null, seg(0, 0, 0), req("e")));
        assertEquals(HttpStatus.BAD_REQUEST, noEvent.status());
        assertEquals("EVENT_NO_REQUIRED", noEvent.code());

        ApiException blankEvent = assertThrows(ApiException.class,
                () -> submit("n1", "EMERGENCY", "  ", seg(0, 0, 0), req("e")));
        assertEquals(HttpStatus.BAD_REQUEST, blankEvent.status());

        ApiException eventOnNormal = assertThrows(ApiException.class,
                () -> submit("n1", "NORMAL", "EVT-X", seg(0, 0, 0), req("n")));
        assertEquals(HttpStatus.BAD_REQUEST, eventOnNormal.status());

        ApiException badPriority = assertThrows(ApiException.class,
                () -> submit("n1", "URGENT", null, seg(0, 0, 0), req("n")));
        assertEquals(HttpStatus.BAD_REQUEST, badPriority.status());

        List<SpaceTimePointDto> unordered = List.of(
                new SpaceTimePointDto(T0 + BUCKET_MS, 0, 0),
                new SpaceTimePointDto(T0, 0, 0));
        ApiException notOrdered = assertThrows(ApiException.class,
                () -> submit("n1", "NORMAL", null, unordered, req("n")));
        assertEquals(HttpStatus.BAD_REQUEST, notOrdered.status());
    }

    @Test
    @DisplayName("时空段规范化：同桶多点去重；同桶重复采样只产生一个占用行")
    void segmentsAreNormalizedAndDeduplicated() {
        route("n1");
        List<SpaceTimePointDto> sameBucket = List.of(
                new SpaceTimePointDto(T0 + 1_000, 100, 100),
                new SpaceTimePointDto(T0 + 2_000, 200, 200));
        PriorityReviewResultDto c = submit("n1", "NORMAL", null, sameBucket, req("n"));
        assertEquals(1, c.buckets().size());
        assertEquals(1, count("SELECT COUNT(*) FROM clearance_bucket WHERE clearance_id = ?",
                c.clearanceId()));
    }

    @Test
    @DisplayName("航线不存在 404；航线/空域版本不匹配 409；重复起飞与失效批件起飞 409")
    void missingRouteVersionConflictsAndDepartureStates() {
        ApiException noRoute = assertThrows(ApiException.class,
                () -> submit("ghost", "NORMAL", null, seg(0, 0, 0), req("n")));
        assertEquals(HttpStatus.NOT_FOUND, noRoute.status());

        route("n1");
        ApiException wrongRouteVersion = assertThrows(ApiException.class,
                () -> priorityService.submitPriorityReview(new PriorityReviewRequest(
                        "n1", 9, 0L, "NORMAL", null, seg(0, 0, 0), req("n"))));
        assertEquals(HttpStatus.CONFLICT, wrongRouteVersion.status());

        reviewService.createZone(new ZoneCreateRequest("z", -90_000, -90_000, -80_000, -80_000,
                req("zone")));
        ApiException wrongAirspaceVersion = assertThrows(ApiException.class,
                () -> priorityService.submitPriorityReview(new PriorityReviewRequest(
                        "n1", 1, 0L, "NORMAL", null, seg(9_000, 9_000, 0), req("n"))));
        assertEquals(HttpStatus.CONFLICT, wrongAirspaceVersion.status());

        // 命中有效禁飞区 → 422：另建一条水平穿过 y=10 的航线 zr
        reviewService.createZone(new ZoneCreateRequest("zz", 40, 5, 60, 15, req("zone")));
        reviewService.createRoute(new RouteCreateRequest("zr",
                List.of(new RoutePointDto(0, 10), new RoutePointDto(100, 10)), req("route")));
        ApiException hitsZone = assertThrows(ApiException.class,
                () -> submit("zr", "NORMAL", null, seg(9_000, 9_000, 1), req("n")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, hitsZone.status());
        assertEquals("ROUTE_BLOCKED_BY_NO_FLY_ZONE", hitsZone.code());

        // 不存在的批件起飞 → 404
        ApiException noClearance = assertThrows(ApiException.class,
                () -> depart("cl_ghost"));
        assertEquals(HttpStatus.NOT_FOUND, noClearance.status());

        // 正常批件起飞后再登记 → 409（n1 几何不碰任何区域）
        PriorityReviewResultDto c = submit("n1", "NORMAL", null, seg(9_000, 9_000, 1), req("n"));
        depart(c.clearanceId());
        ApiException twice = assertThrows(ApiException.class, () -> depart(c.clearanceId()));
        assertEquals(HttpStatus.CONFLICT, twice.status());
        assertEquals("CLEARANCE_ALREADY_DEPARTED", twice.code());

        // 被置换批件起飞 → 409
        route("n2");
        PriorityReviewResultDto victim = normal("n2", 5_000, 5_000, 2);
        route("e9");
        emergency("e9", "EVT-9", 5_000, 5_000, 2);
        ApiException displacedDeparture = assertThrows(ApiException.class,
                () -> depart(victim.clearanceId()));
        assertEquals("CLEARANCE_NOT_ACTIVE", displacedDeparture.code());
    }

    @Test
    @DisplayName("既有航线审查与新优先级裁决互不干扰，普通 review 仍按原规则返回 CLEAR/BLOCKED")
    void plainReviewStillWorksAlongsidePriorityClearances() {
        route("n1");
        normal("n1", 0, 0, 0);
        MutationResponse resp = reviewService.review(
                new ReviewRequest("n1", 1, 0L, req("review")));
        assertEquals("CLEAR", objectMapper.convertValue(resp.data(),
                com.example.starter.api.dto.ReviewResultDto.class).conclusion());
    }

    // ============================ 幂等与指纹 ============================

    @Test
    @DisplayName("同键同参重放首次快照（含抢占结果）；事件号或时空段变化指纹不同 → 409")
    void sameKeyReplaysFirstSnapshotAndFingerprintCoversPriorityFields() {
        route("n1");
        normal("n1", 0, 0, 0);
        route("e1");
        String key = "fixed-emergency-key";
        PriorityReviewRequest first = new PriorityReviewRequest(
                "e1", 1, 0L, "EMERGENCY", "EVT-1", seg(0, 0, 0), key);
        PriorityReviewResultDto d1 = objectMapper.convertValue(
                priorityService.submitPriorityReview(first).data(), PriorityReviewResultDto.class);

        MutationResponse replayResp = priorityService.submitPriorityReview(first);
        assertTrue(replayResp.replayed());
        PriorityReviewResultDto d2 = objectMapper.convertValue(
                replayResp.data(), PriorityReviewResultDto.class);
        assertEquals(d1.clearanceId(), d2.clearanceId());
        assertEquals(d1.preemptionId(), d2.preemptionId());
        assertEquals(1, count("SELECT COUNT(*) FROM clearance WHERE route_id = 'e1'"));
        assertEquals(1, count("SELECT COUNT(*) FROM preemption"));
        assertEquals(1, count("SELECT COUNT(*) FROM preemption_item"));

        // 事件号变化 → 指纹不同，同键 409
        ApiException differentEvent = assertThrows(ApiException.class,
                () -> priorityService.submitPriorityReview(new PriorityReviewRequest(
                        "e1", 1, 0L, "EMERGENCY", "EVT-OTHER", seg(0, 0, 0), key)));
        assertEquals(HttpStatus.CONFLICT, differentEvent.status());
        // 时空段变化 → 指纹不同
        ApiException differentSegments = assertThrows(ApiException.class,
                () -> priorityService.submitPriorityReview(new PriorityReviewRequest(
                        "e1", 1, 0L, "EMERGENCY", "EVT-1", seg(1_000, 0, 0), key)));
        assertEquals(HttpStatus.CONFLICT, differentSegments.status());
        // 航线版本变化 → 指纹不同（且版本本身不存在）
        ApiException differentVersion = assertThrows(ApiException.class,
                () -> priorityService.submitPriorityReview(new PriorityReviewRequest(
                        "e1", 2, 0L, "EMERGENCY", "EVT-1", seg(0, 0, 0), key)));
        assertEquals(HttpStatus.CONFLICT, differentVersion.status());
        // 同键异种操作 → 409
        ApiException differentKind = assertThrows(ApiException.class,
                () -> priorityService.registerDeparture(
                        new DepartureRequest(d1.clearanceId(), key)));
        assertEquals(HttpStatus.CONFLICT, differentKind.status());
    }

    @Test
    @DisplayName("起飞登记同键重放；容量设置同键重放")
    void departureAndCapacityAreIdempotent() {
        route("n1");
        PriorityReviewResultDto c = normal("n1", 0, 0, 0);
        String depKey = "fixed-departure";
        DepartureResultDto d1 = objectMapper.convertValue(priorityService.registerDeparture(
                new DepartureRequest(c.clearanceId(), depKey)).data(), DepartureResultDto.class);
        MutationResponse replay = priorityService.registerDeparture(
                new DepartureRequest(c.clearanceId(), depKey));
        assertTrue(replay.replayed());
        assertEquals(d1.departedAt(), objectMapper.convertValue(
                replay.data(), DepartureResultDto.class).departedAt());

        String capKey = "fixed-capacity";
        priorityService.setCapacity(new CapacitySetRequest(3, 3, 4, capKey));
        MutationResponse capReplay = priorityService.setCapacity(
                new CapacitySetRequest(3, 3, 4, capKey));
        assertTrue(capReplay.replayed());
    }

    // ============================ 并发 ============================

    @Test
    @DisplayName("并发：紧急抢占与起飞登记按提交顺序裁决，恰好一方生效")
    void concurrentEmergencyAndDepartureAreAdjudicatedByCommitOrder() throws Exception {
        for (int iteration = 0; iteration < 8; iteration++) {
            final int round = iteration;
            String nId = "vn" + round;
            String eId = "ve" + round;
            route(nId);
            route(eId);
            PriorityReviewResultDto victim = normal(nId, round * 7_000, 0, 0);

            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Object> emergencyFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return submit(eId, "EMERGENCY", "EVT-C" + round,
                                seg(round * 7_000, 0, 0), "req-emergency-" + round);
                    } catch (RuntimeException ex) {
                        return ex;
                    }
                });
                Future<Object> departureFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return priorityService.registerDeparture(
                                new DepartureRequest(victim.clearanceId(),
                                        "req-departure-" + round));
                    } catch (RuntimeException ex) {
                        return ex;
                    }
                });

                Object emergencyResult = emergencyFuture.get(20, TimeUnit.SECONDS);
                Object departureResult = departureFuture.get(20, TimeUnit.SECONDS);
                boolean emergencyWon = emergencyResult instanceof PriorityReviewResultDto;
                boolean departureWon = departureResult instanceof MutationResponse;
                assertTrue(emergencyWon ^ departureWon,
                        "第 " + round + " 轮必须恰好一方成功：emergencyWon=" + emergencyWon
                                + ", departureWon=" + departureWon);

                ClearancePo finalVictim = priorityService.getClearance(victim.clearanceId());
                if (emergencyWon) {
                    assertEquals("DISPLACED", finalVictim.status());
                    assertTrue(departureResult instanceof ApiException);
                } else {
                    assertEquals("DEPARTED", finalVictim.status());
                    CapacityConflictException ex = (CapacityConflictException) emergencyResult;
                    assertEquals("DEPARTED", ex.blockingRoutes().get(0).reason());
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("并发同键紧急提交：恰好一次业务生效，其余重放同一抢占快照")
    void concurrentSameEmergencyKeyPlaysBackOneOutcome() throws Exception {
        route("n1");
        normal("n1", 0, 0, 0);
        int n = 5;
        for (int i = 1; i <= n; i++) {
            route("ce" + i);
        }
        String key = "concurrent-emergency-key";
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                String routeId = "ce" + i;
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        // 相同指纹内容才能同键重放：routeId 不同会导致参数哈希不同，
                        // 因此这里全部提交同一条紧急航线 ce1 的同键请求
                        return priorityService.submitPriorityReview(new PriorityReviewRequest(
                                "ce1", 1, 0L, "EMERGENCY", "EVT-CONCURRENT",
                                seg(0, 0, 0), key));
                    } catch (RuntimeException ex) {
                        return ex;
                    }
                }));
            }
            int firstCount = 0;
            String preemptionId = null;
            for (Future<Object> f : futures) {
                Object result = f.get(20, TimeUnit.SECONDS);
                assertTrue(result instanceof MutationResponse, "同键并发不应抛错: " + result);
                MutationResponse resp = (MutationResponse) result;
                PriorityReviewResultDto dto = objectMapper.convertValue(
                        resp.data(), PriorityReviewResultDto.class);
                if (!resp.replayed()) {
                    firstCount++;
                    preemptionId = dto.preemptionId();
                } else {
                    assertEquals(preemptionId == null ? dto.preemptionId() : preemptionId,
                            dto.preemptionId());
                }
            }
            assertEquals(1, firstCount, "仅一次紧急抢占真正生效");
            assertEquals(1, count("SELECT COUNT(*) FROM preemption"));
            assertEquals(1, count("SELECT COUNT(*) FROM clearance WHERE route_id = 'ce1'"));
            assertEquals(1, count("SELECT COUNT(*) FROM preemption_item"));
            assertEquals("DISPLACED",
                    priorityService.getDisplacedClearances("n1").get(0).status());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发两架 NORMAL 抢同一容量桶：只允许一架落地")
    void concurrentNormalSubmissionsForSameBucketOnlyOneWins() throws Exception {
        route("cn1");
        route("cn2");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (String routeId : List.of("cn1", "cn2")) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return submit(routeId, "NORMAL", null, seg(0, 0, 3),
                                "req-concurrent-normal-" + routeId);
                    } catch (RuntimeException ex) {
                        return ex;
                    }
                }));
            }
            int approved = 0;
            int rejected = 0;
            for (Future<Object> f : futures) {
                Object result = f.get(20, TimeUnit.SECONDS);
                if (result instanceof PriorityReviewResultDto) {
                    approved++;
                } else {
                    rejected++;
                    assertTrue(result instanceof CapacityConflictException);
                }
            }
            assertEquals(1, approved);
            assertEquals(1, rejected);
            assertEquals(1, priorityService.getCapacityBucket(0, 0, bucketOf(3)).occupied());
        } finally {
            pool.shutdownNow();
        }
    }
}
