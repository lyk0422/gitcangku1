package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.PermitConflictException;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.PermitCreateRequest;
import com.example.starter.api.dto.PermitResult;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
 * 覆盖主流程、禁飞审查、间隔冲突（含对向相遇合成场景）、活动许可上限、
 * 撤销释放未来占用、幂等边界与真实并发互斥。时间相关用例使用可控时钟。
 */
@SpringBootTest
@DisplayName("运动间隔许可 H2 事务测试")
class PermitServiceH2Test {

    /** 可控时钟：测试可显式推进"现在"，撤销时刻由此决定。 */
    static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant newInstant) {
            this.instant = newInstant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
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

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.ofEpochSecond(1_000_000));
        }
    }

    @Autowired
    private AirspaceReviewService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MutableClock clock;

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
        clock.setInstant(Instant.ofEpochSecond(1_000_000));
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

    private PermitCreateRequest permitReq(String routeId, long start, long end) {
        return new PermitCreateRequest(routeId, 1, 0L, start, end, "tester", "req-" + rid("permit"));
    }

    private PermitResult permitData(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), PermitResult.class);
    }

    // ============================ 主流程 ============================

    @Test
    @DisplayName("主流程：许可创建成功；航线替换不移动已许可轨迹，新申请须引用新版本")
    void createPermitAndSnapshotImmuneToRouteReplace() {
        createRoute("r1", 0, 0, 100, 0);
        MutationResponse created = service.createPermit(permitReq("r1", 0, 11));
        assertFalse(created.replayed());
        PermitResult permit = permitData(created);
        assertNotNull(permit.permitId());
        assertEquals("ACTIVE", permit.status());
        assertEquals(1, permit.routeVersion());
        assertEquals(0L, permit.airspaceVersion());
        assertNull(permit.revokedEpochSec());

        // 航线替换为 y=500 的新版本：已许可轨迹快照不移动
        service.replaceRoute(new RouteReplaceRequest("r1", 1, pts(0, 500, 100, 500), "req-" + rid("replace")));
        assertEquals(0, jdbc.queryForObject(
                "SELECT start_y FROM permit WHERE permit_id = ?", Integer.class, permit.permitId()));

        // 旧版本申请 → 409；新版本申请（y=500 远离旧轨迹）→ 成功
        ApiException stale = assertThrows(ApiException.class, () -> service.createPermit(
                new PermitCreateRequest("r1", 1, 0L, 0L, 11L, "tester", "req-" + rid("permit"))));
        assertEquals(HttpStatus.CONFLICT, stale.status());
        MutationResponse ok = service.createPermit(
                new PermitCreateRequest("r1", 2, 0L, 0L, 11L, "tester", "req-" + rid("permit")));
        assertEquals(2, permitData(ok).routeVersion());
    }

    @Test
    @DisplayName("许可仍需通过原禁飞审查：命中有效禁飞区 409，撤销后用新版本可申请")
    void permitRequiresNoFlyClearance() {
        createRoute("rz", 0, 10, 100, 10);
        service.createZone(new ZoneCreateRequest("z1", 40, 5, 60, 15, "req-" + rid("zone")));
        ApiException blocked = assertThrows(ApiException.class, () -> service.createPermit(
                new PermitCreateRequest("rz", 1, 1L, 0L, 10L, "tester", "req-" + rid("permit"))));
        assertEquals(HttpStatus.CONFLICT, blocked.status());
        assertEquals("PERMIT_ZONE_BLOCKED", blocked.code());

        service.revokeZone(new ZoneRevokeRequest("z1", "req-" + rid("revoke")));
        MutationResponse ok = service.createPermit(
                new PermitCreateRequest("rz", 1, 2L, 0L, 10L, "tester", "req-" + rid("permit")));
        assertEquals("ACTIVE", permitData(ok).status());
    }

    @Test
    @DisplayName("失败分支：非法区间、非两点航线、缺失航线、版本不匹配")
    void invalidPermitRequestsRejected() {
        createRoute("r2p", 0, 0, 100, 0);
        createRoute("r3p", 0, 0, 50, 0, 100, 0);

        ApiException badInterval = assertThrows(ApiException.class, () -> service.createPermit(
                permitReq("r2p", 10, 10)));
        assertEquals(HttpStatus.BAD_REQUEST, badInterval.status());
        ApiException reversed = assertThrows(ApiException.class, () -> service.createPermit(
                permitReq("r2p", 20, 10)));
        assertEquals(HttpStatus.BAD_REQUEST, reversed.status());

        ApiException threePoint = assertThrows(ApiException.class, () -> service.createPermit(
                permitReq("r3p", 0, 10)));
        assertEquals(HttpStatus.BAD_REQUEST, threePoint.status());
        assertEquals("ROUTE_NOT_TWO_POINT", threePoint.code());

        ApiException missing = assertThrows(ApiException.class, () -> service.createPermit(
                permitReq("ghost", 0, 10)));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        ApiException badVersion = assertThrows(ApiException.class, () -> service.createPermit(
                new PermitCreateRequest("r2p", 9, 0L, 0L, 10L, "tester", "req-" + rid("permit"))));
        assertEquals(HttpStatus.CONFLICT, badVersion.status());
    }
    // ============================ 间隔冲突 ============================

    @Test
    @DisplayName("合成场景：对向直线第 5.5 秒相遇，端点相隔 100 米仍拒绝；推迟到前一结束后成功")
    void opposingHeadOnRejectedThenDelayedSucceeds() {
        createRoute("ra", 0, 0, 100, 0);
        createRoute("rb", 100, 0, 0, 0);
        // A: [0,11) 从 (0,0) 到 (100,0)
        PermitResult a = permitData(service.createPermit(permitReq("ra", 0, 11)));

        // B: [0,11) 从 (100,0) 到 (0,0)，t=5.5 时与 A 在同一点相遇；端点相隔 100 米
        PermitConflictException conflict = assertThrows(PermitConflictException.class,
                () -> service.createPermit(permitReq("rb", 0, 11)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, conflict.status());
        assertEquals(List.of(a.permitId()), conflict.conflictingPermitIds());
        // 冲突不新增许可
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM permit", Integer.class));

        // 左闭右开：A 终点 11 不含，B 推迟到 [11,22) 与 A 无共同占用 → 成功
        MutationResponse delayed = service.createPermit(permitReq("rb", 11, 22));
        assertEquals("ACTIVE", permitData(delayed).status());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM permit", Integer.class));
    }

    @Test
    @DisplayName("恰好 10 米合法，9 米拒绝；端点相接的同轨迹许可不冲突")
    void separationBoundaryEnforced() {
        createRoute("pa", 0, 0, 100, 0);
        createRoute("pb", 0, 10, 100, 10);
        createRoute("pc", 0, 9, 100, 9);
        service.createPermit(permitReq("pa", 0, 10));
        // 平行相距恰好 10 米 → 合法
        assertEquals("ACTIVE", permitData(service.createPermit(permitReq("pb", 0, 10))).status());
        // 相距 9 米 → 422
        PermitConflictException tooClose = assertThrows(PermitConflictException.class,
                () -> service.createPermit(permitReq("pc", 0, 10)));
        assertEquals(2, tooClose.conflictingPermitIds().size());

        // 同一轨迹端点相接 [10,20)：无共同占用 → 合法
        assertEquals("ACTIVE", permitData(service.createPermit(permitReq("pa", 10, 20))).status());
    }

    @Test
    @DisplayName("活动许可最多 20 条，撤销后释放名额")
    void maxActivePermitsEnforced() {
        createRoute("rl", 0, 0, 100, 0);
        for (int i = 0; i < 20; i++) {
            // 互不重叠的区间，避免间隔冲突干扰上限判定
            service.createPermit(permitReq("rl", i * 10L, i * 10L + 5));
        }
        ApiException full = assertThrows(ApiException.class, () -> service.createPermit(
                permitReq("rl", 1000, 1005)));
        assertEquals(HttpStatus.CONFLICT, full.status());
        assertEquals("PERMIT_LIMIT_EXCEEDED", full.code());

        // 撤销一条后名额释放
        String firstPermit = jdbc.queryForObject(
                "SELECT permit_id FROM permit ORDER BY permit_id LIMIT 1", String.class);
        service.revokePermit(new PermitRevokeRequest(firstPermit, "tester", "req-" + rid("revoke")));
        assertEquals("ACTIVE", permitData(service.createPermit(permitReq("rl", 1000, 1005))).status());
    }

    // ============================ 撤销 ============================

    @Test
    @DisplayName("撤销释放未来占用、历史轨迹保留：撤销前区间仍计入占用")
    void revokeReleasesFutureOccupancyKeepsHistory() {
        createRoute("va", 0, 0, 100, 0);
        createRoute("vb", 100, 0, 0, 0);
        clock.setInstant(Instant.ofEpochSecond(1_000));
        // A 占用 [1000,1100)
        PermitResult a = permitData(service.createPermit(permitReq("va", 1000, 1100)));

        // 时钟推进到 t=1050 时撤销：占用区间收缩为 [1000,1050)
        clock.setInstant(Instant.ofEpochSecond(1_050));
        MutationResponse revoked = service.revokePermit(
                new PermitRevokeRequest(a.permitId(), "tester", "req-" + rid("revoke")));
        PermitResult revokedData = permitData(revoked);
        assertEquals("REVOKED", revokedData.status());
        assertEquals(1_050L, revokedData.revokedEpochSec());

        // 历史轨迹保留：许可记录与轨迹快照仍在
        PermitResult historical = service.getPermit(a.permitId());
        assertEquals("REVOKED", historical.status());
        assertEquals(1_000L, historical.startEpochSec());
        assertEquals(1_100L, historical.endEpochSec());
        assertEquals(0, jdbc.queryForObject(
                "SELECT start_x FROM permit WHERE permit_id = ?", Integer.class, a.permitId()));

        // 与保留的历史占用 [1000,1050) 重叠 → 仍 422（此时 B 尚无许可，冲突只可能来自 A）
        PermitConflictException past = assertThrows(PermitConflictException.class,
                () -> service.createPermit(permitReq("vb", 1000, 1100)));
        assertEquals(List.of(a.permitId()), past.conflictingPermitIds());
        // 对向航线 B：只与未来占用 [1050,1150) 冲突 → 释放后成功
        assertEquals("ACTIVE", permitData(service.createPermit(permitReq("vb", 1050, 1150))).status());

        // 重复撤销 409；撤销不存在 404
        ApiException twice = assertThrows(ApiException.class, () -> service.revokePermit(
                new PermitRevokeRequest(a.permitId(), "tester", "req-" + rid("revoke2"))));
        assertEquals(HttpStatus.CONFLICT, twice.status());
        ApiException missing = assertThrows(ApiException.class, () -> service.revokePermit(
                new PermitRevokeRequest("pm_ghost", "tester", "req-" + rid("revoke3"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }
    // ============================ 幂等 ============================

    @Test
    @DisplayName("幂等：同键同参重放首次完整响应；异参（含不同操作者）409；失败不占键")
    void permitIdempotencySemantics() {
        createRoute("ri", 0, 0, 100, 0);
        createRoute("rj", 0, 50, 100, 50);
        String key = "permit-idem-1";
        PermitCreateRequest req = new PermitCreateRequest("ri", 1, 0L, 0L, 10L, "alice", key);
        MutationResponse first = service.createPermit(req);
        assertFalse(first.replayed());

        // 同键同参重放：返回首次完整响应，不新增许可
        MutationResponse replay = service.createPermit(
                new PermitCreateRequest("ri", 1, 0L, 0L, 10L, "alice", key));
        assertTrue(replay.replayed());
        assertEquals(permitData(first).permitId(), permitData(replay).permitId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM permit", Integer.class));

        // 同键异参 409；仅操作者不同也属于异参
        ApiException diffParams = assertThrows(ApiException.class, () -> service.createPermit(
                new PermitCreateRequest("ri", 1, 0L, 0L, 20L, "alice", key)));
        assertEquals(HttpStatus.CONFLICT, diffParams.status());
        ApiException diffOperator = assertThrows(ApiException.class, () -> service.createPermit(
                new PermitCreateRequest("ri", 1, 0L, 0L, 10L, "bob", key)));
        assertEquals(HttpStatus.CONFLICT, diffOperator.status());

        // 失败（422 间隔冲突）不占键：同键可换参数成功
        createRoute("rk", 100, 0, 0, 0);
        String failKey = "permit-idem-fail";
        assertThrows(PermitConflictException.class, () -> service.createPermit(
                new PermitCreateRequest("rk", 1, 0L, 0L, 10L, "alice", failKey)));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?", Integer.class, failKey));
        MutationResponse reused = service.createPermit(
                new PermitCreateRequest("rj", 1, 0L, 0L, 10L, "alice", failKey));
        assertFalse(reused.replayed());
        assertEquals("ACTIVE", permitData(reused).status());
    }

    // ============================ 并发 ============================

    @Test
    @DisplayName("并发冲突申请：至多保存互相安全的集合，恰好一条成功其余 422")
    void concurrentConflictingPermitsExactlyOneSaved() throws Exception {
        createRoute("ca", 0, 0, 100, 0);
        createRoute("cb", 100, 0, 0, 0);
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String routeId = (i % 2 == 0) ? "ca" : "cb";
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.createPermit(new PermitCreateRequest(
                                routeId, 1, 0L, 0L, 11L, "tester", "req-" + rid("race")));
                    } catch (ApiException ex) {
                        return ex;
                    }
                }));
            }
            int success = 0;
            int conflict = 0;
            for (Future<Object> f : futures) {
                Object result = f.get(15, TimeUnit.SECONDS);
                if (result instanceof MutationResponse resp) {
                    assertFalse(resp.replayed());
                    success++;
                } else {
                    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
                            ((ApiException) result).status());
                    conflict++;
                }
            }
            // 全部申请两两冲突（对向或同轨迹同时段），至多保存一条
            assertEquals(1, success);
            assertEquals(n - 1, conflict);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM permit", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("同键并发申请：恰好一次业务生效，其余重放首次响应")
    void concurrentSameRequestIdPermitReplays() throws Exception {
        createRoute("ck", 0, 0, 100, 0);
        String key = "permit-concurrent-key";
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.createPermit(
                            new PermitCreateRequest("ck", 1, 0L, 0L, 10L, "alice", key));
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
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM permit", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?", Integer.class, key));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("许可审查与区域变更读取一致版本：成功必为变更前快照，否则 409")
    void concurrentPermitAndZoneChangeConsistentVersion() throws Exception {
        int iterations = 8;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < iterations; i++) {
                String routeId = "cr-" + i;
                String zoneId = "cz-" + i;
                int y = 10 + i * 100;
                createRoute(routeId, 0, y, 100, y);
                long versionBefore = jdbc.queryForObject(
                        "SELECT global_version FROM airspace_meta WHERE id=1", Long.class);
                CyclicBarrier barrier = new CyclicBarrier(2);
                // 许可方：明确提交建区前的空域版本
                Future<Object> permitFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.createPermit(new PermitCreateRequest(
                                routeId, 1, versionBefore, 0L, 10L, "tester",
                                "req-permit-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                // 建区方：创建恰好封锁本轮航线的区域
                Future<Object> zoneFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.createZone(new ZoneCreateRequest(
                                zoneId, 40, y - 5, 60, y + 5, "req-zone-" + zoneId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Object permitResult = permitFuture.get(15, TimeUnit.SECONDS);
                Object zoneResult = zoneFuture.get(15, TimeUnit.SECONDS);
                assertTrue(zoneResult instanceof MutationResponse, "建区必须成功");
                if (permitResult instanceof MutationResponse mr) {
                    // 许可先拿锁：读到建区前的一致快照，版本必须是提交时的旧版本
                    assertFalse(mr.replayed());
                    assertEquals(versionBefore, permitData(mr).airspaceVersion(),
                            "许可不得携带旧版本却使用新区域判定");
                } else {
                    // 建区先提交：版本已推进，许可必须 409，不能产生许可
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) permitResult).status());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}