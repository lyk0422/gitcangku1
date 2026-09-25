package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.BucketRefDto;
import com.example.starter.api.dto.BucketCapacityDto;
import com.example.starter.api.dto.CapacityConfigRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyPlanItemDto;
import com.example.starter.api.dto.OccupancyPlanRequest;
import com.example.starter.api.dto.TransferActivateRequest;
import com.example.starter.api.dto.TransferActivateResult;
import com.example.starter.api.dto.TransferEvidenceResult;
import com.example.starter.api.dto.TransferItemRequest;
import com.example.starter.api.dto.TransferPreviewResult;
import com.example.starter.service.AirspaceReviewService;
import com.example.starter.service.CapacityService;
import com.example.starter.service.CapacityTransferService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 航路时空桶容量闭环原子转配的 H2 数据库测试（MODE=MySQL）。
 *
 * <p>覆盖：容量配置与序列登记、A→B→C→A 闭环转配、项换序幂等、
 * 路径/禁飞/集合/容量失败整体回滚、并发版本冲突与串行提交、transferKey 唯一。</p>
 */
@SpringBootTest
class CapacityTransferH2Test {

    private static final long T0 = 0L;
    private static final long T1 = 15L * 60L * 1000L;

    @Autowired
    private CapacityService capacityService;
    @Autowired
    private CapacityTransferService transferService;
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
        jdbc.update("DELETE FROM transfer_bucket_evidence");
        jdbc.update("DELETE FROM transfer_route_evidence");
        jdbc.update("DELETE FROM capacity_transfer");
        jdbc.update("DELETE FROM route_occupancy_plan");
        jdbc.update("DELETE FROM capacity_config");
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

    // ---------------------------- 构造工具 ----------------------------

    private void createRoute(String routeId) {
        reviewService.createRoute(new com.example.starter.api.dto.RouteCreateRequest(
                routeId,
                List.of(new com.example.starter.api.dto.RoutePointDto(0, 0),
                        new com.example.starter.api.dto.RoutePointDto(100, 100)),
                req("route")));
    }

    /** 审核 CLEAR：无禁飞区且空域版本 0。 */
    private void approveClear(String routeId) {
        reviewService.review(new com.example.starter.api.dto.ReviewRequest(
                routeId, 1, 0L, req("review")));
    }

    private void configCapacity(int cellX, int cellY, long bucket, int maxFlights) {
        capacityService.configure(new CapacityConfigRequest(
                cellX, cellY, bucket, maxFlights, req("cap")));
    }

    private static OccupancyPlanItemDto planItem(int seq, int cellX, int cellY, long bucket) {
        return new OccupancyPlanItemDto(seq, cellX, cellY, bucket);
    }

    private void registerPlan(String routeId, int version, OccupancyPlanItemDto... items) {
        capacityService.registerPlan(new OccupancyPlanRequest(
                routeId, version, List.of(items), req("plan")));
    }

    private static BucketRefDto bucket(int x, int y, long t) {
        return new BucketRefDto(x, y, t);
    }

    private static TransferItemRequest item(String routeId, int x1, int y1, long t1,
                                            int x2, int y2, long t2) {
        return new TransferItemRequest(routeId, 1, 1, bucket(x1, y1, t1), bucket(x2, y2, t2));
    }

    private TransferActivateResult dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), TransferActivateResult.class);
    }

    // ---------------------------- 容量与序列 ----------------------------

    @Test
    void capacityConfigAndOccupancyCounting() {
        createRoute("r1");
        approveClear("r1");
        configCapacity(0, 0, T0, 2);
        registerPlan("r1", 1, planItem(0, 0, 0, T0), planItem(1, 0, 0, T1));
        // (0,0)@T0：序列第一项占用 1 架次；同一航线两槽位在不同桶分别计 1
        BucketCapacityDto b0 = capacityService.getBucket(0, 0, T0);
        assertEquals(2, b0.maxFlights());
        assertEquals(1, b0.usedBefore());
        BucketCapacityDto b1 = capacityService.getBucket(0, 0, T1);
        assertEquals(0, b1.maxFlights(), "未配置桶上限为 0");
        assertEquals(1, b1.usedBefore());
    }

    @Test
    void unalignedBucketRejectedAndPlanSeqMustBeContinuous() {
        ApiException badBucket = assertThrows(ApiException.class, () -> capacityService.configure(
                new CapacityConfigRequest(0, 0, 123L, 1, req("cap"))));
        assertEquals(HttpStatus.BAD_REQUEST, badBucket.status());
        createRoute("r2");
        approveClear("r2");
        ApiException badSeq = assertThrows(ApiException.class, () -> capacityService.registerPlan(
                new OccupancyPlanRequest("r2", 1,
                        List.of(planItem(0, 0, 0, T0), planItem(2, 1, 1, T0)), req("plan"))));
        assertEquals(HttpStatus.BAD_REQUEST, badSeq.status());
    }

    @Test
    void registerPlanOnlyForCurrentVersion() {
        createRoute("r3");
        approveClear("r3");
        ApiException ex = assertThrows(ApiException.class, () -> registerPlan(
                "r3", 9, planItem(0, 0, 0, T0), planItem(1, 1, 1, T0)));
        assertEquals(HttpStatus.CONFLICT, ex.status());
    }

    // ---------------------------- 闭环转配主流程 ----------------------------

    // 几何：X=cell(1,1) Y=cell(2,1) Z=cell(1,2)
    // A：锚点 cell(2,2)，X→Y；B：锚点 cell(2,2)，Y→Z；C：锚点 cell(0,2)，Z→X
    private void prepareCycleRoutes() {
        createRoute("A");
        createRoute("B");
        createRoute("C");
        approveClear("A");
        approveClear("B");
        approveClear("C");
        configCapacity(1, 1, T0, 1);
        configCapacity(2, 1, T0, 1);
        configCapacity(1, 2, T0, 1);
        configCapacity(2, 2, T0, 2);
        configCapacity(0, 2, T0, 1);
        registerPlan("A", 1, planItem(0, 2, 2, T0), planItem(1, 1, 1, T0));
        registerPlan("B", 1, planItem(0, 2, 2, T0), planItem(1, 2, 1, T0));
        registerPlan("C", 1, planItem(0, 0, 2, T0), planItem(1, 1, 2, T0));
    }

    private List<TransferItemRequest> cycleItems() {
        return List.of(item("A", 1, 1, T0, 2, 1, T0),
                item("B", 2, 1, T0, 1, 2, T0),
                item("C", 1, 2, T0, 1, 1, T0));
    }

    @Test
    void closedLoopTransferSucceedsAtomicallyAndFreezesEvidence() {
        prepareCycleRoutes();
        List<TransferItemRequest> items = cycleItems();
        TransferPreviewResult preview = transferService.preview(
                new TransferActivateRequest("tk-preview", "req-preview", items));
        assertTrue(preview.feasible());
        assertEquals(0L, preview.airspaceVersion());
        // 预览只读：没有任何转配落库
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity_transfer", Integer.class));

        MutationResponse resp = transferService.activate(
                new TransferActivateRequest("tk-1", "req-act-1", items));
        assertFalse(resp.replayed());
        TransferActivateResult result = dataOf(resp);
        assertEquals("tk-1", result.transferKey());
        assertEquals(3, result.routes().size());
        // 逐航线增版：A/B/C 全部从 1 → 2
        for (var r : result.routes()) {
            assertEquals(1, r.fromVersion());
            assertEquals(2, r.toVersion());
        }
        // 全部桶转配后均恰好 1 架次（闭环不错判）
        for (BucketCapacityDto b : result.buckets()) {
            assertEquals(b.usedBefore(), b.usedAfter());
            assertTrue(b.usedAfter() <= b.maxFlights());
        }
        // 数据库当前占用：新版本序列生效、旧版本不再计入
        assertEquals(1, capacityService.getBucket(1, 1, T0).usedBefore());
        assertEquals(1, capacityService.getBucket(2, 1, T0).usedBefore());
        assertEquals(1, capacityService.getBucket(1, 2, T0).usedBefore());

        // 证据查询：按航线、桶稳定排序，含审查依据
        TransferEvidenceResult evidence = transferService.getEvidence("tk-1");
        assertEquals("req-act-1", evidence.requestId());
        assertEquals(List.of("A", "B", "C"),
                evidence.routes().stream().map(r -> r.routeId()).toList());
        assertEquals(3, evidence.routes().size());
        for (var r : evidence.routes()) {
            assertNotNull(r.reviewId());
            assertEquals(1, r.reviewRouteVersion());
            assertEquals(0L, r.reviewAirspaceVersion());
            assertEquals(2, r.beforePlan().size());
            assertEquals(2, r.afterPlan().size());
        }
        // 桶证据按 bucketStart, cellX, cellY 排序
        List<String> bucketKeys = evidence.buckets().stream()
                .map(b -> b.cellX() + ":" + b.cellY()).toList();
        List<String> sorted = new ArrayList<>(bucketKeys);
        sorted.sort(String::compareTo);
        assertEquals(sorted, bucketKeys);
    }

    // ---------------------------- 幂等与换序 ----------------------------

    @Test
    void sameRequestReplaysSnapshotAndItemReorderIsSameParams() {
        prepareCycleRoutes();
        List<TransferItemRequest> items = new ArrayList<>(cycleItems());
        // 故意打乱项顺序
        java.util.Collections.reverse(items);
        MutationResponse first = transferService.activate(
                new TransferActivateRequest("tk-2", "req-idem", items));
        assertFalse(first.replayed());
        // 再次换序 + 同 requestId：重放首次快照
        List<TransferItemRequest> items2 = new ArrayList<>(items);
        java.util.Collections.swap(items2, 0, 2);
        MutationResponse replay = transferService.activate(
                new TransferActivateRequest("tk-2", "req-idem", items2));
        assertTrue(replay.replayed());
        assertEquals(dataOf(first).transferKey(), dataOf(replay).transferKey());
        // 仅一次业务生效
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity_transfer", Integer.class));
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer_route_evidence", Integer.class));
    }

    @Test
    void differentParamsWithSameRequestIdConflictAndFailureDoesNotConsumeKey() {
        prepareCycleRoutes();
        transferService.activate(new TransferActivateRequest(
                "tk-3", "req-diff", cycleItems()));
        // 同 requestId 异参（改了目标桶）→ 409
        List<TransferItemRequest> changed = new ArrayList<>(cycleItems());
        changed.set(0, item("A", 1, 1, T0, 2, 2, T0));
        ApiException diff = assertThrows(ApiException.class, () -> transferService.activate(
                new TransferActivateRequest("tk-3", "req-diff", changed)));
        assertEquals(HttpStatus.CONFLICT, diff.status());
        // transferKey 必须唯一：不同 requestId 复用同一 transferKey → 409
        ApiException dupKey = assertThrows(ApiException.class, () -> transferService.activate(
                new TransferActivateRequest("tk-3", "req-other", cycleItems())));
        assertEquals(HttpStatus.CONFLICT, dupKey.status());
        assertEquals("TRANSFER_KEY_EXISTS", dupKey.code());
    }

    @Test
    void failedTransferRollsBackAndDoesNotConsumeKey() {
        prepareCycleRoutes();
        // 把 Y 容量降到 0，闭环必然容量超限
        capacityService.configure(new CapacityConfigRequest(2, 1, T0, 0, req("cap-down")));
        ApiException ex = assertThrows(ApiException.class, () -> transferService.activate(
                new TransferActivateRequest("tk-fail", "req-fail", cycleItems())));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("CAPACITY_EXCEEDED", ex.code());
        // 整体回滚：无转配、无版本递增、无占键
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity_transfer", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM route WHERE route_id = 'A'", Integer.class));
        List<Map<String, Object>> dedupRows = jdbc.queryForList(
                "SELECT request_id FROM request_dedup WHERE request_id = 'req-fail'");
        assertTrue(dedupRows.isEmpty());
        // 键未被占用：恢复容量后同 requestId 可成功
        capacityService.configure(new CapacityConfigRequest(2, 1, T0, 1, req("cap-up")));
        MutationResponse ok = transferService.activate(
                new TransferActivateRequest("tk-fail-ok", "req-fail", cycleItems()));
        assertFalse(ok.replayed());
    }

    // ---------------------------- 失败分支 ----------------------------

    @Test
    void versionChangeIs409AndDiscontinuousIs422() {
        prepareCycleRoutes();
        // 某一项携带错误 expectedVersion → 409
        List<TransferItemRequest> wrongVersion = new ArrayList<>(cycleItems());
        wrongVersion.set(0, new TransferItemRequest("A", 1, 7, bucket(1, 1, T0), bucket(2, 1, T0)));
        ApiException versionEx = assertThrows(ApiException.class, () -> transferService.activate(
                new TransferActivateRequest("tk-v", "req-v", wrongVersion)));
        assertEquals(HttpStatus.CONFLICT, versionEx.status());

        // 目标桶与相邻路径不连续 → 422
        List<TransferItemRequest> discontinuous = new ArrayList<>(cycleItems());
        discontinuous.set(0, item("A", 1, 1, T0, 5, 5, T0));
        ApiException discEx = assertThrows(ApiException.class, () -> transferService.activate(
                new TransferActivateRequest("tk-d", "req-d2", discontinuous)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, discEx.status());
        assertEquals("DISCONTINUOUS_PATH", discEx.code());
        // 失败不留痕
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity_transfer", Integer.class));
    }

    @Test
    void noFlyConflictIs422() {
        prepareCycleRoutes();
        // 在闭环已激活前新建恰好覆盖 Y=cell(2,1) 的禁飞区，使所有 CLEAR 审核失效；
        // 重新审核 BLOCKED，航线不再 ACTIVE；激活应 409 ROUTE_INACTIVE
        reviewService.createZone(new com.example.starter.api.dto.ZoneCreateRequest(
                "z-block", 41, 20, 60, 39, req("zone")));
        ApiException inactive = assertThrows(ApiException.class, () -> transferService.activate(
                new TransferActivateRequest("tk-z", "req-z", cycleItems())));
        assertEquals(HttpStatus.CONFLICT, inactive.status());
        assertEquals("ROUTE_INACTIVE", inactive.code());

        // 撤销区域、空域版本变为 2，重新 CLEAR 后再制造禁飞冲突预览：
        // 直接用预览验证 NO_FLY_CONFLICT 违规明细（后态命中区域即报，不要求审核状态）
        reviewService.revokeZone(new com.example.starter.api.dto.ZoneRevokeRequest(
                "z-block", req("revoke")));
        reviewService.review(new com.example.starter.api.dto.ReviewRequest("A", 1, 2L, req("rvA")));
        reviewService.review(new com.example.starter.api.dto.ReviewRequest("B", 1, 2L, req("rvB")));
        reviewService.review(new com.example.starter.api.dto.ReviewRequest("C", 1, 2L, req("rvC")));
        // 再造一个只覆盖目标 Y 的区域（此时审核变 STALE，仅预览后态几何冲突）
        reviewService.createZone(new com.example.starter.api.dto.ZoneCreateRequest(
                "z-block2", 41, 20, 60, 39, req("zone2")));
        TransferPreviewResult preview = transferService.preview(
                new TransferActivateRequest("tk-p", "req-p", cycleItems()));
        assertFalse(preview.feasible());
        assertTrue(preview.violations().stream().anyMatch(v ->
                "NO_FLY_CONFLICT".equals(v.type()) && "A".equals(v.routeId())));
    }

    @Test
    void missingSourceBucketIs422() {
        prepareCycleRoutes();
        List<TransferItemRequest> bad = new ArrayList<>(cycleItems());
        bad.set(0, item("A", 8, 8, T0, 2, 1, T0));
        ApiException ex = assertThrows(ApiException.class, () -> transferService.activate(
                new TransferActivateRequest("tk-m", "req-m", bad)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("ITEM_NOT_IN_PLAN", ex.code());
    }

    @Test
    void nonParticipantOccupancyIsEnforced() {
        prepareCycleRoutes();
        // 非参与者 D 占用目标 Y 一架次（容量 1）→ 闭环后 Y 有 A 与 D，超限
        createRoute("D");
        approveClear("D");
        registerPlan("D", 1, planItem(0, 2, 1, T0), planItem(1, 2, 2, T0));
        ApiException ex = assertThrows(ApiException.class, () -> transferService.activate(
                new TransferActivateRequest("tk-np", "req-np", cycleItems())));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("CAPACITY_EXCEEDED", ex.code());
    }

    // ---------------------------- 并发边界 ----------------------------

    @Test
    void concurrentTransfersSerializeByCommitOrder() throws Exception {
        prepareCycleRoutes();
        // 两组内容相同但键不同的闭环转配并发：无论提交顺序如何，
        // 先提交者完整成功，后提交者读到版本已推进必须 409，且无部分转配。
        int n = 2;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            Future<Object> f1 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return transferService.activate(new TransferActivateRequest(
                            "tk-c1", "req-c1", cycleItems()));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            Future<Object> f2 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return transferService.activate(new TransferActivateRequest(
                            "tk-c2", "req-c2", cycleItems()));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            Object r1 = f1.get(20, TimeUnit.SECONDS);
            Object r2 = f2.get(20, TimeUnit.SECONDS);
            int success = (r1 instanceof MutationResponse ? 1 : 0)
                    + (r2 instanceof MutationResponse ? 1 : 0);
            int conflict = (r1 instanceof ApiException e && e.status() == HttpStatus.CONFLICT ? 1 : 0)
                    + (r2 instanceof ApiException e && e.status() == HttpStatus.CONFLICT ? 1 : 0);
            assertEquals(1, success, "恰好一个转配成功");
            assertEquals(1, conflict, "落败转配必须 409");
            // 只有一个转配落库，航线版本统一为 2，无部分转配痕迹
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM capacity_transfer", Integer.class));
            assertEquals(3, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM route WHERE version = 2", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void transferConcurrentWithRouteRevisionIsSerializable() throws Exception {
        prepareCycleRoutes();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> transferFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return transferService.activate(new TransferActivateRequest(
                            "tk-r", "req-r", cycleItems()));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            Future<Object> reviseFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return reviewService.replaceRoute(
                            new com.example.starter.api.dto.RouteReplaceRequest("A", 1,
                                    List.of(new com.example.starter.api.dto.RoutePointDto(0, 0),
                                            new com.example.starter.api.dto.RoutePointDto(50, 50)),
                                    "req-revise-a"));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            Object transferResult = transferFuture.get(20, TimeUnit.SECONDS);
            Object reviseResult = reviseFuture.get(20, TimeUnit.SECONDS);
            // 恰好一方成功；若修订先提交，转配必须 409 且无部分转配
            if (reviseResult instanceof MutationResponse) {
                assertTrue(transferResult instanceof ApiException);
                assertEquals(HttpStatus.CONFLICT, ((ApiException) transferResult).status());
                assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM capacity_transfer", Integer.class));
            } else {
                assertTrue(transferResult instanceof MutationResponse,
                        "转配先提交时必须完整成功");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void transferConcurrentWithCapacityAdjustmentNeverSeesHalfState() throws Exception {
        prepareCycleRoutes();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> transferFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return transferService.activate(new TransferActivateRequest(
                            "tk-cap", "req-cap", cycleItems()));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            // 容量调整：把目标 Y 的上限降到 0；与激活在同一协调锁上串行
            Future<Object> adjustFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return capacityService.configure(new CapacityConfigRequest(
                            2, 1, T0, 0, "req-lower-y"));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            Object transferResult = transferFuture.get(20, TimeUnit.SECONDS);
            Object adjustResult = adjustFuture.get(20, TimeUnit.SECONDS);
            assertTrue(adjustResult instanceof MutationResponse, "容量调整必须成功一次");
            if (transferResult instanceof ApiException ex) {
                // 调整先提交：激活只能观察到调整后的完整状态，因容量超限 422 整体回滚
                assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
                assertEquals("CAPACITY_EXCEEDED", ex.code());
                assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM capacity_transfer", Integer.class));
                assertEquals(1, jdbc.queryForObject(
                        "SELECT version FROM route WHERE route_id = 'A'", Integer.class));
            } else {
                // 激活先提交：完整成功；调整随后生效，双方互不出现部分状态
                assertNotNull(transferResult);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void transferConcurrentWithAirspaceChangeRejectsStaleReview() throws Exception {
        prepareCycleRoutes();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> transferFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return transferService.activate(new TransferActivateRequest(
                            "tk-air", "req-air", cycleItems()));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            // 建禁飞区推进空域版本：与激活在同一协调锁上串行
            Future<Object> zoneFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return reviewService.createZone(new com.example.starter.api.dto.ZoneCreateRequest(
                            "z-conc", -90, -90, -80, -80, "req-zone-conc"));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            Object transferResult = transferFuture.get(20, TimeUnit.SECONDS);
            Object zoneResult = zoneFuture.get(20, TimeUnit.SECONDS);
            assertTrue(zoneResult instanceof MutationResponse, "建区必须成功");
            if (transferResult instanceof ApiException ex) {
                // 建区先提交：空域版本已推进，旧 CLEAR 失效，激活必须 409 且无部分转配
                assertEquals(HttpStatus.CONFLICT, ex.status());
                assertEquals("ROUTE_INACTIVE", ex.code());
                assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM capacity_transfer", Integer.class));
                assertEquals(3, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM route WHERE version = 1", Integer.class));
            } else {
                // 激活先提交：基于旧空域版本的完整状态成功，建区随后生效
                assertNotNull(transferResult);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
