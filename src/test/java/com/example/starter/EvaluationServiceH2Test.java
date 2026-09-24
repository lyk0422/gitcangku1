package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.EvaluationCandidateDto;
import com.example.starter.api.dto.EvaluationRequest;
import com.example.starter.api.dto.EvaluationResultDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.ZoneCreateRequest;
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
 * 改航候选集批量评估的 H2 数据库测试（MODE=MySQL）。
 * 覆盖候选集合校验、一致状态判定、选中替换与回滚、评估记录不可变、
 * 幂等重放与真实并发互斥。
 */
@SpringBootTest
class EvaluationServiceH2Test {

    @Autowired
    private com.example.starter.service.AirspaceReviewService service;
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
        jdbc.update("DELETE FROM evaluation_candidate");
        jdbc.update("DELETE FROM evaluation");
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

    private void createRoute(String routeId, List<RoutePointDto> points) {
        service.createRoute(new RouteCreateRequest(routeId, points, "req-" + rid("route")));
    }

    private void createZone(String zoneId, int xMin, int yMin, int xMax, int yMax) {
        service.createZone(new ZoneCreateRequest(zoneId, xMin, yMin, xMax, yMax,
                "req-" + rid("zone")));
    }

    private EvaluationResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), EvaluationResultDto.class);
    }

    @SafeVarargs
    private final EvaluationRequest evalReq(String key, String routeId, int expectedVersion,
                                            long airspaceVersion,
                                            List<RoutePointDto>... candidates) {
        return new EvaluationRequest(key, routeId, expectedVersion, airspaceVersion,
                List.of(candidates));
    }

    // ============================ 主流程：选中替换 ============================

    @Test
    void firstClearCandidateIsSelectedAndReplacesRoute() {
        createRoute("er1", pts(0, 10, 100, 10));
        createZone("zb", 40, 5, 60, 15);

        // 候选 0 穿过禁飞区 BLOCKED，候选 1 绕行 CLEAR，候选 2 也可行但不被选中
        MutationResponse resp = service.evaluate(evalReq("ek-main-1", "er1", 1, 1L,
                pts(0, 10, 100, 10),
                pts(0, 30, 100, 30),
                pts(0, -30, 100, -30)));
        assertFalse(resp.replayed());
        EvaluationResultDto dto = dataOf(resp);
        assertEquals(1, dto.selectedIndex());
        assertEquals(1, dto.routeVersion());
        assertEquals(2, dto.newRouteVersion());
        assertEquals(1L, dto.airspaceVersion());
        assertEquals(pts(0, 30, 100, 30), dto.selectedPoints());
        assertEquals(3, dto.candidates().size());
        assertEquals("BLOCKED", dto.candidates().get(0).conclusion());
        assertEquals(List.of("zb"), dto.candidates().get(0).hitZoneIds());
        assertEquals("CLEAR", dto.candidates().get(1).conclusion());
        assertTrue(dto.candidates().get(1).hitZoneIds().isEmpty());
        assertEquals("CLEAR", dto.candidates().get(2).conclusion());

        // 航线已被选中候选点列替换，版本加一
        assertEquals(2, jdbc.queryForObject(
                "SELECT version FROM route WHERE route_id = 'er1'", Integer.class));
        List<Integer> ys = jdbc.queryForList(
                "SELECT y FROM route_point WHERE route_id = 'er1' ORDER BY seq", Integer.class);
        assertEquals(List.of(30, 30), ys);
        // 评估记录与逐候选结论已持久化
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM evaluation", Integer.class));
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_candidate", Integer.class));
        // 航线版本推进使当前审核结论失效（无审核记录时 404，有记录时 STALE）
        ApiException noReview = assertThrows(ApiException.class,
                () -> service.getCurrentReview("er1"));
        assertEquals(HttpStatus.NOT_FOUND, noReview.status());
    }

    @Test
    void evaluationInvalidatesCurrentReviewConclusion() {
        createRoute("er2", pts(0, 10, 100, 10));
        // 先做一次审核得到当前结论
        service.review(new com.example.starter.api.dto.ReviewRequest(
                "er2", 1, 0L, "req-" + rid("review")));
        assertEquals("CLEAR", service.getCurrentReview("er2").conclusion());

        service.evaluate(evalReq("ek-stale-1", "er2", 1, 0L,
                pts(0, 10, 100, 10), pts(0, 30, 100, 30)));
        // 替换后当前审核结论必须失效
        assertEquals("STALE", service.getCurrentReview("er2").conclusion());
    }

    @Test
    void boundaryTouchCandidateIsBlockedAndInsideCandidateIsBlocked() {
        createRoute("er3", pts(0, 0, 10, 0));
        createZone("zt", 40, 5, 60, 15);
        // 候选 0 仅接触区域底边（y=5）→ BLOCKED；候选 1 完全位于区域内 → BLOCKED；
        // 候选 2 远离 → CLEAR 被选中
        MutationResponse resp = service.evaluate(evalReq("ek-geo-1", "er3", 1, 1L,
                pts(0, 5, 100, 5),
                pts(45, 8, 55, 12),
                pts(0, 50, 100, 50)));
        EvaluationResultDto dto = dataOf(resp);
        assertEquals("BLOCKED", dto.candidates().get(0).conclusion());
        assertEquals("BLOCKED", dto.candidates().get(1).conclusion());
        assertEquals(2, dto.selectedIndex());
    }

    // ============================ 候选集合校验 ============================

    @Test
    void duplicateCandidatesRejectedAs400() {
        createRoute("ec1", pts(0, 0, 10, 0));
        ApiException ex = assertThrows(ApiException.class, () -> service.evaluate(
                evalReq("ek-dup-1", "ec1", 1, 0L,
                        pts(0, 10, 100, 10), pts(5, 5, 6, 6), pts(0, 10, 100, 10))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("DUPLICATE_CANDIDATE", ex.code());
    }

    @Test
    void candidateWithAllIdenticalPointsRejectedAs400() {
        createRoute("ec2", pts(0, 0, 10, 0));
        ApiException ex = assertThrows(ApiException.class, () -> service.evaluate(
                evalReq("ek-same-1", "ec2", 1, 0L,
                        pts(7, 7, 7, 7), pts(0, 10, 100, 10))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
    }

    // ============================ 全部 BLOCKED：422 回滚 ============================

    @Test
    void allCandidatesBlockedReturns422AndWritesNothing() {
        createRoute("eb1", pts(0, 10, 100, 10));
        createZone("zb1", 40, 5, 60, 15);
        createZone("zb2", 40, 25, 60, 35);

        ApiException ex = assertThrows(ApiException.class, () -> service.evaluate(
                evalReq("ek-blocked-1", "eb1", 1, 2L,
                        pts(0, 10, 100, 10), pts(0, 30, 100, 30))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("ALL_CANDIDATES_BLOCKED", ex.code());
        // 422 响应携带逐候选命中集合
        @SuppressWarnings("unchecked")
        List<EvaluationCandidateDto> details = (List<EvaluationCandidateDto>) ex.details();
        assertNotNull(details);
        assertEquals(2, details.size());
        assertEquals(List.of("zb1"), details.get(0).hitZoneIds());
        assertEquals(List.of("zb2"), details.get(1).hitZoneIds());

        // 不写入航线：版本与点列保持原样
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM route WHERE route_id = 'eb1'", Integer.class));
        assertEquals(List.of(10, 10), jdbc.queryForList(
                "SELECT y FROM route_point WHERE route_id = 'eb1' ORDER BY seq", Integer.class));
        // 不写评估记录、不占键
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM evaluation", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id = 'ek-blocked-1'",
                Integer.class));

        // 失败不占键：撤销一个区域后同键可成功
        service.revokeZone(new com.example.starter.api.dto.ZoneRevokeRequest(
                "zb2", "req-" + rid("revoke")));
        MutationResponse ok = service.evaluate(evalReq("ek-blocked-1", "eb1", 1, 3L,
                pts(0, 10, 100, 10), pts(0, 30, 100, 30)));
        assertFalse(ok.replayed());
        assertEquals(1, dataOf(ok).selectedIndex());
    }

    // ============================ 版本不符：409 回滚 ============================

    @Test
    void versionMismatchReturns409AndWritesNothing() {
        createRoute("ev1", pts(0, 10, 100, 10));
        createZone("zv1", 40, 5, 60, 15);

        // 航线版本不符
        ApiException badRoute = assertThrows(ApiException.class, () -> service.evaluate(
                evalReq("ek-v-1", "ev1", 7, 1L, pts(0, 30, 100, 30), pts(0, -30, 100, -30))));
        assertEquals(HttpStatus.CONFLICT, badRoute.status());
        // 空域版本不符
        ApiException badAirspace = assertThrows(ApiException.class, () -> service.evaluate(
                evalReq("ek-v-2", "ev1", 1, 0L, pts(0, 30, 100, 30), pts(0, -30, 100, -30))));
        assertEquals(HttpStatus.CONFLICT, badAirspace.status());
        // 航线不存在
        ApiException missing = assertThrows(ApiException.class, () -> service.evaluate(
                evalReq("ek-v-3", "ghost", 1, 1L, pts(0, 30, 100, 30), pts(0, -30, 100, -30))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        // 航线点列与评估记录都不写入
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM route WHERE route_id = 'ev1'", Integer.class));
        assertEquals(List.of(10, 10), jdbc.queryForList(
                "SELECT y FROM route_point WHERE route_id = 'ev1' ORDER BY seq", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM evaluation", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id LIKE 'ek-v-%'",
                Integer.class));
    }

    // ============================ 评估记录不可变 ============================

    @Test
    void evaluationRecordIsImmutableAfterZoneAndRouteChanges() {
        createRoute("ei1", pts(0, 10, 100, 10));
        createZone("zi1", 40, 5, 60, 15);
        MutationResponse resp = service.evaluate(evalReq("ek-im-1", "ei1", 1, 1L,
                pts(0, 10, 100, 10), pts(0, 30, 100, 30)));
        EvaluationResultDto first = dataOf(resp);

        // 后续禁飞区变更与点列替换都不改写评估记录
        createZone("zi2", -50, -50, -40, -40);
        service.replaceRoute(new RouteReplaceRequest(
                "ei1", 2, pts(0, 0, 100, 0), "req-" + rid("replace")));

        EvaluationResultDto history = service.getEvaluation(first.evaluationId());
        assertEquals(first.evaluationId(), history.evaluationId());
        assertEquals("ek-im-1", history.evaluationKey());
        assertEquals(1L, history.airspaceVersion());
        assertEquals(1, history.routeVersion());
        assertEquals(2, history.newRouteVersion());
        assertEquals(1, history.selectedIndex());
        assertEquals(pts(0, 30, 100, 30), history.selectedPoints());
        assertEquals("BLOCKED", history.candidates().get(0).conclusion());
        assertEquals(List.of("zi1"), history.candidates().get(0).hitZoneIds());
        assertEquals("CLEAR", history.candidates().get(1).conclusion());

        ApiException missing = assertThrows(ApiException.class,
                () -> service.getEvaluation("ev_ghost"));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    // ============================ 幂等 ============================

    @Test
    void sameEvaluationKeyReplaysSnapshotAndReorderConflicts() {
        createRoute("em1", pts(0, 10, 100, 10));
        createZone("zm1", 40, 5, 60, 15);
        EvaluationRequest req = evalReq("ek-replay-1", "em1", 1, 1L,
                pts(0, 10, 100, 10), pts(0, 30, 100, 30));
        MutationResponse first = service.evaluate(req);
        assertFalse(first.replayed());

        // 同键同参重放：返回首次响应快照，不再次推进航线版本
        MutationResponse replay = service.evaluate(req);
        assertTrue(replay.replayed());
        EvaluationResultDto replayDto = dataOf(replay);
        assertEquals(dataOf(first).evaluationId(), replayDto.evaluationId());
        assertEquals(1, replayDto.selectedIndex());
        assertEquals(2, jdbc.queryForObject(
                "SELECT version FROM route WHERE route_id = 'em1'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM evaluation", Integer.class));

        // 候选顺序属于请求参数：同键换序视为异参 → 409
        ApiException reordered = assertThrows(ApiException.class, () -> service.evaluate(
                evalReq("ek-replay-1", "em1", 1, 1L,
                        pts(0, 30, 100, 30), pts(0, 10, 100, 10))));
        assertEquals(HttpStatus.CONFLICT, reordered.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", reordered.code());
    }

    // ============================ 并发 ============================

    @Test
    void concurrentEvaluationAndZoneCreateAreSerialized() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                String routeId = "ce-" + i;
                String zoneId = "cez-" + i;
                int y = 10 + i * 100;
                createRoute(routeId, pts(0, y, 100, y));
                long versionBefore = jdbc.queryForObject(
                        "SELECT global_version FROM airspace_meta WHERE id=1", Long.class);
                CyclicBarrier barrier = new CyclicBarrier(2);

                // 评估方：候选 0 沿航线（建区后会被封锁），候选 1 永远可行
                Future<Object> evalFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.evaluate(evalReq("ek-race-" + routeId, routeId, 1,
                                versionBefore, pts(0, y, 100, y), pts(0, y + 40, 100, y + 40)));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                // 建区方：恰好封锁候选 0
                Future<Object> zoneFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.createZone(new ZoneCreateRequest(
                                zoneId, 40, y - 5, 60, y + 5, "req-zone-" + zoneId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object evalResult = evalFuture.get(15, TimeUnit.SECONDS);
                Object zoneResult = zoneFuture.get(15, TimeUnit.SECONDS);
                assertTrue(zoneResult instanceof MutationResponse, "建区必须成功");

                if (evalResult instanceof MutationResponse mr) {
                    // 评估先拿锁：使用建区前的一致快照，候选 0 必然 CLEAR 被选中
                    EvaluationResultDto dto = dataOf(mr);
                    assertEquals(versionBefore, dto.airspaceVersion());
                    assertEquals(0, dto.selectedIndex());
                    assertEquals("CLEAR", dto.candidates().get(0).conclusion());
                    // 原子性：航线已替换且评估记录必然存在
                    assertEquals(2, jdbc.queryForObject(
                            "SELECT version FROM route WHERE route_id = ?",
                            Integer.class, routeId));
                    assertEquals(1, jdbc.queryForObject(
                            "SELECT COUNT(*) FROM evaluation WHERE route_id = ?",
                            Integer.class, routeId));
                } else {
                    // 建区先提交：空域版本已推进，评估必须 409 且什么都不写
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) evalResult).status());
                    assertEquals(1, jdbc.queryForObject(
                            "SELECT version FROM route WHERE route_id = ?",
                            Integer.class, routeId));
                    assertEquals(0, jdbc.queryForObject(
                            "SELECT COUNT(*) FROM evaluation WHERE route_id = ?",
                            Integer.class, routeId));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentEvaluationAndRouteReplaceIsSerialized() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                String routeId = "cer-" + i;
                createRoute(routeId, pts(0, 10, 100, 10));
                CyclicBarrier barrier = new CyclicBarrier(2);

                Future<Object> evalFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.evaluate(evalReq("ek-rrace-" + routeId, routeId, 1, 0L,
                                pts(0, 30, 100, 30), pts(0, -30, 100, -30)));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> replaceFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.replaceRoute(new RouteReplaceRequest(
                                routeId, 1, pts(0, 0, 100, 0), "req-replace-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object evalResult = evalFuture.get(15, TimeUnit.SECONDS);
                Object replaceResult = replaceFuture.get(15, TimeUnit.SECONDS);
                boolean evalOk = evalResult instanceof MutationResponse;
                boolean replaceOk = replaceResult instanceof MutationResponse;
                // 同一航线版本 1 上两者互斥：恰好一个成功，另一个 409
                assertTrue(evalOk ^ replaceOk,
                        "评估与替换必须恰好一个成功: eval=" + evalOk + ", replace=" + replaceOk);
                if (!evalOk) {
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) evalResult).status());
                }
                if (!replaceOk) {
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) replaceResult).status());
                }
                // 最终状态一致：版本都是 2；评估成功才存在评估记录且点列为选中候选
                assertEquals(2, jdbc.queryForObject(
                        "SELECT version FROM route WHERE route_id = ?", Integer.class, routeId));
                int evalRows = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM evaluation WHERE route_id = ?",
                        Integer.class, routeId);
                List<Integer> ys = jdbc.queryForList(
                        "SELECT y FROM route_point WHERE route_id = ? ORDER BY seq",
                        Integer.class, routeId);
                if (evalOk) {
                    assertEquals(1, evalRows, "航线已替换则评估记录不得缺失");
                    assertEquals(List.of(30, 30), ys);
                } else {
                    assertEquals(0, evalRows);
                    assertEquals(List.of(0, 0), ys);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameEvaluationKeyPlaysBackOneOutcome() throws Exception {
        createRoute("emc", pts(0, 10, 100, 10));
        String key = "ek-concurrent-1";
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.evaluate(evalReq(key, "emc", 1, 0L,
                            pts(0, 30, 100, 30), pts(0, -30, 100, -30)));
                }));
            }
            int firstCount = 0;
            int replayCount = 0;
            String evaluationId = null;
            for (Future<MutationResponse> f : futures) {
                MutationResponse resp = f.get(15, TimeUnit.SECONDS);
                if (resp.replayed()) {
                    replayCount++;
                } else {
                    firstCount++;
                }
                String id = dataOf(resp).evaluationId();
                if (evaluationId == null) {
                    evaluationId = id;
                } else {
                    assertEquals(evaluationId, id, "同键重放必须返回同一评估记录");
                }
            }
            assertEquals(1, firstCount, "仅一次请求真正执行评估");
            assertEquals(n - 1, replayCount);
            // 航线只被替换一次，评估记录与去重记录各一行
            assertEquals(2, jdbc.queryForObject(
                    "SELECT version FROM route WHERE route_id = 'emc'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM evaluation", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                    Integer.class, key));
        } finally {
            pool.shutdownNow();
        }
    }
}
