package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.BucketRefDto;
import com.example.starter.api.dto.CapacityConfigRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.RouteActivateRequest;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.TransferEvidenceDto;
import com.example.starter.api.dto.TransferItemDto;
import com.example.starter.api.dto.TransferPreviewRequest;
import com.example.starter.api.dto.TransferPreviewResult;
import com.example.starter.api.dto.TransferRequest;
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

import java.util.ArrayList;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容量账本与时空桶转配的 H2 数据库测试（MODE=MySQL）。
 * 覆盖闭环转配、路径/禁飞/容量失败、整体回滚、幂等与真实并发边界。
 *
 * <p>公共夹具：航线沿 y=500 水平飞行 (100,500)→(3100,500)，穿越单元
 * 0:0/1:0/2:0/3:0；速度 1 m/s 时各单元进入桶为 0/900/1800/2700，
 * 速度 2 m/s 时为 0/0/900/900，起飞 900 秒速度 1 m/s 时为 900/1800/2700/3600。</p>
 */
@SpringBootTest
class CapacityTransferServiceH2Test {

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
        jdbc.update("DELETE FROM capacity_transfer_bucket");
        jdbc.update("DELETE FROM capacity_transfer_item");
        jdbc.update("DELETE FROM capacity_transfer");
        jdbc.update("DELETE FROM capacity_occupancy");
        jdbc.update("DELETE FROM route_activation");
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
        Long v = jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id = 1", Long.class);
        return v == null ? 0L : v;
    }

    /** 创建航线、审查 CLEAR 并激活（占用容量）。 */
    private void setupActiveRoute(String routeId, long departure, double speed) {
        reviewService.createRoute(new RouteCreateRequest(
                routeId, pts(100, 500, 3100, 500), rid("route")));
        MutationResponse review = reviewService.review(
                new ReviewRequest(routeId, 1, airspaceVersion(), rid("review")));
        assertEquals("CLEAR", objectMapper.convertValue(review.data(),
                com.example.starter.api.dto.ReviewResultDto.class).conclusion());
        MutationResponse activation = transferService.activateRoute(
                new RouteActivateRequest(routeId, 1, departure, speed, rid("activate")));
        assertFalse(activation.replayed());
    }

    private void config(String cellId, long bucketStart, int maxFlights) {
        transferService.configureCapacity(
                new CapacityConfigRequest(cellId, bucketStart, maxFlights, rid("config")));
    }

    private static TransferItemDto item(String routeId, int expectedVersion,
                                        String sourceCell, long sourceBucket,
                                        String targetCell, long targetBucket) {
        return new TransferItemDto(routeId, expectedVersion,
                new BucketRefDto(sourceCell, sourceBucket),
                new BucketRefDto(targetCell, targetBucket));
    }

    private int routeVersion(String routeId) {
        Integer v = jdbc.queryForObject(
                "SELECT version FROM route WHERE route_id = ?", Integer.class, routeId);
        return v == null ? -1 : v;
    }

    private int occupancyCount(String routeId, int version) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity_occupancy WHERE route_id = ? AND route_version = ?",
                Integer.class, routeId, version);
        return c == null ? 0 : c;
    }

    private int occupancyAt(String routeId, String cellId, long bucketStart) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity_occupancy "
                        + "WHERE route_id = ? AND cell_id = ? AND bucket_start = ?",
                Integer.class, routeId, cellId, bucketStart);
        return c == null ? 0 : c;
    }

    private int tableCount(String table) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return c == null ? 0 : c;
    }

    /** 闭环航线夹具（仅激活，不配置容量）：A(起飞0,1m/s)、B(起飞0,2m/s)、C(起飞900,1m/s)。 */
    private void setupLoopRoutes() {
        setupActiveRoute("A", 0L, 1.0d);
        setupActiveRoute("B", 0L, 2.0d);
        setupActiveRoute("C", 900L, 1.0d);
    }

    /** 标准闭环夹具：航线 + 三个桶配置（上限均为 1）。 */
    private void setupClosedLoop() {
        setupLoopRoutes();
        config("1:0", 900L, 1);
        config("2:0", 900L, 1);
        config("1:0", 1800L, 1);
    }

    /** 闭环转配项：A→B→C→A，项顺序故意打乱（顺序不影响语义）。 */
    private static List<TransferItemDto> closedLoopItems() {
        List<TransferItemDto> items = new ArrayList<>();
        items.add(item("C", 1, "1:0", 1800L, "1:0", 900L));
        items.add(item("A", 1, "1:0", 900L, "2:0", 900L));
        items.add(item("B", 1, "2:0", 900L, "1:0", 1800L));
        return items;
    }

    // ============================ 闭环转配主流程 ============================

    @Test
    void closedLoopTransferAppliesAtomically() {
        setupClosedLoop();

        // 预览：完整后态计算，闭环净变化为零，不应误判超限
        TransferPreviewResult preview = transferService.previewTransfer(
                new TransferPreviewRequest(closedLoopItems()));
        assertTrue(preview.applicable());
        assertTrue(preview.violations().isEmpty());
        assertEquals(3, preview.buckets().size());
        // 桶按单元、桶起点稳定排序：1:0@900、1:0@1800、2:0@900
        assertEquals("1:0", preview.buckets().get(0).cellId());
        assertEquals(900L, preview.buckets().get(0).bucketStart());
        assertEquals(1, preview.buckets().get(0).usedBefore());
        assertEquals(1, preview.buckets().get(0).usedAfter());
        assertEquals(0, preview.buckets().get(0).remainingAfter());
        assertEquals("1:0", preview.buckets().get(1).cellId());
        assertEquals(1800L, preview.buckets().get(1).bucketStart());
        assertEquals("2:0", preview.buckets().get(2).cellId());
        // 航线穿越序列：A 的源位置被替换为目标桶
        assertEquals(3, preview.routes().size());
        var routeA = preview.routes().stream()
                .filter(r -> r.routeId().equals("A")).findFirst().orElseThrow();
        assertEquals(1, routeA.currentVersion());
        assertEquals("1:0", routeA.beforeCells().get(1).cellId());
        assertEquals("2:0", routeA.afterCells().get(1).cellId());
        assertEquals(900L, routeA.afterCells().get(1).bucketStart());

        // 激活：一个事务内全部应用
        MutationResponse applied = transferService.applyTransfer(
                new TransferRequest("tk-loop", closedLoopItems(), rid("transfer")));
        assertFalse(applied.replayed());
        TransferEvidenceDto evidence = objectMapper.convertValue(
                applied.data(), TransferEvidenceDto.class);
        assertEquals("tk-loop", evidence.transferKey());
        assertEquals(3, evidence.items().size());
        // 证据按航线稳定排序
        assertEquals("A", evidence.items().get(0).routeId());
        assertEquals("B", evidence.items().get(1).routeId());
        assertEquals("C", evidence.items().get(2).routeId());
        assertEquals(2, evidence.items().get(0).newVersion());
        assertNotNull(evidence.items().get(0).reviewId());
        assertEquals(3, evidence.buckets().size());

        // 数据库终态：逐航线增版、占用一次性替换、旧激活停用
        assertEquals(2, routeVersion("A"));
        assertEquals(2, routeVersion("B"));
        assertEquals(2, routeVersion("C"));
        assertEquals(0, occupancyCount("A", 1));
        assertEquals(4, occupancyCount("A", 2));
        assertEquals(1, occupancyAt("A", "2:0", 900L));
        assertEquals(0, occupancyAt("A", "1:0", 900L));
        assertEquals(1, occupancyAt("B", "1:0", 1800L));
        assertEquals(1, occupancyAt("C", "1:0", 900L));
        assertEquals("SUSPENDED", jdbc.queryForObject(
                "SELECT status FROM route_activation WHERE route_id = 'A' AND version = 1",
                String.class));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM route_activation WHERE route_id = 'A' AND version = 2",
                String.class));

        // 证据查询只读且稳定排序
        TransferEvidenceDto loaded = transferService.getTransfer("tk-loop");
        assertEquals(evidence.transferKey(), loaded.transferKey());
        assertEquals(3, loaded.items().size());
        assertEquals("A", loaded.items().get(0).routeId());
        assertEquals("1:0", loaded.buckets().get(0).cellId());
        assertEquals(900L, loaded.buckets().get(0).bucketStart());
        assertEquals("2:0", loaded.buckets().get(2).cellId());
    }

    @Test
    void previewIsReadOnlyAndReportsViolations() {
        setupClosedLoop();
        config("3:0", 0L, 5);
        int occupancyBefore = tableCount("capacity_occupancy");

        List<TransferItemDto> items = List.of(
                item("A", 99, "1:0", 900L, "2:0", 900L),
                item("B", 1, "2:0", 900L, "3:0", 0L));
        TransferPreviewResult preview = transferService.previewTransfer(
                new TransferPreviewRequest(items));
        assertFalse(preview.applicable());
        assertEquals(1, preview.violations().size());
        assertEquals("VERSION_CONFLICT", preview.violations().get(0).code());
        assertEquals("A", preview.violations().get(0).routeId());

        // 只读：不产生任何持久化变更
        assertEquals(0, tableCount("capacity_transfer"));
        assertEquals(0, tableCount("capacity_transfer_item"));
        assertEquals(occupancyBefore, tableCount("capacity_occupancy"));
        assertEquals(1, routeVersion("A"));
        assertEquals(1, routeVersion("B"));
    }

    // ============================ 失败分支与整体回滚 ============================

    @Test
    void pathNotContiguousRejectedAndFailureDoesNotBurnRequestId() {
        setupClosedLoop();
        String requestId = rid("transfer");
        // A 的目标 3:0 与源位置（穿越序号 1）不相邻；B 为合法项
        List<TransferItemDto> bad = List.of(
                item("A", 1, "1:0", 900L, "3:0", 900L),
                item("B", 1, "2:0", 900L, "1:0", 1800L));
        ApiException ex = assertThrows(ApiException.class, () ->
                transferService.applyTransfer(new TransferRequest("tk-bad", bad, requestId)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("PATH_NOT_CONTIGUOUS", ex.code());

        // 整体回滚：版本、占用、转配记录均无变化，失败不占 requestId
        assertEquals(1, routeVersion("A"));
        assertEquals(1, routeVersion("B"));
        assertEquals(4, occupancyCount("A", 1));
        assertEquals(0, tableCount("capacity_transfer"));
        Integer dedup = jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                Integer.class, requestId);
        assertEquals(0, dedup == null ? 0 : dedup);

        // 同一 requestId 换用合法参数可正常执行（未被失败占用）
        config("3:0", 1800L, 1);
        List<TransferItemDto> good = List.of(
                item("A", 1, "1:0", 900L, "2:0", 900L),
                item("B", 1, "2:0", 900L, "3:0", 1800L));
        MutationResponse ok = transferService.applyTransfer(
                new TransferRequest("tk-good", good, requestId));
        assertFalse(ok.replayed());
        assertEquals(2, routeVersion("A"));
    }

    @Test
    void noFlyConflictRejected() {
        setupClosedLoop();
        // 禁飞区覆盖单元 2:0（[2000,3000]×[0,1000]）
        reviewService.createZone(new ZoneCreateRequest(
                "z-block", 2000, 0, 3000, 1000, rid("zone")));
        List<TransferItemDto> items = List.of(
                item("A", 1, "1:0", 900L, "2:0", 900L),
                item("B", 1, "2:0", 900L, "1:0", 1800L));
        ApiException ex = assertThrows(ApiException.class, () ->
                transferService.applyTransfer(new TransferRequest("tk-nfz", items, rid("transfer"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("NO_FLY_CONFLICT", ex.code());
        assertEquals(1, routeVersion("A"));
        assertEquals(0, tableCount("capacity_transfer"));
    }

    @Test
    void capacityExceededRejected() {
        // D 与 B 同速（2 m/s），同样占用 2:0@900；配置在全部激活后创建
        setupLoopRoutes();
        setupActiveRoute("D", 0L, 2.0d);
        config("1:0", 900L, 1);
        config("2:0", 900L, 1);
        config("1:0", 1800L, 1);
        // 2:0@900 当前已有 B、D 两架次，上限 1；A 再迁入必然超限
        List<TransferItemDto> items = List.of(
                item("A", 1, "1:0", 900L, "2:0", 900L),
                item("C", 1, "1:0", 1800L, "1:0", 900L));
        ApiException ex = assertThrows(ApiException.class, () ->
                transferService.applyTransfer(new TransferRequest("tk-cap", items, rid("transfer"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("CAPACITY_EXCEEDED", ex.code());
        // 整体回滚
        assertEquals(1, routeVersion("A"));
        assertEquals(1, routeVersion("C"));
        assertEquals(4, occupancyCount("A", 1));
        assertEquals(0, tableCount("capacity_transfer"));
    }

    @Test
    void versionConflictRejectedWith409() {
        setupClosedLoop();
        List<TransferItemDto> items = List.of(
                item("A", 2, "1:0", 900L, "2:0", 900L),
                item("B", 1, "2:0", 900L, "1:0", 1800L));
        ApiException ex = assertThrows(ApiException.class, () ->
                transferService.applyTransfer(new TransferRequest("tk-ver", items, rid("transfer"))));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("VERSION_CONFLICT", ex.code());
        assertEquals(1, routeVersion("A"));
    }

    @Test
    void missingSourceOccupancyRejected() {
        setupClosedLoop();
        // A 在 1:0@1800 没有占用（集合遗漏）
        List<TransferItemDto> items = List.of(
                item("A", 1, "1:0", 1800L, "2:0", 900L),
                item("B", 1, "2:0", 900L, "1:0", 1800L));
        ApiException ex = assertThrows(ApiException.class, () ->
                transferService.applyTransfer(new TransferRequest("tk-miss", items, rid("transfer"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("OCCUPANCY_NOT_FOUND", ex.code());
    }

    @Test
    void inactiveRouteRejected() {
        setupClosedLoop();
        // E 仅创建并审查通过，未激活
        reviewService.createRoute(new RouteCreateRequest(
                "E", pts(100, 500, 3100, 500), rid("route")));
        reviewService.review(new ReviewRequest("E", 1, airspaceVersion(), rid("review")));
        List<TransferItemDto> items = List.of(
                item("E", 1, "1:0", 900L, "2:0", 900L),
                item("B", 1, "2:0", 900L, "1:0", 1800L));
        ApiException ex = assertThrows(ApiException.class, () ->
                transferService.applyTransfer(new TransferRequest("tk-inactive", items, rid("transfer"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("ROUTE_NOT_ACTIVE", ex.code());
    }

    @Test
    void unconfiguredTargetBucketRejected() {
        setupActiveRoute("A", 0L, 1.0d);
        setupActiveRoute("B", 0L, 2.0d);
        // 只为 B 的目标配置，A 的目标 2:0@900 未配置
        config("1:0", 1800L, 1);
        List<TransferItemDto> items = List.of(
                item("A", 1, "1:0", 900L, "2:0", 900L),
                item("B", 1, "2:0", 900L, "1:0", 1800L));
        ApiException ex = assertThrows(ApiException.class, () ->
                transferService.applyTransfer(new TransferRequest("tk-nocfg", items, rid("transfer"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("CAPACITY_NOT_CONFIGURED", ex.code());
    }

    @Test
    void structuralValidationFailures() {
        setupClosedLoop();
        // 同一航线重复
        ApiException dup = assertThrows(ApiException.class, () ->
                transferService.applyTransfer(new TransferRequest("tk-dup", List.of(
                        item("A", 1, "1:0", 900L, "2:0", 900L),
                        item("A", 1, "2:0", 1800L, "1:0", 900L)), rid("transfer"))));
        assertEquals(HttpStatus.BAD_REQUEST, dup.status());
        assertEquals("DUPLICATE_ROUTE_ITEM", dup.code());
        // 源目标相同
        ApiException same = assertThrows(ApiException.class, () ->
                transferService.applyTransfer(new TransferRequest("tk-same", List.of(
                        item("A", 1, "1:0", 900L, "1:0", 900L),
                        item("B", 1, "2:0", 900L, "1:0", 1800L)), rid("transfer"))));
        assertEquals(HttpStatus.BAD_REQUEST, same.status());
        assertEquals("INVALID_TARGET_BUCKET", same.code());
        // 项数不足
        ApiException few = assertThrows(ApiException.class, () ->
                transferService.applyTransfer(new TransferRequest("tk-few", List.of(
                        item("A", 1, "1:0", 900L, "2:0", 900L)), rid("transfer"))));
        assertEquals(HttpStatus.BAD_REQUEST, few.status());
        assertEquals("INVALID_ITEM_COUNT", few.code());
        // 桶未对齐 15 分钟
        ApiException align = assertThrows(ApiException.class, () ->
                transferService.applyTransfer(new TransferRequest("tk-align", List.of(
                        item("A", 1, "1:0", 1000L, "2:0", 900L),
                        item("B", 1, "2:0", 900L, "1:0", 1800L)), rid("transfer"))));
        assertEquals(HttpStatus.BAD_REQUEST, align.status());
        assertEquals("BUCKET_NOT_ALIGNED", align.code());
    }

    // ============================ 幂等与唯一约束 ============================

    @Test
    void idempotentReplayParamMismatchAndTransferKeyUnique() {
        setupClosedLoop();
        String requestId = rid("transfer");
        MutationResponse first = transferService.applyTransfer(
                new TransferRequest("tk-idem", closedLoopItems(), requestId));
        assertFalse(first.replayed());

        // 同参重放（项换序视为同参）：返回首次快照，版本不再次推进
        List<TransferItemDto> reordered = List.of(
                item("B", 1, "2:0", 900L, "1:0", 1800L),
                item("A", 1, "1:0", 900L, "2:0", 900L),
                item("C", 1, "1:0", 1800L, "1:0", 900L));
        MutationResponse replay = transferService.applyTransfer(
                new TransferRequest("tk-idem", reordered, requestId));
        assertTrue(replay.replayed());
        TransferEvidenceDto replayed = objectMapper.convertValue(
                replay.data(), TransferEvidenceDto.class);
        assertEquals("tk-idem", replayed.transferKey());
        assertEquals(2, routeVersion("A"));
        assertEquals(1, tableCount("capacity_transfer"));

        // 同键异参 → 409
        ApiException mismatch = assertThrows(ApiException.class, () ->
                transferService.applyTransfer(new TransferRequest("tk-idem", List.of(
                        item("A", 1, "1:0", 900L, "2:0", 1800L),
                        item("B", 1, "2:0", 900L, "1:0", 1800L)), requestId)));
        assertEquals(HttpStatus.CONFLICT, mismatch.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", mismatch.code());

        // 不同 requestId 复用 transferKey → 409
        ApiException keyClash = assertThrows(ApiException.class, () ->
                transferService.applyTransfer(new TransferRequest("tk-idem",
                        closedLoopItems(), rid("transfer"))));
        assertEquals(HttpStatus.CONFLICT, keyClash.status());
        assertEquals("TRANSFER_KEY_EXISTS", keyClash.code());
    }

    @Test
    void transferNotFoundReturns404() {
        ApiException ex = assertThrows(ApiException.class,
                () -> transferService.getTransfer("tk-missing"));
        assertEquals(HttpStatus.NOT_FOUND, ex.status());
        assertEquals("TRANSFER_NOT_FOUND", ex.code());
    }

    // ============================ 并发边界 ============================

    @Test
    void concurrentTransfersSerializeAndOnlyOneApplies() throws Exception {
        setupClosedLoop();
        config("1:0", 0L, 2);
        config("3:0", 1800L, 1);
        // 两个转配单都涉及 A、B，期望版本同为 1：只有一个能成功
        List<TransferItemDto> t1 = List.of(
                item("A", 1, "1:0", 900L, "2:0", 900L),
                item("B", 1, "2:0", 900L, "3:0", 1800L));
        List<TransferItemDto> t2 = List.of(
                item("A", 1, "1:0", 900L, "1:0", 0L),
                item("B", 1, "2:0", 900L, "1:0", 900L));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<Object> f1 = pool.submit(() -> applyQuietly("tk-race-1", t1, barrier));
            Future<Object> f2 = pool.submit(() -> applyQuietly("tk-race-2", t2, barrier));
            Object r1 = f1.get(60, TimeUnit.SECONDS);
            Object r2 = f2.get(60, TimeUnit.SECONDS);

            int successes = (r1 instanceof MutationResponse ? 1 : 0)
                    + (r2 instanceof MutationResponse ? 1 : 0);
            assertEquals(1, successes, "并发转配按提交顺序只能成功一个");
            Object failure = r1 instanceof MutationResponse ? r2 : r1;
            assertTrue(failure instanceof ApiException);
            assertEquals(HttpStatus.CONFLICT, ((ApiException) failure).status());

            // 终态完整：A、B 只被推进一次，占用与版本一致
            assertEquals(2, routeVersion("A"));
            assertEquals(2, routeVersion("B"));
            assertEquals(4, occupancyCount("A", 2));
            assertEquals(4, occupancyCount("B", 2));
            assertEquals(0, occupancyCount("A", 1));
            assertEquals(1, tableCount("capacity_transfer"));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    private Object applyQuietly(String key, List<TransferItemDto> items, CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
            return transferService.applyTransfer(
                    new TransferRequest(key, items, rid("transfer")));
        } catch (ApiException ex) {
            return ex;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    // ============================ 容量配置与航线激活 ============================

    @Test
    void capacityConfigIdempotencyAndValidation() {
        String requestId = rid("config");
        MutationResponse first = transferService.configureCapacity(
                new CapacityConfigRequest("0:0", 0L, 2, requestId));
        assertFalse(first.replayed());
        // 同键同参重放
        MutationResponse replay = transferService.configureCapacity(
                new CapacityConfigRequest("0:0", 0L, 2, requestId));
        assertTrue(replay.replayed());
        // 同键异参 → 409
        ApiException mismatch = assertThrows(ApiException.class, () ->
                transferService.configureCapacity(
                        new CapacityConfigRequest("0:0", 0L, 3, requestId)));
        assertEquals(HttpStatus.CONFLICT, mismatch.status());
        // 桶未对齐 → 400
        ApiException align = assertThrows(ApiException.class, () ->
                transferService.configureCapacity(
                        new CapacityConfigRequest("0:0", 1000L, 1, rid("config"))));
        assertEquals(HttpStatus.BAD_REQUEST, align.status());
        assertEquals("BUCKET_NOT_ALIGNED", align.code());
        // 单元格式非法 → 400
        ApiException cell = assertThrows(ApiException.class, () ->
                transferService.configureCapacity(
                        new CapacityConfigRequest("cell-x", 0L, 1, rid("config"))));
        assertEquals(HttpStatus.BAD_REQUEST, cell.status());
        assertEquals("INVALID_CELL_ID", cell.code());
        // 容量调整生效
        transferService.configureCapacity(
                new CapacityConfigRequest("0:0", 0L, 5, rid("config")));
        Integer max = jdbc.queryForObject(
                "SELECT max_flights FROM capacity_config WHERE cell_id = '0:0' AND bucket_start = 0",
                Integer.class);
        assertEquals(5, max == null ? -1 : max);
    }

    @Test
    void activateRouteFailureBranches() {
        // 无审查 → 422
        reviewService.createRoute(new RouteCreateRequest(
                "R1", pts(100, 500, 3100, 500), rid("route")));
        ApiException noReview = assertThrows(ApiException.class, () ->
                transferService.activateRoute(new RouteActivateRequest("R1", 1, 0L, 1.0d, rid("act"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, noReview.status());
        assertEquals("REVIEW_NOT_CURRENT", noReview.code());

        // 审查后空域版本推进（审查依据失效）→ 422
        reviewService.review(new ReviewRequest("R1", 1, airspaceVersion(), rid("review")));
        reviewService.createZone(new ZoneCreateRequest(
                "z-far", 50000, 50000, 51000, 51000, rid("zone")));
        ApiException stale = assertThrows(ApiException.class, () ->
                transferService.activateRoute(new RouteActivateRequest("R1", 1, 0L, 1.0d, rid("act"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, stale.status());
        assertEquals("REVIEW_NOT_CURRENT", stale.code());

        // 版本不匹配 → 409
        ApiException version = assertThrows(ApiException.class, () ->
                transferService.activateRoute(new RouteActivateRequest("R1", 5, 0L, 1.0d, rid("act"))));
        assertEquals(HttpStatus.CONFLICT, version.status());

        // 航线不存在 → 404
        ApiException missing = assertThrows(ApiException.class, () ->
                transferService.activateRoute(new RouteActivateRequest("RX", 1, 0L, 1.0d, rid("act"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    @Test
    void activateRouteCapacityAndDoubleActivation() {
        reviewService.createRoute(new RouteCreateRequest(
                "R2", pts(100, 500, 3100, 500), rid("route")));
        reviewService.review(new ReviewRequest("R2", 1, airspaceVersion(), rid("review")));
        // 1:0@900 上限 0：任何占用都超限
        config("1:0", 900L, 0);
        ApiException exceeded = assertThrows(ApiException.class, () ->
                transferService.activateRoute(new RouteActivateRequest("R2", 1, 0L, 1.0d, rid("act"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exceeded.status());
        assertEquals("CAPACITY_EXCEEDED", exceeded.code());

        // 放宽上限后激活成功；重复激活 → 409
        config("1:0", 900L, 1);
        MutationResponse ok = transferService.activateRoute(
                new RouteActivateRequest("R2", 1, 0L, 1.0d, rid("act")));
        assertFalse(ok.replayed());
        ApiException again = assertThrows(ApiException.class, () ->
                transferService.activateRoute(new RouteActivateRequest("R2", 1, 0L, 1.0d, rid("act"))));
        assertEquals(HttpStatus.CONFLICT, again.status());
        assertEquals("ROUTE_ALREADY_ACTIVE", again.code());
    }

    @Test
    void routeReplaceSuspendsActivationAndOccupancy() {
        setupActiveRoute("A", 0L, 1.0d);
        setupActiveRoute("B", 0L, 2.0d);
        config("1:0", 1800L, 2);
        assertEquals(4, occupancyCount("A", 1));

        // 航线修订：旧版本激活停用、占用移除
        reviewService.replaceRoute(new RouteReplaceRequest(
                "A", 1, pts(100, 500, 2100, 500), rid("replace")));
        assertEquals(2, routeVersion("A"));
        assertEquals(0, occupancyCount("A", 1));
        assertEquals("SUSPENDED", jdbc.queryForObject(
                "SELECT status FROM route_activation WHERE route_id = 'A' AND version = 1",
                String.class));

        // 旧版本转配 → 409 版本冲突；新版本未激活 → 422
        ApiException oldVersion = assertThrows(ApiException.class, () ->
                transferService.applyTransfer(new TransferRequest("tk-old", List.of(
                        item("A", 1, "1:0", 900L, "2:0", 900L),
                        item("B", 1, "2:0", 900L, "1:0", 1800L)), rid("transfer"))));
        assertEquals(HttpStatus.CONFLICT, oldVersion.status());
        ApiException notActive = assertThrows(ApiException.class, () ->
                transferService.applyTransfer(new TransferRequest("tk-new", List.of(
                        item("A", 2, "1:0", 900L, "2:0", 900L),
                        item("B", 1, "2:0", 900L, "1:0", 1800L)), rid("transfer"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, notActive.status());
        assertEquals("ROUTE_NOT_ACTIVE", notActive.code());
    }
}
