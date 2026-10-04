package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.PermitCreateRequest;
import com.example.starter.api.dto.PermitResult;
import com.example.starter.api.dto.PermitResultDto;
import com.example.starter.api.dto.PermitRevokeRequest;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.domain.Motion;
import com.example.starter.repo.PermitPo;
import com.example.starter.repo.PermitRepository;
import com.example.starter.service.AirspaceReviewService;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运动间隔许可的 H2 数据库测试（MODE=MySQL）。
 * 覆盖主流程、合成对向相遇场景、禁飞审查、版本证据、上限、幂等与真实并发。
 */
@SpringBootTest
class PermitServiceH2Test {

    @Autowired
    private AirspaceReviewService service;
    @Autowired
    private PermitRepository permitRepo;
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
        jdbc.update("DELETE FROM permit");
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

    private void createRoute(String routeId, int... xy) {
        service.createRoute(new RouteCreateRequest(routeId, pts(xy), "req-" + rid("route")));
    }

    private PermitCreateRequest permitReq(String operator, String routeId, int version,
                                          long start, long end) {
        return new PermitCreateRequest("req-" + rid("permit"), operator, routeId, version, start, end);
    }

    private PermitResult dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), PermitResult.class);
    }

    // ============================ 主流程 ============================

    @Test
    void createGetRevokeKeepsHistoryAndReleasesOccupancy() {
        createRoute("ra", 0, 0, 100, 0);
        createRoute("rb", 100, 0, 0, 0);

        MutationResponse created = service.createPermit(permitReq("alice", "ra", 1, 0, 11));
        assertFalse(created.replayed());
        PermitResult permit = dataOf(created);
        assertEquals("ACTIVE", permit.status());
        assertEquals("alice", permit.operator());
        assertEquals(0L, permit.airspaceVersion());

        // 查询返回许可时的不可变轨迹快照
        PermitResultDto dto = service.getPermit(permit.permitId());
        assertEquals(List.of(new RoutePointDto(0, 0), new RoutePointDto(100, 0)),
                dto.pointsSnapshot());
        assertNull(dto.revokedAt());

        // 占用期间对向许可冲突；撤销后释放未来占用
        ApiException conflict = assertThrows(ApiException.class,
                () -> service.createPermit(permitReq("bob", "rb", 1, 0, 11)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, conflict.status());

        MutationResponse revoked = service.revokePermit(
                new PermitRevokeRequest("req-" + rid("revoke"), permit.permitId()));
        assertEquals("REVOKED", dataOf(revoked).status());

        // 历史轨迹保留：仍可查询，状态 REVOKED，撤销时间已记录
        PermitResultDto history = service.getPermit(permit.permitId());
        assertEquals("REVOKED", history.status());
        assertNotNull(history.revokedAt());
        assertEquals(List.of(new RoutePointDto(0, 0), new RoutePointDto(100, 0)),
                history.pointsSnapshot());

        // 撤销释放占用：同时段对向许可现在可以成功
        MutationResponse after = service.createPermit(permitReq("bob", "rb", 1, 0, 11));
        assertEquals("ACTIVE", dataOf(after).status());

        // 重复撤销 409；撤销不存在的许可 404
        ApiException twice = assertThrows(ApiException.class, () -> service.revokePermit(
                new PermitRevokeRequest("req-" + rid("revoke2"), permit.permitId())));
        assertEquals(HttpStatus.CONFLICT, twice.status());
        ApiException missing = assertThrows(ApiException.class, () -> service.revokePermit(
                new PermitRevokeRequest("req-" + rid("revoke3"), "pm_ghost")));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
        ApiException getMissing = assertThrows(ApiException.class,
                () -> service.getPermit("pm_ghost"));
        assertEquals(HttpStatus.NOT_FOUND, getMissing.status());
    }

    @Test
    void opposingHeadOnMeetingAtFivePointFiveSecondsIsRejectedThenDelayedSucceeds() {
        // 合成场景：两条对向直线，端点两两相隔 100 米，区间 [0,11) 内第 5.5 秒相遇
        createRoute("ra", 0, 0, 100, 0);
        createRoute("rb", 100, 0, 0, 0);

        PermitResult first = dataOf(service.createPermit(permitReq("alice", "ra", 1, 0, 11)));
        assertEquals("ACTIVE", first.status());

        // 端点虽相隔 100 米，但中途相遇：第二许可必须被拒绝且返回冲突许可 ID
        ApiException ex = assertThrows(ApiException.class,
                () -> service.createPermit(permitReq("bob", "rb", 1, 0, 11)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("PERMIT_SEPARATION_CONFLICT", ex.code());
        assertEquals(List.of(first.permitId()), ex.conflictPermitIds());
        // 拒绝不新增许可
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM permit", Integer.class));

        // 推迟到前一许可结束后（左闭右开，端点相接无共同占用）可成功
        PermitResult delayed = dataOf(service.createPermit(permitReq("bob", "rb", 1, 11, 22)));
        assertEquals("ACTIVE", delayed.status());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM permit", Integer.class));
    }

    @Test
    void exactlyTenMetersSeparationIsAccepted() {
        createRoute("ra", 0, 0, 100, 0);
        createRoute("rc", 100, 10, 0, 10);
        service.createPermit(permitReq("alice", "ra", 1, 0, 11));
        // 对向但横向恰好错开 10 米：合法
        MutationResponse ok = service.createPermit(permitReq("carol", "rc", 1, 0, 11));
        assertEquals("ACTIVE", dataOf(ok).status());
    }
    // ============================ 失败分支 ============================

    @Test
    void permitRouteMustPassNoFlyReview() {
        createRoute("rz", 0, 0, 100, 0);
        service.createZone(new ZoneCreateRequest("z1", 40, -5, 60, 5, "req-" + rid("zone")));
        ApiException ex = assertThrows(ApiException.class,
                () -> service.createPermit(permitReq("alice", "rz", 1, 0, 11)));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("PERMIT_ROUTE_BLOCKED", ex.code());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM permit", Integer.class));
    }

    @Test
    void routeUpdateDoesNotMovePermittedTrajectoryAndNewPermitNeedsNewVersion() {
        createRoute("ru", 0, 0, 100, 0);
        PermitResult old = dataOf(service.createPermit(permitReq("alice", "ru", 1, 0, 11)));

        // 航线替换到 y=50：已许可轨迹不移动
        service.replaceRoute(new RouteReplaceRequest(
                "ru", 1, pts(0, 50, 100, 50), "req-" + rid("replace")));

        // 旧许可证据不改写：快照仍是 y=0 的轨迹
        PermitResultDto history = service.getPermit(old.permitId());
        assertEquals(1, history.routeVersion());
        assertEquals(List.of(new RoutePointDto(0, 0), new RoutePointDto(100, 0)),
                history.pointsSnapshot());

        // 新申请引用旧版本 → 409
        ApiException stale = assertThrows(ApiException.class,
                () -> service.createPermit(permitReq("bob", "ru", 1, 0, 11)));
        assertEquals(HttpStatus.CONFLICT, stale.status());
        assertEquals("VERSION_CONFLICT", stale.code());

        // 引用新版本可成功（新轨迹 y=50 与旧轨迹 y=0 相距 50 米）
        MutationResponse ok = service.createPermit(permitReq("bob", "ru", 2, 0, 11));
        assertEquals(2, dataOf(ok).routeVersion());
    }

    @Test
    void invalidIntervalRejectedAndFailureDoesNotConsumeKey() {
        createRoute("ri", 0, 0, 100, 0);
        String requestId = "permit-fail-key";
        // start >= end → 400
        ApiException bad = assertThrows(ApiException.class, () -> service.createPermit(
                new PermitCreateRequest(requestId, "alice", "ri", 1, 11L, 11L)));
        assertEquals(HttpStatus.BAD_REQUEST, bad.status());
        assertEquals("INVALID_PERMIT_INTERVAL", bad.code());
        // 失败不占键：同键可用于合法参数
        MutationResponse ok = service.createPermit(
                new PermitCreateRequest(requestId, "alice", "ri", 1, 0L, 11L));
        assertFalse(ok.replayed());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM permit", Integer.class));

        // 航线不存在 404；非两点航线 400
        ApiException noRoute = assertThrows(ApiException.class,
                () -> service.createPermit(permitReq("alice", "ghost", 1, 0, 11)));
        assertEquals(HttpStatus.NOT_FOUND, noRoute.status());
        createRoute("r3p", 0, 0, 50, 0, 100, 0);
        ApiException notTwoPoint = assertThrows(ApiException.class,
                () -> service.createPermit(permitReq("alice", "r3p", 1, 0, 11)));
        assertEquals(HttpStatus.BAD_REQUEST, notTwoPoint.status());
        assertEquals("PERMIT_REQUIRES_TWO_POINT_ROUTE", notTwoPoint.code());
    }

    @Test
    void activePermitLimitIsTwenty() {
        createRoute("rl", 0, 0, 100, 0);
        // 20 条首尾相接的许可（端点相接无共同占用）全部合法
        for (int i = 0; i < 20; i++) {
            long start = i * 10L;
            MutationResponse ok = service.createPermit(
                    permitReq("op" + i, "rl", 1, start, start + 10));
            assertEquals("ACTIVE", dataOf(ok).status());
        }
        assertEquals(20, jdbc.queryForObject(
                "SELECT COUNT(*) FROM permit WHERE status = 'ACTIVE'", Integer.class));
        // 第 21 条活动许可超出上限
        ApiException ex = assertThrows(ApiException.class,
                () -> service.createPermit(permitReq("op20", "rl", 1, 200, 210)));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("PERMIT_LIMIT_EXCEEDED", ex.code());
    }

    // ============================ 幂等 ============================

    @Test
    void sameRequestReplaysOriginalPermitAndDifferentParamsConflict() {
        createRoute("rm", 0, 0, 100, 0);
        String requestId = "permit-replay-1";
        PermitCreateRequest req = new PermitCreateRequest(requestId, "alice", "rm", 1, 0L, 11L);
        MutationResponse first = service.createPermit(req);
        assertFalse(first.replayed());

        // 同键同参重放：返回首次完整响应，不新增许可
        MutationResponse replay = service.createPermit(
                new PermitCreateRequest(requestId, "alice", "rm", 1, 0L, 11L));
        assertTrue(replay.replayed());
        assertEquals(dataOf(first).permitId(), dataOf(replay).permitId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM permit", Integer.class));

        // 同键异参 → 409；同键异种操作 → 409
        ApiException diffParams = assertThrows(ApiException.class, () -> service.createPermit(
                new PermitCreateRequest(requestId, "alice", "rm", 1, 0L, 12L)));
        assertEquals(HttpStatus.CONFLICT, diffParams.status());
        ApiException diffKind = assertThrows(ApiException.class, () -> service.revokePermit(
                new PermitRevokeRequest(requestId, dataOf(first).permitId())));
        assertEquals(HttpStatus.CONFLICT, diffKind.status());
    }
    // ============================ 并发 ============================

    @Test
    void concurrentApplicationsKeepOnlyMutuallySafeSet() throws Exception {
        // 冲突组：6 个线程在同向/对向航线上申请同一时段 [0,11)，两两冲突
        createRoute("cx", 0, 0, 100, 0);
        createRoute("cy", 100, 0, 0, 0);
        // 安全组：2 个线程申请错开的时段，与任何 [0,11) 许可都无共同占用
        int conflicting = 6;
        int safe = 2;
        int total = conflicting + safe;
        CyclicBarrier barrier = new CyclicBarrier(total);
        ExecutorService pool = Executors.newFixedThreadPool(total);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < conflicting; i++) {
                String routeId = (i % 2 == 0) ? "cx" : "cy";
                String reqId = "req-conflict-" + i;
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.createPermit(new PermitCreateRequest(
                                reqId, "op", routeId, 1, 0L, 11L));
                    } catch (ApiException ex) {
                        return ex;
                    }
                }));
            }
            for (int i = 0; i < safe; i++) {
                String routeId = (i % 2 == 0) ? "cx" : "cy";
                String reqId = "req-safe-" + i;
                long start = 11L * (i + 1);
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.createPermit(new PermitCreateRequest(
                                reqId, "op", routeId, 1, start, start + 11));
                    } catch (ApiException ex) {
                        return ex;
                    }
                }));
            }
            int conflictSuccess = 0;
            int conflictRejected = 0;
            int safeSuccess = 0;
            for (int i = 0; i < total; i++) {
                Object result = futures.get(i).get(15, TimeUnit.SECONDS);
                if (i < conflicting) {
                    if (result instanceof MutationResponse) {
                        conflictSuccess++;
                    } else {
                        ApiException ex = (ApiException) result;
                        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
                        assertEquals("PERMIT_SEPARATION_CONFLICT", ex.code());
                        conflictRejected++;
                    }
                } else if (result instanceof MutationResponse) {
                    safeSuccess++;
                }
            }
            // 两两冲突的申请至多保存一条；安全申请全部保存
            assertEquals(1, conflictSuccess);
            assertEquals(conflicting - 1, conflictRejected);
            assertEquals(safe, safeSuccess);

            // 最终数据库中的活动许可集合必须两两安全（用同一连续时间算法复核）
            List<PermitPo> active = permitRepo.findActivePermits();
            assertEquals(1 + safe, active.size());
            for (int i = 0; i < active.size(); i++) {
                for (int j = i + 1; j < active.size(); j++) {
                    PermitPo p = active.get(i);
                    PermitPo q = active.get(j);
                    assertFalse(Motion.conflicts(
                            p.pointsSnapshot().get(0).x(), p.pointsSnapshot().get(0).y(),
                            p.pointsSnapshot().get(1).x(), p.pointsSnapshot().get(1).y(),
                            p.startEpochSecond(), p.endEpochSecond(),
                            q.pointsSnapshot().get(0).x(), q.pointsSnapshot().get(0).y(),
                            q.pointsSnapshot().get(1).x(), q.pointsSnapshot().get(1).y(),
                            q.startEpochSecond(), q.endEpochSecond()),
                            "活动许可 " + p.permitId() + " 与 " + q.permitId() + " 间隔不足");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentPermitAndZoneChangeReadConsistentVersion() throws Exception {
        int iterations = 8;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < iterations; i++) {
                String routeId = "pz-" + i;
                String zoneId = "zz-" + i;
                int y = 500 + i * 100;
                createRoute(routeId, 0, y, 100, y);
                long versionBefore = jdbc.queryForObject(
                        "SELECT global_version FROM airspace_meta WHERE id=1", Long.class);
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Object> permitFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.createPermit(new PermitCreateRequest(
                                "req-p-" + routeId, "op", routeId, 1, 0L, 11L));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> zoneFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.createZone(new ZoneCreateRequest(
                                zoneId, 40, y - 5, 60, y + 5, "req-z-" + zoneId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Object permitResult = permitFuture.get(15, TimeUnit.SECONDS);
                Object zoneResult = zoneFuture.get(15, TimeUnit.SECONDS);
                assertTrue(zoneResult instanceof MutationResponse, "建区必须成功");
                if (permitResult instanceof MutationResponse mr) {
                    // 许可先拿锁：读到建区前的一致空域版本，证据版本必须等于建区前版本
                    assertEquals(versionBefore, dataOf(mr).airspaceVersion());
                } else {
                    // 建区先提交：许可必须因命中新区域被拒绝，不能携带混合版本成功
                    ApiException ex = (ApiException) permitResult;
                    assertEquals(HttpStatus.CONFLICT, ex.status());
                    assertEquals("PERMIT_ROUTE_BLOCKED", ex.code());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}