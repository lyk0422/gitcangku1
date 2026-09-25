package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.BucketDto;
import com.example.starter.api.dto.BucketMarginDto;
import com.example.starter.api.dto.CapacityConfigRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.RouteActivateRequest;
import com.example.starter.api.dto.RouteActivateResult;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RouteDeactivateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.TransferActivateRequest;
import com.example.starter.api.dto.TransferEvidenceDto;
import com.example.starter.api.dto.TransferItemDto;
import com.example.starter.api.dto.TransferPreviewRequest;
import com.example.starter.api.dto.TransferPreviewResponse;
import com.example.starter.api.dto.TransferResultDto;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.service.AirspaceReviewService;
import com.example.starter.service.CapacityTransferService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 航路时空桶容量账本与闭环原子转配的 H2 数据库测试（MODE=MySQL）。
 * 覆盖闭环转配、路径/禁飞/容量失败、整体回滚、幂等与并发边界。
 */
@SpringBootTest
class CapacityTransferServiceH2Test {

    private static final long T0 = 0L;
    private static final long T1 = 900000L;

    @Autowired
    private CapacityTransferService service;
    @Autowired
    private AirspaceReviewService airspaceService;
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
        jdbc.update("DELETE FROM capacity_transfer_bucket");
        jdbc.update("DELETE FROM capacity_transfer_route");
        jdbc.update("DELETE FROM capacity_transfer_item");
        jdbc.update("DELETE FROM capacity_transfer");
        jdbc.update("DELETE FROM route_occupancy");
        jdbc.update("DELETE FROM capacity_config");
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

    private long airspaceVersion() {
        return jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id = 1", Long.class);
    }

    private void config(String cellId, long bucketStart, int maxFlights) {
        service.configureCapacity(new CapacityConfigRequest(
                cellId, bucketStart, maxFlights, "req-" + rid("cfg")));
    }

    private void reviewClear(String routeId, int version) {
        airspaceService.review(new ReviewRequest(
                routeId, version, airspaceVersion(), "req-" + rid("review")));
    }

    private RouteActivateResult activate(String routeId, int version, long departure) {
        MutationResponse resp = service.activateRoute(new RouteActivateRequest(
                routeId, version, departure, "req-" + rid("act")));
        return objectMapper.convertValue(resp.data(), RouteActivateResult.class);
    }

    /** 建航线、审查通过并激活占用（初始版本 1）。 */
    private void newActiveRoute(String routeId, List<RoutePointDto> points, long departure) {
        airspaceService.createRoute(new RouteCreateRequest(routeId, points, "req-" + rid("route")));
        reviewClear(routeId, 1);
        activate(routeId, 1, departure);
    }

    private static TransferItemDto item(String routeId, int version,
                                        String srcCell, long srcBucket,
                                        String tgtCell, long tgtBucket) {
        return new TransferItemDto(routeId, version,
                new BucketDto(srcCell, srcBucket), new BucketDto(tgtCell, tgtBucket));
    }

    private MutationResponse transfer(String transferKey, String requestId,
                                      List<TransferItemDto> items) {
        return service.activateTransfer(new TransferActivateRequest(transferKey, items, requestId));
    }

    private TransferResultDto transferData(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), TransferResultDto.class);
    }

    private int occupancyCount(String routeId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM route_occupancy WHERE route_id = ?", Integer.class, routeId);
    }

    private int routeVersion(String routeId) {
        return jdbc.queryForObject(
                "SELECT version FROM route WHERE route_id = ?", Integer.class, routeId);
    }

    // ============================ 容量配置与航线激活 ============================

    @Test
    void configureAndActivateOccupiesBucketsAndEnforcesCapacity() {
        config("C0_0", T0, 1);
        // 非法单元标识 / 未对齐时间桶 → 400
        ApiException badCell = assertThrows(ApiException.class, () -> service.configureCapacity(
                new CapacityConfigRequest("X0_0", T0, 1, "req-" + rid("cfg"))));
        assertEquals(HttpStatus.BAD_REQUEST, badCell.status());
        ApiException unaligned = assertThrows(ApiException.class, () -> service.configureCapacity(
                new CapacityConfigRequest("C0_0", 1000L, 1, "req-" + rid("cfg"))));
        assertEquals(HttpStatus.BAD_REQUEST, unaligned.status());

        // 航线 (100,10)->(100,10) 退化不允许，用 (0,10)->(100,10)：穿越序列 [C0_0]
        newActiveRoute("r1", pts(0, 10, 100, 10), T0);
        assertEquals(1, occupancyCount("r1"));
        assertEquals("C0_0", jdbc.queryForObject(
                "SELECT cell_id FROM route_occupancy WHERE route_id = 'r1'", String.class));

        // 第二条航线占用同一桶：容量 1 已满 → 422
        airspaceService.createRoute(
                new RouteCreateRequest("r2", pts(0, 20, 100, 20), "req-" + rid("route")));
        reviewClear("r2", 1);
        ApiException full = assertThrows(ApiException.class,
                () -> activate("r2", 1, T0));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, full.status());
        assertEquals("CAPACITY_EXCEEDED", full.code());

        // 停用 r1 后 r2 可激活
        service.deactivateRoute(new RouteDeactivateRequest("r1", "req-" + rid("deact")));
        assertEquals(0, occupancyCount("r1"));
        activate("r2", 1, T0);
        assertEquals(1, occupancyCount("r2"));
    }

    @Test
    void activateRequiresCurrentClearReviewAndRejectsBadState() {
        airspaceService.createRoute(
                new RouteCreateRequest("ra", pts(0, 10, 100, 10), "req-" + rid("route")));
        // 未审查 → 422 REVIEW_NOT_CURRENT
        ApiException noReview = assertThrows(ApiException.class,
                () -> activate("ra", 1, T0));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, noReview.status());
        assertEquals("REVIEW_NOT_CURRENT", noReview.code());

        // 未对齐起飞时刻 → 400
        reviewClear("ra", 1);
        ApiException badDeparture = assertThrows(ApiException.class, () -> service.activateRoute(
                new RouteActivateRequest("ra", 1, 123L, "req-" + rid("act"))));
        assertEquals(HttpStatus.BAD_REQUEST, badDeparture.status());

        // 版本不匹配 → 409；不存在 → 404
        ApiException wrongVersion = assertThrows(ApiException.class,
                () -> activate("ra", 9, T0));
        assertEquals(HttpStatus.CONFLICT, wrongVersion.status());
        ApiException missing = assertThrows(ApiException.class,
                () -> activate("ghost", 1, T0));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        // 激活成功后重复激活 → 409；重复停用 → 409
        activate("ra", 1, T0);
        ApiException twice = assertThrows(ApiException.class,
                () -> activate("ra", 1, T0));
        assertEquals(HttpStatus.CONFLICT, twice.status());
        assertEquals("ROUTE_ALREADY_ACTIVE", twice.code());
        service.deactivateRoute(new RouteDeactivateRequest("ra", "req-" + rid("deact")));
        ApiException deactTwice = assertThrows(ApiException.class, () -> service
                .deactivateRoute(new RouteDeactivateRequest("ra", "req-" + rid("deact2"))));
        assertEquals(HttpStatus.CONFLICT, deactTwice.status());
    }

    @Test
    void routeReplaceDropsOccupancy() {
        newActiveRoute("rr", pts(0, 10, 100, 10), T0);
        assertEquals(1, occupancyCount("rr"));
        // 航线修订后旧版本占用同事务移除
        airspaceService.replaceRoute(new RouteReplaceRequest(
                "rr", 1, pts(0, 0, 100, 0), "req-" + rid("replace")));
        assertEquals(0, occupancyCount("rr"));
        assertEquals(2, routeVersion("rr"));
    }

    // ============================ 闭环转配主流程 ============================

    @Test
    void closedLoopSwapSucceedsWhereSequentialApplicationWouldFail() {
        // A: [C0_0, C0_1]，B: [C1_0, C1_1]，两桶容量均为 1 且各占其一
        config("C0_0", T0, 1);
        config("C1_0", T0, 1);
        newActiveRoute("A", pts(100, 100, 100, 1100), T0);
        newActiveRoute("B", pts(1100, 100, 1100, 1100), T0);

        List<TransferItemDto> items = List.of(
                item("A", 1, "C0_0", T0, "C1_0", T0),
                item("B", 1, "C1_0", T0, "C0_0", T0));

        // 预览：只读，完整后态下闭环互换不超限
        TransferPreviewResponse preview = service.previewTransfer(new TransferPreviewRequest(items));
        assertTrue(preview.valid(), "闭环互换在完整后态下必须合法: " + preview.violations());
        assertEquals(2, preview.routes().size());
        assertEquals(1, preview.routes().get(0).currentVersion());
        assertEquals(2, preview.buckets().size());
        BucketMarginDto first = preview.buckets().get(0);
        assertEquals("C0_0", first.cellId());
        assertEquals(1, first.beforeCount());
        assertEquals(1, first.afterCount());
        assertEquals(0, first.margin());
        // 预览只读：不产生任何占用/版本变化
        assertEquals(1, routeVersion("A"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity_transfer", Integer.class));

        // 激活：逐项释放/占用会误判超限，完整后态必须成功
        MutationResponse resp = transfer("tk-swap", "req-" + rid("transfer"), items);
        assertFalse(resp.replayed());
        TransferResultDto result = transferData(resp);
        assertEquals("tk-swap", result.transferKey());
        assertEquals(2, result.routes().size());
        assertEquals(1, result.routes().get(0).oldVersion());
        assertEquals(2, result.routes().get(0).newVersion());
        assertNotNull(result.routes().get(0).reviewId());

        // 占用一次性替换且版本增版
        assertEquals(2, routeVersion("A"));
        assertEquals(2, routeVersion("B"));
        List<Map<String, Object>> occA = jdbc.queryForList(
                "SELECT cell_id, bucket_start, route_version FROM route_occupancy "
                        + "WHERE route_id = 'A' ORDER BY seq");
        assertEquals(2, occA.size());
        assertEquals("C1_0", occA.get(0).get("cell_id"));
        assertEquals(2, ((Number) occA.get(0).get("route_version")).intValue());
        List<Map<String, Object>> occB = jdbc.queryForList(
                "SELECT cell_id FROM route_occupancy WHERE route_id = 'B' ORDER BY seq");
        assertEquals("C0_0", occB.get(0).get("cell_id"));

        // 证据冻结：按桶、航线稳定排序
        TransferEvidenceDto evidence = service.getTransferEvidence("tk-swap");
        assertEquals("tk-swap", evidence.transferKey());
        assertEquals(2, evidence.items().size());
        assertEquals(List.of("A", "B"),
                evidence.routes().stream().map(r -> r.routeId()).toList());
        assertEquals(List.of("C1_0", "C0_1"),
                evidence.routes().get(0).afterPath().stream().map(BucketDto::cellId).toList());
        assertEquals(List.of("C0_0", "C1_0"),
                evidence.buckets().stream().map(BucketMarginDto::cellId).toList());
        assertEquals(1, evidence.buckets().get(0).beforeCount());
        assertEquals(1, evidence.buckets().get(0).afterCount());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity_transfer", Integer.class));
    }

    @Test
    void threeRouteCycleTransferIsAtomic() {
        // A→B→C→A 闭环：A: [C0_0,C1_0]，B: [C1_0,C2_0]，C: [C2_0,C1_0]
        config("C0_0", T0, 1);
        config("C1_0", T0, 1);
        config("C2_0", T0, 1);
        newActiveRoute("A", pts(100, 100, 1100, 100), T0);
        newActiveRoute("B", pts(1100, 100, 2100, 100), T0);
        newActiveRoute("C", pts(2100, 100, 1100, 100), T0);

        MutationResponse resp = transfer("tk-cycle", "req-" + rid("transfer"), List.of(
                item("A", 1, "C0_0", T0, "C1_0", T0),
                item("B", 1, "C1_0", T0, "C2_0", T0),
                item("C", 1, "C2_0", T0, "C0_0", T0)));
        assertFalse(resp.replayed());
        assertEquals(3, transferData(resp).routes().size());
        // 三个桶占用数不变，仅归属轮换
        for (String cell : List.of("C0_0", "C1_0", "C2_0")) {
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM route_occupancy WHERE cell_id = ? AND bucket_start = ?",
                    Integer.class, cell, T0));
        }
        assertEquals("A", jdbc.queryForObject(
                "SELECT route_id FROM route_occupancy WHERE cell_id = 'C1_0' AND bucket_start = 0",
                String.class));
        assertEquals("C", jdbc.queryForObject(
                "SELECT route_id FROM route_occupancy WHERE cell_id = 'C0_0' AND bucket_start = 0",
                String.class));
    }

    // ============================ 失败分支 ============================

    @Test
    void versionConflictAndRouteNotActiveAreRejected() {
        newActiveRoute("A", pts(100, 100, 100, 1100), T0);
        newActiveRoute("B", pts(1100, 100, 1100, 1100), T0);

        // 版本变化 → 409
        ApiException version = assertThrows(ApiException.class, () -> transfer("tk-v1",
                "req-" + rid("transfer"), List.of(
                        item("A", 9, "C0_0", T0, "C1_0", T0),
                        item("B", 1, "C1_0", T0, "C0_0", T0))));
        assertEquals(HttpStatus.CONFLICT, version.status());
        assertEquals("VERSION_CONFLICT", version.code());

        // 航线停用 → 422
        service.deactivateRoute(new RouteDeactivateRequest("B", "req-" + rid("deact")));
        ApiException inactive = assertThrows(ApiException.class, () -> transfer("tk-v2",
                "req-" + rid("transfer"), List.of(
                        item("A", 1, "C0_0", T0, "C1_0", T0),
                        item("B", 1, "C1_0", T0, "C0_0", T0))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, inactive.status());
        assertEquals("ROUTE_NOT_ACTIVE", inactive.code());
    }

    @Test
    void discontinuousTargetIsRejected() {
        newActiveRoute("A", pts(100, 100, 100, 1100), T0);
        newActiveRoute("B", pts(1100, 100, 1100, 1100), T0);
        // A 的目标单元 C5_0 与后续单元 C0_1 不相邻 → 422
        ApiException ex = assertThrows(ApiException.class, () -> transfer("tk-path",
                "req-" + rid("transfer"), List.of(
                        item("A", 1, "C0_0", T0, "C5_0", T0),
                        item("B", 1, "C1_0", T0, "C0_0", T0))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("PATH_NOT_CONTINUOUS", ex.code());

        // 目标桶时间与源桶不一致 → 422
        ApiException time = assertThrows(ApiException.class, () -> transfer("tk-time",
                "req-" + rid("transfer"), List.of(
                        item("A", 1, "C0_0", T0, "C1_0", T1),
                        item("B", 1, "C1_0", T0, "C0_0", T0))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, time.status());
        assertEquals("PATH_NOT_CONTINUOUS", time.code());
    }

    @Test
    void noFlyConflictIsRejected() {
        newActiveRoute("D", pts(100, 100, 1100, 100), T0);
        newActiveRoute("E", pts(5100, 100, 6100, 100), T0);
        // 禁飞区覆盖目标单元 C2_0（审查完成后再建区，模拟空域变化）
        airspaceService.createZone(new ZoneCreateRequest(
                "z-c2", 2000, 0, 3000, 1000, "req-" + rid("zone")));
        ApiException ex = assertThrows(ApiException.class, () -> transfer("tk-nofly",
                "req-" + rid("transfer"), List.of(
                        item("D", 1, "C0_0", T0, "C2_0", T0),
                        item("E", 1, "C5_0", T0, "C6_0", T0))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("NO_FLY_CONFLICT", ex.code());
        // 失败整体回滚：占用与版本不变
        assertEquals(1, routeVersion("D"));
        assertEquals("C0_0", jdbc.queryForObject(
                "SELECT cell_id FROM route_occupancy WHERE route_id = 'D' AND seq = 0",
                String.class));
    }

    @Test
    void capacityExceededCountsNonParticipantOccupancy() {
        // 未参与航线 F 占用 (C2_0,T0)，容量 1；A 转入该桶必然超限
        config("C2_0", T0, 1);
        newActiveRoute("F", pts(2100, 100, 3100, 100), T0);
        newActiveRoute("A", pts(100, 100, 1100, 100), T0);
        newActiveRoute("B", pts(1100, 100, 1100, 1100), T0);

        List<TransferItemDto> items = List.of(
                item("A", 1, "C0_0", T0, "C2_0", T0),
                item("B", 1, "C1_0", T0, "C0_0", T0));
        // 预览报告违规与余量（未参与航线占用计入）
        TransferPreviewResponse preview = service.previewTransfer(new TransferPreviewRequest(items));
        assertFalse(preview.valid());
        assertTrue(preview.violations().stream()
                .anyMatch(v -> v.code().equals("CAPACITY_EXCEEDED")));
        BucketMarginDto exceeded = preview.buckets().stream()
                .filter(b -> b.cellId().equals("C2_0")).findFirst().orElseThrow();
        assertEquals(1, exceeded.beforeCount());
        assertEquals(2, exceeded.afterCount());
        assertEquals(-1, exceeded.margin());

        ApiException ex = assertThrows(ApiException.class,
                () -> transfer("tk-cap", "req-" + rid("transfer"), items));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("CAPACITY_EXCEEDED", ex.code());
        // 未参与航线占用不受影响（F 的穿越序列占 2 行）
        assertEquals(2, occupancyCount("F"));
        assertEquals("F", jdbc.queryForObject(
                "SELECT route_id FROM route_occupancy WHERE cell_id = 'C2_0' AND bucket_start = 0",
                String.class));
    }

    @Test
    void sourceNotOccupiedAndIncompleteSetAreRejected() {
        newActiveRoute("A", pts(100, 100, 100, 1100), T0);
        newActiveRoute("B", pts(1100, 100, 1100, 1100), T0);
        // 源桶不在该航线占用集合中（集合遗漏）→ 422
        ApiException missing = assertThrows(ApiException.class, () -> transfer("tk-src",
                "req-" + rid("transfer"), List.of(
                        item("A", 1, "C9_9", T0, "C1_0", T0),
                        item("B", 1, "C1_0", T0, "C0_0", T0))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, missing.status());
        assertEquals("SOURCE_BUCKET_NOT_FOUND", missing.code());

        // 参与航线不足 2 条 → 422
        ApiException incomplete = assertThrows(ApiException.class, () -> transfer("tk-set",
                "req-" + rid("transfer"), List.of(
                        item("A", 1, "C0_0", T0, "C0_1", T0),
                        item("A", 1, "C0_1", T1, "C0_0", T1))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, incomplete.status());
        assertEquals("TRANSFER_SET_INCOMPLETE", incomplete.code());
    }

    // ============================ 整体回滚 ============================

    @Test
    void failedTransferRollsBackEverything() {
        config("C2_0", T0, 1);
        newActiveRoute("F", pts(2100, 100, 3100, 100), T0);
        newActiveRoute("A", pts(100, 100, 1100, 100), T0);
        newActiveRoute("B", pts(1100, 100, 1100, 1100), T0);
        String requestId = "req-rollback-1";

        // 第一项合法、第二项容量超限：任一违规则整体回滚
        ApiException ex = assertThrows(ApiException.class, () -> transfer("tk-rb", requestId,
                List.of(
                        item("B", 1, "C1_0", T0, "C0_0", T0),
                        item("A", 1, "C0_0", T0, "C2_0", T0))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());

        // 不产生部分转配：占用、版本、证据、去重键全部保持原状
        assertEquals(1, routeVersion("A"));
        assertEquals(1, routeVersion("B"));
        assertEquals("C0_0", jdbc.queryForObject(
                "SELECT cell_id FROM route_occupancy WHERE route_id = 'A' AND seq = 0",
                String.class));
        assertEquals("C1_0", jdbc.queryForObject(
                "SELECT cell_id FROM route_occupancy WHERE route_id = 'B' AND seq = 0",
                String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity_transfer", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity_transfer_item", Integer.class));
        assertNull(jdbc.query(
                        "SELECT request_id FROM request_dedup WHERE request_id = ?",
                        (rs, n) -> rs.getString(1), requestId)
                .stream().findFirst().orElse(null));

        // 失败不占键：修正参数后同 requestId 可成功
        MutationResponse ok = transfer("tk-rb2", requestId, List.of(
                item("A", 1, "C0_0", T0, "C1_0", T0),
                item("B", 1, "C1_0", T0, "C0_0", T0)));
        assertFalse(ok.replayed());
        assertEquals(2, routeVersion("A"));
    }

    // ============================ 幂等 ============================

    @Test
    void transferIdempotencyReplaysSnapshotAndItemOrderIsIrrelevant() {
        newActiveRoute("A", pts(100, 100, 100, 1100), T0);
        newActiveRoute("B", pts(1100, 100, 1100, 1100), T0);
        String requestId = "req-idem-transfer";

        MutationResponse first = transfer("tk-idem", requestId, List.of(
                item("A", 1, "C0_0", T0, "C1_0", T0),
                item("B", 1, "C1_0", T0, "C0_0", T0)));
        assertFalse(first.replayed());

        // 同键同参（转配项换序视为同参）→ 重放首次快照
        MutationResponse replay = transfer("tk-idem", requestId, List.of(
                item("B", 1, "C1_0", T0, "C0_0", T0),
                item("A", 1, "C0_0", T0, "C1_0", T0)));
        assertTrue(replay.replayed());
        assertEquals("tk-idem", transferData(replay).transferKey());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity_transfer", Integer.class));
        assertEquals(2, routeVersion("A"));

        // 同键异参 → 409
        ApiException mismatch = assertThrows(ApiException.class, () -> transfer("tk-idem",
                requestId, List.of(
                        item("A", 1, "C0_0", T0, "C0_1", T0),
                        item("B", 1, "C1_0", T0, "C0_0", T0))));
        assertEquals(HttpStatus.CONFLICT, mismatch.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", mismatch.code());

        // transferKey 唯一：不同 requestId 复用同一 transferKey → 409
        ApiException dupKey = assertThrows(ApiException.class, () -> transfer("tk-idem",
                "req-" + rid("transfer"), List.of(
                        item("A", 2, "C1_0", T0, "C0_0", T0),
                        item("B", 2, "C0_0", T0, "C1_0", T0))));
        assertEquals(HttpStatus.CONFLICT, dupKey.status());
        assertEquals("TRANSFER_KEY_EXISTS", dupKey.code());
    }

    @Test
    void evidenceQueryIsReadOnlyAndNotFoundIs404() {
        ApiException missing = assertThrows(ApiException.class,
                () -> service.getTransferEvidence("tk-ghost"));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        newActiveRoute("A", pts(100, 100, 100, 1100), T0);
        newActiveRoute("B", pts(1100, 100, 1100, 1100), T0);
        transfer("tk-ev", "req-" + rid("transfer"), List.of(
                item("A", 1, "C0_0", T0, "C1_0", T0),
                item("B", 1, "C1_0", T0, "C0_0", T0)));

        TransferEvidenceDto before = service.getTransferEvidence("tk-ev");
        TransferEvidenceDto again = service.getTransferEvidence("tk-ev");
        assertEquals(before, again);
        // 只读：证据与占用不随查询变化
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity_transfer", Integer.class));
        assertEquals(2, occupancyCount("A"));
    }

    // ============================ 并发 ============================

    @Test
    void concurrentTransfersOnSameRoutesExactlyOneWins() throws Exception {
        newActiveRoute("A", pts(100, 100, 100, 1100), T0);
        newActiveRoute("B", pts(1100, 100, 1100, 1100), T0);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Object> t1 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return transfer("tk-race-1", "req-race-1", List.of(
                            item("A", 1, "C0_0", T0, "C1_0", T0),
                            item("B", 1, "C1_0", T0, "C0_0", T0)));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            Future<Object> t2 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return transfer("tk-race-2", "req-race-2", List.of(
                            item("B", 1, "C1_0", T0, "C0_0", T0),
                            item("A", 1, "C0_0", T0, "C1_0", T0)));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            Object r1 = t1.get(20, TimeUnit.SECONDS);
            Object r2 = t2.get(20, TimeUnit.SECONDS);
            int successes = (r1 instanceof MutationResponse ? 1 : 0)
                    + (r2 instanceof MutationResponse ? 1 : 0);
            assertEquals(1, successes, "并发转配按提交顺序只能成功一个");
            Object loser = r1 instanceof MutationResponse ? r2 : r1;
            assertTrue(loser instanceof ApiException);
            assertEquals(HttpStatus.CONFLICT, ((ApiException) loser).status());
            // 只能观察到完整状态：版本一致推进一次，占用与版本对应
            assertEquals(2, routeVersion("A"));
            assertEquals(2, routeVersion("B"));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM capacity_transfer", Integer.class));
            // 两条航线各 2 行占用，全部推进到版本 2
            assertEquals(4, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM route_occupancy WHERE route_version = 2",
                    Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentTransferAndRouteReplaceObserveOnlyCompleteStates() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 6; i++) {
                String a = "A" + i;
                String b = "B" + i;
                newActiveRoute(a, pts(100, 100, 100, 1100), T0);
                newActiveRoute(b, pts(1100, 100, 1100, 1100), T0);
                CyclicBarrier barrier = new CyclicBarrier(2);
                String routeA = a;
                String routeB = b;
                Future<Object> transferFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return transfer("tk-x-" + routeA, "req-x-" + routeA, List.of(
                                item(routeA, 1, "C0_0", T0, "C1_0", T0),
                                item(routeB, 1, "C1_0", T0, "C0_0", T0)));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> replaceFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return airspaceService.replaceRoute(new RouteReplaceRequest(
                                routeA, 1, pts(100, 100, 2100, 100), "req-rp-" + routeA));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Object transferResult = transferFuture.get(20, TimeUnit.SECONDS);
                Object replaceResult = replaceFuture.get(20, TimeUnit.SECONDS);
                int transferOk = transferResult instanceof MutationResponse ? 1 : 0;
                int replaceOk = replaceResult instanceof MutationResponse ? 1 : 0;
                assertEquals(1, transferOk + replaceOk,
                        "转配与航线修订互斥，恰好一个成功");
                // 无论谁先提交，版本只推进一次且占用状态完整
                assertEquals(2, routeVersion(routeA));
                if (transferOk == 1) {
                    // 转配胜：占用属于版本 2
                    assertEquals(2, jdbc.queryForObject(
                            "SELECT COUNT(*) FROM route_occupancy "
                                    + "WHERE route_id = ? AND route_version = 2",
                            Integer.class, routeA));
                } else {
                    // 修订胜：旧占用已移除，转配 409
                    assertEquals(0, occupancyCount(routeA));
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) transferResult).status());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentTransferAndCapacityAdjustAreSerialized() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 6; i++) {
                // 每轮独立数据，避免上一轮容量调整影响本轮激活
                cleanup();
                String a = "CA" + i;
                String b = "CB" + i;
                newActiveRoute(a, pts(100, 100, 100, 1100), T0);
                newActiveRoute(b, pts(1100, 100, 1100, 1100), T0);
                CyclicBarrier barrier = new CyclicBarrier(2);
                String routeA = a;
                String routeB = b;
                // 转配：A、B 互换 T0 桶
                Future<Object> transferFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return transfer("tk-c-" + routeA, "req-c-" + routeA, List.of(
                                item(routeA, 1, "C0_0", T0, "C1_0", T0),
                                item(routeB, 1, "C1_0", T0, "C0_0", T0)));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                // 容量调整：把 (C1_0,T0) 上限调为 0；若先提交，转配必因超限失败
                Future<Object> configFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.configureCapacity(new CapacityConfigRequest(
                                "C1_0", T0, 0, "req-cfg-" + routeA));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Object transferResult = transferFuture.get(20, TimeUnit.SECONDS);
                Object configResult = configFuture.get(20, TimeUnit.SECONDS);
                assertTrue(configResult instanceof MutationResponse, "容量调整必须成功");
                if (transferResult instanceof MutationResponse) {
                    // 转配先提交：占用已完整替换，调整只影响后续
                    assertEquals(2, routeVersion(routeA));
                    assertEquals("C1_0", jdbc.queryForObject(
                            "SELECT cell_id FROM route_occupancy WHERE route_id = ? AND seq = 0",
                            String.class, routeA));
                } else {
                    // 调整先提交：转配必须 422 超限，不能产生部分转配
                    ApiException ex = (ApiException) transferResult;
                    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
                    assertEquals("CAPACITY_EXCEEDED", ex.code());
                    assertEquals(1, routeVersion(routeA));
                    assertEquals(0, jdbc.queryForObject(
                            "SELECT COUNT(*) FROM capacity_transfer", Integer.class));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
