package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.EnergyBlockDto;
import com.example.starter.api.dto.EnergyProfileRegisterRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.service.AirspaceReviewService;
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
 * 航线版本能量档案与 ENERGY_BLOCKED 结论的 H2 数据库测试（MODE=MySQL）。
 * 覆盖题干合成场景、几何优先、登记校验、幂等、STALE 与真实并发。
 */
@SpringBootTest
class EnergyProfileH2Test {

    @Autowired
    private AirspaceReviewService service;
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
        jdbc.update("DELETE FROM route_energy_profile");
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

    private void createRoute(String routeId, List<RoutePointDto> points) {
        service.createRoute(new RouteCreateRequest(routeId, points, "req-" + rid("route")));
    }

    private EnergyProfileRegisterRequest energyReq(String routeId, int version, long available,
                                                   long reserve, List<Long> segments,
                                                   List<Long> diversions) {
        return new EnergyProfileRegisterRequest(routeId, version, available, reserve,
                segments, diversions, "tester", "req-" + rid("energy"));
    }

    private ReviewResultDto review(String routeId, int routeVersion) {
        long airspaceVersion = jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id=1", Long.class);
        MutationResponse resp = service.review(new ReviewRequest(
                routeId, routeVersion, airspaceVersion, "req-" + rid("review")));
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    private ReviewResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    // ============================ 题干合成场景 ============================

    @Test
    void syntheticScenarioEnergyBlockedThenQualifiedAfterLoweringDiversion() {
        // 三航点两段航线，几何上无禁飞区
        createRoute("e1", pts(0, 0, 50, 0, 100, 0));
        // 合成能量 100、必留 10、两段各 30、终点备降 40 → 终点需求 110 > 100
        MutationResponse reg = service.registerEnergyProfile(
                energyReq("e1", 1, 100, 10, List.of(30L, 30L), List.of(0L, 0L, 40L)));
        assertFalse(reg.replayed());
        assertEquals(2, objectMapper.convertValue(reg.data(), Map.class).get("segments"));

        ReviewResultDto blocked = review("e1", 1);
        assertEquals("ENERGY_BLOCKED", blocked.conclusion());
        assertTrue(blocked.hitZoneIds().isEmpty());
        // 阻断点号与缺口可观察：终点（点号 2）需求 110，缺口 10
        assertEquals(1, blocked.energyBlocks().size());
        EnergyBlockDto block = blocked.energyBlocks().get(0);
        assertEquals(2, block.pointIndex());
        assertEquals(10, block.deficit());
        assertEquals(110, block.required());
        assertEquals(100, block.availableEnergy());
        // 冻结每点累计与余量：0 / 30 / 60
        assertNotNull(blocked.energySnapshot());
        assertEquals(10, blocked.energySnapshot().reserveEnergy());
        assertEquals(List.of(0L, 30L, 60L), blocked.energySnapshot().points().stream()
                .map(p -> p.cumulativeConsumption()).toList());

        // 档案变化必须通过航线修订生成新版本；旧审查随之 STALE
        service.replaceRoute(new RouteReplaceRequest(
                "e1", 1, pts(0, 0, 50, 0, 100, 0), "req-" + rid("replace")));
        assertEquals("STALE", service.getCurrentReview("e1").conclusion());
        // 历史审查保留原结论与原快照
        ReviewResultDto history = service.getReview(blocked.reviewId());
        assertEquals("ENERGY_BLOCKED", history.conclusion());
        assertEquals(10, history.energyBlocks().get(0).deficit());

        // 新版本登记终点备降 30 → 终点需求 100 ≤ 100，能量合格
        service.registerEnergyProfile(
                energyReq("e1", 2, 100, 10, List.of(30L, 30L), List.of(0L, 0L, 30L)));
        ReviewResultDto clear = review("e1", 2);
        assertEquals("CLEAR", clear.conclusion());
        assertTrue(clear.energyBlocks().isEmpty());
        assertEquals("CLEAR", service.getCurrentReview("e1").conclusion());
    }

    @Test
    void energyQualifiedDoesNotOverrideGeometryBlocked() {
        // 航线可避开禁飞区却没有能量备降 / 能量合格但几何命中：几何 BLOCKED 优先
        createRoute("e2", pts(0, 10, 100, 10));
        service.createZone(new ZoneCreateRequest("gz", 40, 5, 60, 15, "req-" + rid("zone")));
        // 能量完全合格
        service.registerEnergyProfile(
                energyReq("e2", 1, 100, 10, List.of(30L), List.of(0L, 0L)));
        ReviewResultDto dto = review("e2", 1);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(List.of("gz"), dto.hitZoneIds());
        // 能量评估仍随审查冻结、可观察
        assertNotNull(dto.energySnapshot());
        assertTrue(dto.energyBlocks().isEmpty());
    }

    @Test
    void geometryBlockedAndEnergyBlockedBothObservable() {
        createRoute("e3", pts(0, 10, 100, 10));
        service.createZone(new ZoneCreateRequest("gz2", 40, 5, 60, 15, "req-" + rid("zone")));
        // 能量也不足：一段 95 + 余量 10 > 100
        service.registerEnergyProfile(
                energyReq("e3", 1, 100, 10, List.of(95L), List.of(0L, 0L)));
        ReviewResultDto dto = review("e3", 1);
        // 几何命中保留，不被能量结论覆盖
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(List.of("gz2"), dto.hitZoneIds());
        // 能量缺口同时可观察
        assertEquals(1, dto.energyBlocks().size());
        assertEquals(1, dto.energyBlocks().get(0).pointIndex());
        assertEquals(5, dto.energyBlocks().get(0).deficit());
    }

    @Test
    void routeWithoutEnergyProfileReviewedByOriginalContract() {
        // 旧无能量航线：只按几何审查，无能量快照
        createRoute("e4", pts(0, 0, 100, 0));
        ReviewResultDto dto = review("e4", 1);
        assertEquals("CLEAR", dto.conclusion());
        assertNull(dto.energySnapshot());
        assertTrue(dto.energyBlocks().isEmpty());
    }

    @Test
    void multipleInsufficientPointsAllReportedInOrder() {
        createRoute("e5", pts(0, 0, 50, 0, 100, 0));
        // 点1：55+10=65 缺 5；点2：85+10=95 缺 35
        service.registerEnergyProfile(
                energyReq("e5", 1, 60, 10, List.of(55L, 30L), List.of(0L, 0L, 0L)));
        ReviewResultDto dto = review("e5", 1);
        assertEquals("ENERGY_BLOCKED", dto.conclusion());
        assertEquals(2, dto.energyBlocks().size());
        assertEquals(1, dto.energyBlocks().get(0).pointIndex());
        assertEquals(5, dto.energyBlocks().get(0).deficit());
        assertEquals(2, dto.energyBlocks().get(1).pointIndex());
        assertEquals(35, dto.energyBlocks().get(1).deficit());
    }

    // ============================ 登记失败分支 ============================

    @Test
    void registerRejectsCountMismatchAndInvalidValues() {
        createRoute("v1", pts(0, 0, 50, 0, 100, 0));
        // 耗能条数不等于坐标段数（2）
        ApiException segMismatch = assertThrows(ApiException.class, () ->
                service.registerEnergyProfile(energyReq("v1", 1, 100, 10,
                        List.of(30L), List.of(0L, 0L, 0L))));
        assertEquals(HttpStatus.BAD_REQUEST, segMismatch.status());
        // 备降条数不齐全（应为 3）
        ApiException divMismatch = assertThrows(ApiException.class, () ->
                service.registerEnergyProfile(energyReq("v1", 1, 100, 10,
                        List.of(30L, 30L), List.of(0L, 0L))));
        assertEquals(HttpStatus.BAD_REQUEST, divMismatch.status());
        // 非正整数段耗能
        ApiException zeroSeg = assertThrows(ApiException.class, () ->
                service.registerEnergyProfile(energyReq("v1", 1, 100, 10,
                        List.of(0L, 30L), List.of(0L, 0L, 0L))));
        assertEquals(HttpStatus.BAD_REQUEST, zeroSeg.status());
        // 负备降耗能
        ApiException negDiv = assertThrows(ApiException.class, () ->
                service.registerEnergyProfile(energyReq("v1", 1, 100, 10,
                        List.of(30L, 30L), List.of(0L, -1L, 0L))));
        assertEquals(HttpStatus.BAD_REQUEST, negDiv.status());
        // 空操作者
        ApiException noOperator = assertThrows(ApiException.class, () ->
                service.registerEnergyProfile(new EnergyProfileRegisterRequest("v1", 1, 100L, 10L,
                        List.of(30L, 30L), List.of(0L, 0L, 0L), "  ", "req-" + rid("energy"))));
        assertEquals(HttpStatus.BAD_REQUEST, noOperator.status());
        // 缺必填字段
        ApiException missing = assertThrows(ApiException.class, () ->
                service.registerEnergyProfile(new EnergyProfileRegisterRequest("v1", 1, null, 10L,
                        List.of(30L, 30L), List.of(0L, 0L, 0L), "tester", "req-" + rid("energy"))));
        assertEquals(HttpStatus.BAD_REQUEST, missing.status());
        // 全部失败均不写入档案
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM route_energy_profile", Integer.class));
    }

    @Test
    void registerRejectsMoreThanTenSegments() {
        // 12 个航点 = 11 段，超过能量档案 10 段上限
        createRoute("v2", pts(0, 0, 10, 0, 20, 0, 30, 0, 40, 0, 50, 0,
                60, 0, 70, 0, 80, 0, 90, 0, 100, 0, 110, 0));
        List<Long> segments = java.util.Collections.nCopies(11, 5L);
        List<Long> diversions = java.util.Collections.nCopies(12, 0L);
        ApiException ex = assertThrows(ApiException.class, () ->
                service.registerEnergyProfile(energyReq("v2", 1, 1000, 10, segments, diversions)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
    }

    @Test
    void registerVersionConflictNotFoundAndDuplicate() {
        createRoute("v3", pts(0, 0, 100, 0));
        // 航线不存在 → 404
        ApiException notFound = assertThrows(ApiException.class, () ->
                service.registerEnergyProfile(energyReq("ghost", 1, 100, 10,
                        List.of(30L), List.of(0L, 0L))));
        assertEquals(HttpStatus.NOT_FOUND, notFound.status());
        // 版本不匹配 → 409
        ApiException conflict = assertThrows(ApiException.class, () ->
                service.registerEnergyProfile(energyReq("v3", 9, 100, 10,
                        List.of(30L), List.of(0L, 0L))));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        // 同版本重复登记 → 409，档案变化必须先修订航线
        service.registerEnergyProfile(energyReq("v3", 1, 100, 10, List.of(30L), List.of(0L, 0L)));
        ApiException dup = assertThrows(ApiException.class, () ->
                service.registerEnergyProfile(energyReq("v3", 1, 200, 10,
                        List.of(30L), List.of(0L, 0L))));
        assertEquals(HttpStatus.CONFLICT, dup.status());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM route_energy_profile", Integer.class));
    }

    @Test
    void energyValuesBoundedAndSumsExact() {
        // 边界值精确求和：10 段 × 1e11 = 1e12，恰好等于出发能量，不溢出、不误判
        createRoute("v4", pts(0, 0, 10, 0, 20, 0, 30, 0, 40, 0, 50, 0,
                60, 0, 70, 0, 80, 0, 90, 0, 100, 0));
        List<Long> segments = java.util.Collections.nCopies(10, 100_000_000_000L);
        List<Long> diversions = java.util.Collections.nCopies(11, 0L);
        service.registerEnergyProfile(
                energyReq("v4", 1, 1_000_000_000_000L, 0, segments, diversions));
        ReviewResultDto ok = review("v4", 1);
        assertEquals("CLEAR", ok.conclusion());
        assertEquals(1_000_000_000_000L,
                ok.energySnapshot().points().get(10).cumulativeConsumption());
        // 超出单项上限 → 400
        ApiException tooBig = assertThrows(ApiException.class, () ->
                service.registerEnergyProfile(new EnergyProfileRegisterRequest("v4", 1,
                        1_000_000_000_001L, 0L, segments, diversions,
                        "tester", "req-" + rid("energy"))));
        assertEquals(HttpStatus.BAD_REQUEST, tooBig.status());
    }

    // ============================ 幂等与 STALE ============================

    @Test
    void registerIdempotentReplayAndParamMismatch() {
        createRoute("i1", pts(0, 0, 100, 0));
        String requestId = "energy-idem-1";
        EnergyProfileRegisterRequest req = new EnergyProfileRegisterRequest("i1", 1, 100L, 10L,
                List.of(30L), List.of(0L, 0L), "tester", requestId);
        MutationResponse first = service.registerEnergyProfile(req);
        assertFalse(first.replayed());
        // 同键同参重放首次完整响应
        MutationResponse replay = service.registerEnergyProfile(
                new EnergyProfileRegisterRequest("i1", 1, 100L, 10L,
                        List.of(30L), List.of(0L, 0L), "tester", requestId));
        assertTrue(replay.replayed());
        assertEquals(objectMapper.convertValue(first.data(), Map.class),
                objectMapper.convertValue(replay.data(), Map.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM route_energy_profile", Integer.class));
        // 同键异参 → 409
        ApiException mismatch = assertThrows(ApiException.class, () ->
                service.registerEnergyProfile(new EnergyProfileRegisterRequest("i1", 1, 200L, 10L,
                        List.of(30L), List.of(0L, 0L), "tester", requestId)));
        assertEquals(HttpStatus.CONFLICT, mismatch.status());
    }

    @Test
    void failedRegisterDoesNotConsumeIdempotencyKey() {
        createRoute("i2", pts(0, 0, 100, 0));
        String requestId = "energy-idem-fail";
        // 首次因条数不匹配失败
        ApiException failed = assertThrows(ApiException.class, () ->
                service.registerEnergyProfile(new EnergyProfileRegisterRequest("i2", 1, 100L, 10L,
                        List.of(30L, 30L), List.of(0L, 0L), "tester", requestId)));
        assertEquals(HttpStatus.BAD_REQUEST, failed.status());
        assertNull(jdbc.query(
                "SELECT request_id FROM request_dedup WHERE request_id = ?",
                (rs, n) -> rs.getString(1), requestId).stream().findFirst().orElse(null));
        // 键未被占用：修正参数后同键成功
        MutationResponse ok = service.registerEnergyProfile(
                new EnergyProfileRegisterRequest("i2", 1, 100L, 10L,
                        List.of(30L), List.of(0L, 0L), "tester", requestId));
        assertFalse(ok.replayed());
    }

    @Test
    void reviewBeforeProfileRegistrationBecomesStaleAfterRegister() {
        // 先按原契约审查（无档案），再补登档案：旧结论不再代表当前状态
        createRoute("s1", pts(0, 0, 100, 0));
        ReviewResultDto before = review("s1", 1);
        assertEquals("CLEAR", before.conclusion());
        assertEquals("CLEAR", service.getCurrentReview("s1").conclusion());

        service.registerEnergyProfile(energyReq("s1", 1, 100, 10, List.of(95L), List.of(0L, 0L)));
        assertEquals("STALE", service.getCurrentReview("s1").conclusion());
        // 历史结论不变
        assertEquals("CLEAR", service.getReview(before.reviewId()).conclusion());
        // 重新审查 → ENERGY_BLOCKED，恢复为当前结论
        ReviewResultDto after = review("s1", 1);
        assertEquals("ENERGY_BLOCKED", after.conclusion());
        assertEquals("ENERGY_BLOCKED", service.getCurrentReview("s1").conclusion());
    }

    // ============================ 并发 ============================

    @Test
    void concurrentRegisterSameRequestIdPlaysBackOneOutcome() throws Exception {
        createRoute("c1", pts(0, 0, 100, 0));
        String requestId = "energy-concurrent-key";
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.registerEnergyProfile(new EnergyProfileRegisterRequest(
                            "c1", 1, 100L, 10L, List.of(30L), List.of(0L, 0L),
                            "tester", requestId));
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
            assertEquals(1, firstCount, "仅一次登记真正生效");
            assertEquals(n - 1, replayCount);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM route_energy_profile", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                    Integer.class, requestId));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentRegisterAndReviewAreSerialized() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                String routeId = "c2-" + i;
                createRoute(routeId, pts(0, 0, 100, 0));
                CyclicBarrier barrier = new CyclicBarrier(2);
                String id = routeId;
                Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.review(new ReviewRequest(id, 1, 0L, "req-rv-" + id));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> registerFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.registerEnergyProfile(new EnergyProfileRegisterRequest(
                                id, 1, 100L, 10L, List.of(95L), List.of(0L, 0L),
                                "tester", "req-eg-" + id));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object registerResult = registerFuture.get(15, TimeUnit.SECONDS);
                assertTrue(registerResult instanceof MutationResponse, "登记必须成功");
                assertTrue(reviewResult instanceof MutationResponse, "审查必须成功一次");
                ReviewResultDto dto = dataOf((MutationResponse) reviewResult);
                if (dto.energySnapshot() == null) {
                    // 审查先拿锁：看到的是登记前的一致快照，按原契约 CLEAR
                    assertEquals("CLEAR", dto.conclusion());
                } else {
                    // 登记先提交：审查必须使用已登记档案，得出 ENERGY_BLOCKED
                    assertEquals("ENERGY_BLOCKED", dto.conclusion());
                    assertEquals(1, dto.energyBlocks().size());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
