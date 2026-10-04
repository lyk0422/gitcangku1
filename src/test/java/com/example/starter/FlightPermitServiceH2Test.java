package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.PermitCreateRequest;
import com.example.starter.api.dto.PermitResultDto;
import com.example.starter.api.dto.PermitRevokeRequest;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.api.dto.ZoneRevokeRequest;
import com.example.starter.service.AirspaceReviewService;
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
 * 两点匀速航线运动间隔许可的 H2 数据库测试（MODE=MySQL）。
 * 覆盖主流程、失败分支、间隔边界、活动许可上限、撤销语义、
 * 幂等重放与真实并发互斥；业务与去重原子提交。
 */
@SpringBootTest
@DisplayName("运动间隔许可 H2 业务测试")
class FlightPermitServiceH2Test {

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
        jdbc.update("DELETE FROM flight_permit");
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

    private void createRoute(String routeId, int x1, int y1, int x2, int y2) {
        service.createRoute(new RouteCreateRequest(routeId,
                List.of(new RoutePointDto(x1, y1), new RoutePointDto(x2, y2)),
                "req-" + rid("route")));
    }

    private PermitCreateRequest permitReq(String routeId, long start, long end) {
        return new PermitCreateRequest("req-" + rid("permit"), "tester",
                routeId, 1, start, end);
    }

    private PermitResultDto permitData(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), PermitResultDto.class);
    }

    private int permitCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM flight_permit", Integer.class);
    }

    // ============================ 主流程 ============================

    @Test
    @DisplayName("主流程：创建许可快照轨迹，查询原样返回")
    void createPermitHappyPath() {
        createRoute("ra", 0, 0, 100, 0);
        MutationResponse resp = service.createPermit(permitReq("ra", 1000, 1010));
        assertFalse(resp.replayed());
        PermitResultDto dto = permitData(resp);
        assertNotNull(dto.permitId());
        assertEquals("ra", dto.routeId());
        assertEquals(1, dto.routeVersion());
        assertEquals(1000L, dto.startSecond());
        assertEquals(1010L, dto.endSecond());
        assertEquals("ACTIVE", dto.status());
        assertEquals(0.0, dto.ax());
        assertEquals(100.0, dto.bx());
        assertEquals(1, permitCount());
        // 查询返回同一不可变快照
        PermitResultDto queried = service.getPermit(dto.permitId());
        assertEquals(dto, queried);
    }

    @Test
    @DisplayName("合成对向直线第 5.5 秒相遇：拒绝第二许可；推迟到前一结束后成功")
    void opposingLinesMeetingAtHalfSecondRejectedThenDelayedAccepted() {
        // 运动1：(0,0)→(100,0)，t∈[0,10)
        createRoute("east", 0, 0, 100, 0);
        PermitResultDto first = permitData(service.createPermit(permitReq("east", 0, 10)));
        // 运动2：(100,0)→(0,0)，t∈[1,11)：5.5 秒相遇，端点相隔 90~100 米
        createRoute("west", 100, 0, 0, 0);
        ApiException conflict = assertThrows(ApiException.class,
                () -> service.createPermit(permitReq("west", 1, 11)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, conflict.status());
        assertEquals("PERMIT_SEPARATION_CONFLICT", conflict.code());
        assertEquals(List.of(first.permitId()),
                conflict.details().get("conflictPermitIds"));
        // 冲突不新增许可
        assertEquals(1, permitCount());

        // 推迟到前一许可结束后（左闭右开，端点相接无共同占用）→ 成功
        MutationResponse delayed = service.createPermit(permitReq("west", 10, 20));
        assertEquals("ACTIVE", permitData(delayed).status());
        assertEquals(2, permitCount());
    }

    @Test
    @DisplayName("恰好 10 米平行航线合法，9 米拒绝")
    void separationBoundary() {
        createRoute("p1", 0, 0, 100, 0);
        service.createPermit(permitReq("p1", 0, 100));
        // 恰好 10 米 → 合法
        createRoute("p2", 0, 10, 100, 10);
        MutationResponse ok = service.createPermit(permitReq("p2", 0, 100));
        assertEquals("ACTIVE", permitData(ok).status());
        // 9 米 → 冲突
        createRoute("p3", 0, 9, 100, 9);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.createPermit(permitReq("p3", 0, 100)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("PERMIT_SEPARATION_CONFLICT", ex.code());
        assertEquals(2, permitCount());
    }

    // ============================ 失败分支 ============================

    @Test
    @DisplayName("失败分支：区间非法 400、航线缺失 404、版本不符 409、非两点航线 400")
    void validationFailures() {
        createRoute("v1", 0, 0, 100, 0);
        ApiException badInterval = assertThrows(ApiException.class,
                () -> service.createPermit(permitReq("v1", 100, 100)));
        assertEquals(HttpStatus.BAD_REQUEST, badInterval.status());
        assertEquals("INVALID_PERMIT_INTERVAL", badInterval.code());

        ApiException noRoute = assertThrows(ApiException.class,
                () -> service.createPermit(permitReq("ghost", 0, 10)));
        assertEquals(HttpStatus.NOT_FOUND, noRoute.status());

        ApiException badVersion = assertThrows(ApiException.class, () ->
                service.createPermit(new PermitCreateRequest(
                        "req-" + rid("permit"), "tester", "v1", 9, 0L, 10L)));
        assertEquals(HttpStatus.CONFLICT, badVersion.status());
        assertEquals("VERSION_CONFLICT", badVersion.code());

        service.createRoute(new RouteCreateRequest("v3",
                List.of(new RoutePointDto(0, 0), new RoutePointDto(50, 0),
                        new RoutePointDto(100, 0)), "req-" + rid("route")));
        ApiException threePoints = assertThrows(ApiException.class,
                () -> service.createPermit(permitReq("v3", 0, 10)));
        assertEquals(HttpStatus.BAD_REQUEST, threePoints.status());
        assertEquals("PERMIT_REQUIRES_TWO_POINT_ROUTE", threePoints.code());
        assertEquals(0, permitCount());
    }

    @Test
    @DisplayName("禁飞审查：轨迹命中有效禁飞区 422，撤销后可许可")
    void blockedByNoFlyZone() {
        createRoute("zb", 0, 10, 100, 10);
        service.createZone(new ZoneCreateRequest("zz", 40, 5, 60, 15, "req-" + rid("zone")));
        ApiException blocked = assertThrows(ApiException.class,
                () -> service.createPermit(permitReq("zb", 0, 10)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, blocked.status());
        assertEquals("PERMIT_BLOCKED_BY_ZONE", blocked.code());
        assertEquals(List.of("zz"), blocked.details().get("hitZoneIds"));
        assertEquals(0, permitCount());

        service.revokeZone(new ZoneRevokeRequest("zz", "req-" + rid("revoke")));
        MutationResponse ok = service.createPermit(permitReq("zb", 0, 10));
        assertEquals("ACTIVE", permitData(ok).status());
    }

    @Test
    @DisplayName("活动许可上限 20 条：第 21 条 422")
    void activePermitLimit() {
        for (int i = 0; i < 20; i++) {
            String routeId = "lim" + i;
            createRoute(routeId, 0, i * 20, 100, i * 20);
            service.createPermit(permitReq(routeId, 0, 1000));
        }
        assertEquals(20, permitCount());
        createRoute("lim-overflow", 0, 9999, 100, 9999);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.createPermit(permitReq("lim-overflow", 0, 1000)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("PERMIT_LIMIT_EXCEEDED", ex.code());
        assertEquals(20, permitCount());
    }
    // ============================ 撤销语义 ============================

    @Test
    @DisplayName("撤销释放未来占用、历史轨迹保留；重复撤销 409、撤销缺失 404")
    void revokeReleasesOccupancyAndKeepsHistory() {
        createRoute("rk1", 0, 0, 100, 0);
        PermitResultDto first = permitData(service.createPermit(permitReq("rk1", 0, 10)));
        // 对向冲突许可被拒绝
        createRoute("rk2", 100, 0, 0, 0);
        assertThrows(ApiException.class, () -> service.createPermit(permitReq("rk2", 1, 11)));

        // 撤销前一许可 → 释放占用，冲突许可可成功
        MutationResponse revoked = service.revokePermit(
                new PermitRevokeRequest("req-" + rid("revoke"), "tester", first.permitId()));
        assertEquals("REVOKED", permitData(revoked).status());
        MutationResponse now = service.createPermit(permitReq("rk2", 1, 11));
        assertEquals("ACTIVE", permitData(now).status());

        // 历史轨迹保留：撤销后仍可查询原快照
        PermitResultDto history = service.getPermit(first.permitId());
        assertEquals("REVOKED", history.status());
        assertEquals(0L, history.startSecond());
        assertEquals(10L, history.endSecond());
        assertEquals(0.0, history.ax());
        assertEquals(100.0, history.bx());

        // 重复撤销 409；撤销缺失 404
        ApiException twice = assertThrows(ApiException.class, () -> service.revokePermit(
                new PermitRevokeRequest("req-" + rid("revoke2"), "tester", first.permitId())));
        assertEquals(HttpStatus.CONFLICT, twice.status());
        ApiException missing = assertThrows(ApiException.class, () -> service.revokePermit(
                new PermitRevokeRequest("req-" + rid("revoke3"), "tester", "pm_ghost")));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    @Test
    @DisplayName("航线替换不移动已许可轨迹，新申请必须引用新版本")
    void routeReplaceDoesNotMovePermittedTrajectory() {
        createRoute("mv", 0, 0, 100, 0);
        PermitResultDto permit = permitData(service.createPermit(permitReq("mv", 0, 10)));
        // 替换航线到 y=50
        service.replaceRoute(new RouteReplaceRequest("mv", 1,
                List.of(new RoutePointDto(0, 50), new RoutePointDto(100, 50)),
                "req-" + rid("replace")));
        // 已许可轨迹不变
        PermitResultDto kept = service.getPermit(permit.permitId());
        assertEquals(0.0, kept.ay());
        assertEquals(1, kept.routeVersion());
        // 旧版本新申请 409
        ApiException stale = assertThrows(ApiException.class, () ->
                service.createPermit(new PermitCreateRequest(
                        "req-" + rid("permit"), "tester", "mv", 1, 20L, 30L)));
        assertEquals(HttpStatus.CONFLICT, stale.status());
        // 新版本申请按新轨迹评估：y=50 与已许可 y=0 相距 50 米 → 成功
        MutationResponse ok = service.createPermit(new PermitCreateRequest(
                "req-" + rid("permit"), "tester", "mv", 2, 0L, 10L));
        assertEquals("ACTIVE", permitData(ok).status());
    }

    // ============================ 幂等 ============================

    @Test
    @DisplayName("幂等：同键同参重放首次完整响应，异参 409，失败不占键")
    void idempotencySemantics() {
        createRoute("id1", 0, 0, 100, 0);
        String requestId = "permit-idem-1";
        PermitCreateRequest req = new PermitCreateRequest(
                requestId, "tester", "id1", 1, 0L, 10L);
        MutationResponse first = service.createPermit(req);
        assertFalse(first.replayed());
        // 同键同参重放：同一许可，不新增记录
        MutationResponse replay = service.createPermit(
                new PermitCreateRequest(requestId, "tester", "id1", 1, 0L, 10L));
        assertTrue(replay.replayed());
        assertEquals(permitData(first).permitId(), permitData(replay).permitId());
        assertEquals(1, permitCount());
        // 同键异参（不同操作者/参数）→ 409
        ApiException mismatch = assertThrows(ApiException.class, () ->
                service.createPermit(new PermitCreateRequest(
                        requestId, "other-operator", "id1", 1, 0L, 10L)));
        assertEquals(HttpStatus.CONFLICT, mismatch.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", mismatch.code());

        // 失败不占键：先以冲突参数失败，再同键成功
        createRoute("id2", 100, 0, 0, 0);
        String failKey = "permit-idem-fail";
        ApiException conflict = assertThrows(ApiException.class, () ->
                service.createPermit(new PermitCreateRequest(
                        failKey, "tester", "id2", 1, 1L, 11L)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, conflict.status());
        assertNull(jdbc.queryForList(
                "SELECT request_id FROM request_dedup WHERE request_id = ?", failKey)
                .stream().findFirst().orElse(null));
        MutationResponse ok = service.createPermit(new PermitCreateRequest(
                failKey, "tester", "id2", 1, 100L, 110L));
        assertFalse(ok.replayed());
    }
    // ============================ 并发 ============================

    @Test
    @DisplayName("并发冲突申请：串行化后至多保存互相安全的集合（恰好 1 条成功）")
    void concurrentConflictingPermitsOnlyOneSaved() throws Exception {
        int n = 6;
        for (int i = 0; i < n; i++) {
            createRoute("cc" + i, 0, 0, 100, 0);
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier barrier = new CyclicBarrier(n);
        try {
            List<Future<Object>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                String routeId = "cc" + i;
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.createPermit(new PermitCreateRequest(
                                "req-cc-" + routeId, "tester", routeId, 1, 0L, 100L));
                    } catch (ApiException ex) {
                        return ex;
                    }
                }));
            }
            int success = 0;
            int conflicts = 0;
            for (Future<Object> f : futures) {
                Object result = f.get(15, TimeUnit.SECONDS);
                if (result instanceof MutationResponse) {
                    success++;
                } else {
                    ApiException ex = (ApiException) result;
                    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
                    assertEquals("PERMIT_SEPARATION_CONFLICT", ex.code());
                    conflicts++;
                }
            }
            assertEquals(1, success, "同一轨迹同一区间只能有一条许可成功");
            assertEquals(n - 1, conflicts);
            assertEquals(1, permitCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发安全申请：互不冲突的全部保存")
    void concurrentSafePermitsAllSaved() throws Exception {
        int n = 4;
        for (int i = 0; i < n; i++) {
            createRoute("cs" + i, 0, i * 20, 100, i * 20);
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier barrier = new CyclicBarrier(n);
        try {
            List<Future<Object>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                String routeId = "cs" + i;
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.createPermit(new PermitCreateRequest(
                                "req-cs-" + routeId, "tester", routeId, 1, 0L, 100L));
                    } catch (ApiException ex) {
                        return ex;
                    }
                }));
            }
            int success = 0;
            for (Future<Object> f : futures) {
                Object result = f.get(15, TimeUnit.SECONDS);
                assertTrue(result instanceof MutationResponse,
                        "互相安全的许可必须全部成功: " + result);
                success++;
            }
            assertEquals(n, success);
            assertEquals(n, permitCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("同键并发：恰好一次业务生效，其余重放，不泄露唯一键异常")
    void concurrentSameRequestIdPlaysBackOneOutcome() throws Exception {
        createRoute("ck", 0, 0, 100, 0);
        String requestId = "permit-concurrent-key";
        int n = 5;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier barrier = new CyclicBarrier(n);
        try {
            List<Future<MutationResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.createPermit(new PermitCreateRequest(
                            requestId, "tester", "ck", 1, 0L, 100L));
                }));
            }
            int firstCount = 0;
            int replayCount = 0;
            String permitId = null;
            for (Future<MutationResponse> f : futures) {
                MutationResponse resp = f.get(15, TimeUnit.SECONDS);
                if (resp.replayed()) {
                    replayCount++;
                } else {
                    firstCount++;
                }
                String id = permitData(resp).permitId();
                if (permitId == null) {
                    permitId = id;
                } else {
                    assertEquals(permitId, id, "重放必须返回首次的同一许可");
                }
            }
            assertEquals(1, firstCount);
            assertEquals(n - 1, replayCount);
            assertEquals(1, permitCount());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                    Integer.class, requestId));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("建区与许可并发：许可要么看到一致旧状态成功，要么 422，不产生错配")
    void concurrentZoneCreateAndPermitCreateAreSerialized() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                String routeId = "zc" + i;
                String zoneId = "zczone" + i;
                int y = 500 + i * 100;
                createRoute(routeId, 0, y, 100, y);
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Object> permitFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.createPermit(new PermitCreateRequest(
                                "req-zcp-" + routeId, "tester", routeId, 1, 0L, 100L));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> zoneFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.createZone(new ZoneCreateRequest(
                                zoneId, 40, y - 5, 60, y + 5, "req-zcz-" + zoneId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Object permitResult = permitFuture.get(15, TimeUnit.SECONDS);
                Object zoneResult = zoneFuture.get(15, TimeUnit.SECONDS);
                assertTrue(zoneResult instanceof MutationResponse, "建区必须成功");
                if (permitResult instanceof MutationResponse) {
                    // 许可先拿锁：看到建区前一致状态，合法成功
                    assertEquals("ACTIVE", permitData((MutationResponse) permitResult).status());
                } else {
                    // 建区先提交：许可必须被禁飞审查拦截
                    ApiException ex = (ApiException) permitResult;
                    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
                    assertEquals("PERMIT_BLOCKED_BY_ZONE", ex.code());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}