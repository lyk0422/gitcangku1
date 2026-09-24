package com.example.starter;

import com.example.starter.api.AllCandidatesBlockedException;
import com.example.starter.api.ApiException;
import com.example.starter.api.dto.CandidateResultDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.RerouteEvaluationRequest;
import com.example.starter.api.dto.RerouteEvaluationResultDto;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.api.dto.ZoneRevokeRequest;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 改航候选集批量评估的 H2 数据库测试（MODE=MySQL）。
 * 覆盖候选集合校验、一致状态判定、首个 CLEAR 选择、全部 BLOCKED 的 422 回滚、
 * 版本冲突、选中替换与审核失效、评估报告不可变、幂等重放/换序异参/失败不占键。
 */
@SpringBootTest
class RerouteEvaluationH2Test {

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
        jdbc.update("DELETE FROM reroute_evaluation_candidate");
        jdbc.update("DELETE FROM reroute_evaluation");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    private String uniq(String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    private static List<RoutePointDto> pts(int... xy) {
        return java.util.stream.IntStream.range(0, xy.length / 2)
                .mapToObj(i -> new RoutePointDto(xy[2 * i], xy[2 * i + 1]))
                .toList();
    }

    private RerouteEvaluationResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), RerouteEvaluationResultDto.class);
    }

    private void createRoute(String routeId, int... xy) {
        service.createRoute(new RouteCreateRequest(routeId, pts(xy), uniq("req-route")));
    }

    private void createZone(String zoneId, int xMin, int yMin, int xMax, int yMax) {
        service.createZone(new ZoneCreateRequest(
                zoneId, xMin, yMin, xMax, yMax, uniq("req-zone")));
    }

    private RerouteEvaluationRequest evalReq(String routeId, int expectedVersion,
                                             long airspaceVersion,
                                             List<List<RoutePointDto>> candidates) {
        return new RerouteEvaluationRequest(uniq("eval"), routeId, expectedVersion,
                airspaceVersion, candidates);
    }

    // ============================ 候选集合校验（400） ============================

    @Test
    void candidateWithAllIdenticalPointsIs400() {
        createRoute("r1", 0, 0, 10, 10);
        List<List<RoutePointDto>> candidates = List.of(
                pts(5, 5, 5, 5), pts(0, 0, 100, 100));
        ApiException ex = assertThrows(ApiException.class, () -> service.evaluateReroute(
                evalReq("r1", 1, 0L, candidates)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("CANDIDATE_POINTS_IDENTICAL", ex.code());
        assertNoSideEffectsOnFailure("r1");
    }

    @Test
    void duplicateCandidatesIs400() {
        createRoute("r1", 0, 0, 10, 10);
        // 两个候选点列完全相同（即便中间有重复点也按序列逐点比较）
        List<List<RoutePointDto>> candidates = List.of(
                pts(0, 0, 1, 1, 2, 2), pts(0, 0, 1, 1, 2, 2));
        ApiException ex = assertThrows(ApiException.class, () -> service.evaluateReroute(
                evalReq("r1", 1, 0L, candidates)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("DUPLICATE_CANDIDATE", ex.code());
        assertNoSideEffectsOnFailure("r1");
    }

    @Test
    void validationFailureDoesNotConsumeEvaluationKey() {
        createRoute("r1", 0, 0, 10, 10);
        String key = "eval-validation-key";
        RerouteEvaluationRequest bad = new RerouteEvaluationRequest(
                key, "r1", 1, 0L, List.of(pts(1, 1, 1, 1), pts(0, 0, 9, 9)));
        ApiException ex = assertThrows(ApiException.class,
                () -> service.evaluateReroute(bad));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals(0, dedupCount(key));
        // 同一个键可用于合法参数并成功
        MutationResponse ok = service.evaluateReroute(new RerouteEvaluationRequest(
                key, "r1", 1, 0L, List.of(pts(0, 0, 1, 1), pts(2, 2, 3, 3))));
        assertFalse(ok.replayed());
        assertEquals(2, jdbc.queryForObject(
                "SELECT version FROM route WHERE route_id = 'r1'", Integer.class));
    }

    private void assertNoSideEffectsOnFailure(String routeId) {
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM route WHERE route_id = ?", Integer.class, routeId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM reroute_evaluation", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM reroute_evaluation_candidate", Integer.class));
    }

    private int dedupCount(String key) {
        return jdbc.queryForList(
                "SELECT request_id FROM request_dedup WHERE request_id = ?", key).size();
    }

    // ============================ 一致状态判定与首个 CLEAR 选择 ============================

    @Test
    void selectsFirstClearCandidateInDeclaredOrderAndReplacesRoute() {
        // 航线初始水平 y=10
        createRoute("r1", 0, 10, 100, 10);
        createZone("zbox", 40, 5, 60, 15); // 空域版本 1，封住 y=10 的水平候选

        List<List<RoutePointDto>> candidates = List.of(
                pts(0, 10, 100, 10),    // 1: 穿过 zbox → BLOCKED，命中 zbox
                pts(0, 80, 100, 80),    // 2: 远离区域 → CLEAR（选中）
                pts(0, 90, 100, 90));   // 3: 同样 CLEAR，但不得被选
        MutationResponse resp = service.evaluateReroute(
                evalReq("r1", 1, 1L, candidates));
        assertFalse(resp.replayed());
        RerouteEvaluationResultDto dto = dataOf(resp);

        assertEquals(1L, dto.airspaceVersion());
        assertEquals(1, dto.routeVersion());
        assertEquals(2, dto.newRouteVersion());
        assertEquals(2, dto.selectedIndex());
        assertEquals(3, dto.candidates().size());
        CandidateResultDto c1 = dto.candidates().get(0);
        CandidateResultDto c2 = dto.candidates().get(1);
        CandidateResultDto c3 = dto.candidates().get(2);
        assertEquals(1, c1.index());
        assertEquals("BLOCKED", c1.conclusion());
        assertEquals(List.of("zbox"), c1.hitZoneIds());
        assertEquals(2, c2.index());
        assertEquals("CLEAR", c2.conclusion());
        assertTrue(c2.hitZoneIds().isEmpty());
        // 点列原样返回（顺序不变）
        assertEquals(80, c2.points().get(0).y());
        assertEquals("CLEAR", c3.conclusion());

        // 航线已被选中候选替换，版本 2
        assertEquals(2, jdbc.queryForObject(
                "SELECT version FROM route WHERE route_id = 'r1'", Integer.class));
        List<Map<String, Object>> newPoints = jdbc.queryForList(
                "SELECT x, y FROM route_point WHERE route_id = 'r1' ORDER BY seq");
        assertEquals(2, newPoints.size());
        assertEquals(80, ((Number) newPoints.get(0).get("y")).intValue());
        assertEquals(80, ((Number) newPoints.get(1).get("y")).intValue());
    }

    @Test
    void blockedCandidateHitZoneIdsAreSortedAndDeduplicated() {
        createRoute("r2", 0, 0, 100, 100);
        createZone("zeta", 40, 40, 60, 60);
        createZone("alpha", 45, 45, 55, 55);
        createZone("mid", 48, 48, 52, 52);
        List<List<RoutePointDto>> candidates = List.of(
                pts(0, 50, 100, 50),    // 1: 同时穿过三个区域
                pts(-90, -90, -80, -80)); // 2: CLEAR
        RerouteEvaluationResultDto dto = dataOf(service.evaluateReroute(
                evalReq("r2", 1, 3L, candidates)));
        assertEquals(2, dto.selectedIndex());
        assertEquals(List.of("alpha", "mid", "zeta"),
                dto.candidates().get(0).hitZoneIds());
    }

    @Test
    void boundaryTouchAndContainedCandidateAreBlocked() {
        createRoute("r3", 0, 0, 100, 100);
        createZone("edge", 40, 20, 60, 30);
        List<List<RoutePointDto>> candidates = List.of(
                // 1: 航段恰与区域底边 y=20 接触 → BLOCKED
                pts(0, 20, 100, 20),
                // 2: 两个端点都在区域内（完全位于区域内）→ BLOCKED
                pts(45, 25, 55, 25),
                // 3: CLEAR
                pts(0, 80, 100, 80));
        RerouteEvaluationResultDto dto = dataOf(service.evaluateReroute(
                evalReq("r3", 1, 1L, candidates)));
        assertEquals(3, dto.selectedIndex());
        assertEquals("BLOCKED", dto.candidates().get(0).conclusion());
        assertEquals(List.of("edge"), dto.candidates().get(0).hitZoneIds());
        assertEquals("BLOCKED", dto.candidates().get(1).conclusion());
        assertEquals("CLEAR", dto.candidates().get(2).conclusion());
    }

    // ============================ 全部 BLOCKED → 422 且回滚 ============================

    @Test
    void allCandidatesBlockedReturns422AndWritesNothing() {
        createRoute("r4", 0, 0, 100, 100);
        createZone("za", 10, 10, 80, 80);
        List<List<RoutePointDto>> candidates = List.of(
                pts(0, 50, 100, 50),    // 穿过区域
                pts(40, 40, 60, 60));   // 完全位于区域内
        AllCandidatesBlockedException ex = assertThrows(AllCandidatesBlockedException.class,
                () -> service.evaluateReroute(evalReq("r4", 1, 1L, candidates)));

        // 逐候选命中集合
        List<CandidateResultDto> perCandidate = ex.candidates();
        assertEquals(2, perCandidate.size());
        assertEquals("BLOCKED", perCandidate.get(0).conclusion());
        assertEquals(List.of("za"), perCandidate.get(0).hitZoneIds());
        assertEquals("BLOCKED", perCandidate.get(1).conclusion());
        assertEquals(List.of("za"), perCandidate.get(1).hitZoneIds());

        // 航线点列未替换、版本仍为 1，评估记录与去重记录均未写入
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM route WHERE route_id = 'r4'", Integer.class));
        List<Map<String, Object>> points = jdbc.queryForList(
                "SELECT x, y FROM route_point WHERE route_id = 'r4' ORDER BY seq");
        assertEquals(0, ((Number) points.get(0).get("x")).intValue());
        assertEquals(0, ((Number) points.get(0).get("y")).intValue());
        assertEquals(100, ((Number) points.get(1).get("x")).intValue());
        assertEquals(100, ((Number) points.get(1).get("y")).intValue());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM reroute_evaluation", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM reroute_evaluation_candidate", Integer.class));
        // 失败不占评估键（建航线/建区自身的去重行不受影响）
        Integer evalDedup = jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_kind = 'REROUTE_EVALUATION'",
                Integer.class);
        assertEquals(0, evalDedup);
    }

    @Test
    void blocked422DoesNotConsumeKeyAndSameKeyCanSucceedLater() {
        createRoute("r5", 0, 0, 10, 10);
        createZone("z", 0, 40, 100, 60);
        String key = "eval-blocked-key";
        // 两个候选都穿过区域 → 422
        AllCandidatesBlockedException blocked = assertThrows(
                AllCandidatesBlockedException.class,
                () -> service.evaluateReroute(new RerouteEvaluationRequest(
                        key, "r5", 1, 1L,
                        List.of(pts(0, 50, 10, 50), pts(0, 55, 10, 55)))));
        assertNotNull(blocked);
        // 失败不占键：同键用于“同参”仍然重新判定（区域撤销后可成功）
        service.revokeZone(new ZoneRevokeRequest("z", uniq("req-revoke")));
        MutationResponse ok = service.evaluateReroute(new RerouteEvaluationRequest(
                key, "r5", 1, 2L,
                List.of(pts(0, 50, 10, 50), pts(0, 55, 10, 55))));
        assertFalse(ok.replayed());
        assertEquals(1, dataOf(ok).selectedIndex());
    }

    // ============================ 版本与资源冲突 ============================

    @Test
    void missingRouteIs404AndVersionMismatchesAre409() {
        List<List<RoutePointDto>> candidates = List.of(
                pts(0, 0, 10, 10), pts(20, 20, 30, 30));

        ApiException missing = assertThrows(ApiException.class,
                () -> service.evaluateReroute(evalReq("ghost", 1, 0L, candidates)));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        createRoute("r6", 0, 0, 10, 10);
        ApiException wrongRouteVersion = assertThrows(ApiException.class,
                () -> service.evaluateReroute(evalReq("r6", 9, 0L, candidates)));
        assertEquals(HttpStatus.CONFLICT, wrongRouteVersion.status());
        assertEquals("VERSION_CONFLICT", wrongRouteVersion.code());

        createZone("zv", -90, -90, -80, -80);
        ApiException wrongAirspaceVersion = assertThrows(ApiException.class,
                () -> service.evaluateReroute(evalReq("r6", 1, 0L, candidates)));
        assertEquals(HttpStatus.CONFLICT, wrongAirspaceVersion.status());
        assertEquals("VERSION_CONFLICT", wrongAirspaceVersion.code());

        // 任一版本不符：航线与评估记录都不写入
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM route WHERE route_id = 'r6'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM reroute_evaluation", Integer.class));
    }

    // ============================ 选中替换使当前审核失效 ============================

    @Test
    void selectingCandidateInvalidatesCurrentReviewAndRecordIsQueryable() {
        createRoute("r7", 0, 10, 100, 10);
        // 空域版本 0 下审核当前航线 → CLEAR
        MutationResponse reviewResp = service.review(
                new ReviewRequest("r7", 1, 0L, uniq("req-review")));
        ReviewResultDto review = objectMapper.convertValue(
                reviewResp.data(), ReviewResultDto.class);
        assertEquals("CLEAR", review.conclusion());

        List<List<RoutePointDto>> candidates = List.of(
                pts(0, 80, 100, 80), pts(0, 90, 100, 90));
        MutationResponse evalResp = service.evaluateReroute(
                evalReq("r7", 1, 0L, candidates));
        RerouteEvaluationResultDto eval = dataOf(evalResp);
        assertEquals(1, eval.selectedIndex());

        // 航线版本推进，旧审核对“当前结论”失效为 STALE（旧记录本身不变）
        assertEquals("STALE", service.getCurrentReview("r7").conclusion());
        assertEquals("CLEAR", service.getReview(review.reviewId()).conclusion());

        // 评估记录可查询且内容一致
        RerouteEvaluationResultDto loaded = service.getRerouteEvaluation(eval.evaluationKey());
        assertEquals(eval.selectedIndex(), loaded.selectedIndex());
        assertEquals(eval.routeVersion(), loaded.routeVersion());
        assertEquals(eval.newRouteVersion(), loaded.newRouteVersion());
        assertEquals(eval.airspaceVersion(), loaded.airspaceVersion());
        assertEquals(2, loaded.candidates().size());
        assertEquals(80, loaded.candidates().get(0).points().get(0).y());
    }

    @Test
    void getMissingEvaluationIs404() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.getRerouteEvaluation("no-such-eval"));
        assertEquals(HttpStatus.NOT_FOUND, ex.status());
        assertEquals("EVALUATION_NOT_FOUND", ex.code());
    }

    // ============================ 报告不可变 ============================

    @Test
    void evaluationReportIsImmutableAfterZoneChangeAndRouteReplace() {
        createRoute("r8", 0, 10, 100, 10);
        createZone("zb", 40, 5, 60, 15); // 空域版本 1
        List<List<RoutePointDto>> candidates = List.of(
                pts(0, 10, 100, 10), pts(0, 80, 100, 80));
        MutationResponse resp = service.evaluateReroute(
                evalReq("r8", 1, 1L, candidates));
        RerouteEvaluationResultDto first = dataOf(resp);
        assertEquals(2, first.selectedIndex());
        assertEquals(1L, first.airspaceVersion());
        assertEquals(List.of("zb"), first.candidates().get(0).hitZoneIds());

        // 后续：撤销区域（空域版本变化）、再替换航线，均不得改写评估报告
        service.revokeZone(new ZoneRevokeRequest("zb", uniq("req-revoke")));
        service.evaluateReroute(evalReq("r8", 2, 2L,
                List.of(pts(0, 30, 100, 30), pts(0, 40, 100, 40))));

        RerouteEvaluationResultDto loaded = service.getRerouteEvaluation(first.evaluationKey());
        assertEquals(first.selectedIndex(), loaded.selectedIndex());
        assertEquals(1L, loaded.airspaceVersion());
        assertEquals("BLOCKED", loaded.candidates().get(0).conclusion());
        assertEquals(List.of("zb"), loaded.candidates().get(0).hitZoneIds());
        assertEquals("CLEAR", loaded.candidates().get(1).conclusion());
        assertEquals(80, loaded.candidates().get(1).points().get(0).y());
        // 固化的是替换前/后版本，不随后续替换改写
        assertEquals(1, loaded.routeVersion());
        assertEquals(2, loaded.newRouteVersion());
    }

    // ============================ 幂等 ============================

    @Test
    void sameKeySameParamsReplaysFirstSnapshot() {
        createRoute("r9", 0, 10, 100, 10);
        String key = "eval-replay-key";
        List<List<RoutePointDto>> candidates = List.of(
                pts(0, 80, 100, 80), pts(0, 90, 100, 90));
        MutationResponse first = service.evaluateReroute(new RerouteEvaluationRequest(
                key, "r9", 1, 0L, candidates));
        assertFalse(first.replayed());
        RerouteEvaluationResultDto firstDto = dataOf(first);

        // 同键同参重放：即使航线版本/空域版本已经变化，仍返回首次快照
        createZone("zl", -90, -90, -80, -80);
        MutationResponse replay = service.evaluateReroute(new RerouteEvaluationRequest(
                key, "r9", 1, 0L, candidates));
        assertTrue(replay.replayed());
        RerouteEvaluationResultDto replayDto = dataOf(replay);
        assertEquals(firstDto.evaluationKey(), replayDto.evaluationKey());
        assertEquals(firstDto.selectedIndex(), replayDto.selectedIndex());
        assertEquals(0L, replayDto.airspaceVersion());
        assertEquals(2, replayDto.newRouteVersion());
        // 仅一次业务生效：评估记录一条、航线只被替换一次（版本 2）
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM reroute_evaluation", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT version FROM route WHERE route_id = 'r9'", Integer.class));
    }

    @Test
    void reorderedCandidatesAreDifferentParamsAndConflict409() {
        createRoute("r10", 0, 0, 10, 10);
        String key = "eval-reorder-key";
        List<RoutePointDto> a = pts(0, 80, 100, 80);
        List<RoutePointDto> b = pts(0, 90, 100, 90);
        MutationResponse first = service.evaluateReroute(new RerouteEvaluationRequest(
                key, "r10", 1, 0L, List.of(a, b)));
        assertFalse(first.replayed());

        // 候选换序属于异参 → 409，且不会再次替换航线
        ApiException ex = assertThrows(ApiException.class,
                () -> service.evaluateReroute(new RerouteEvaluationRequest(
                        key, "r10", 2, 0L, List.of(b, a))));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", ex.code());
        assertEquals(2, jdbc.queryForObject(
                "SELECT version FROM route WHERE route_id = 'r10'", Integer.class));
    }

    @Test
    void sameKeyDifferentOperationKindConflicts() {
        createRoute("r11", 0, 0, 10, 10);
        String key = "eval-cross-kind";
        service.evaluateReroute(new RerouteEvaluationRequest(
                key, "r11", 1, 0L, List.of(pts(0, 1, 10, 1), pts(0, 2, 10, 2))));
        // 同键用于普通审核 → 409
        ApiException ex = assertThrows(ApiException.class,
                () -> service.review(new ReviewRequest("r11", 2, 0L, key)));
        assertEquals(HttpStatus.CONFLICT, ex.status());
    }

    // ============================ 并发 ============================

    @Test
    void concurrentZoneCreateAndEvaluationNeverMixesVersionsAndZones() throws Exception {
        int iterations = 10;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < iterations; i++) {
                String routeId = "er-" + i;
                String zoneId = "ez-" + i;
                int y = 10 + i * 100;
                createRoute(routeId, 0, y, 100, y);
                long versionBefore = jdbc.queryForObject(
                        "SELECT global_version FROM airspace_meta WHERE id=1", Long.class);

                List<List<RoutePointDto>> candidates = List.of(
                        pts(0, y, 100, y),          // 1: 会被新区域封住
                        pts(0, y + 200, 100, y + 200)); // 2: 永远 CLEAR
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Object> evalFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.evaluateReroute(new RerouteEvaluationRequest(
                                "ek-" + routeId, routeId, 1, versionBefore, candidates));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> zoneFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.createZone(new ZoneCreateRequest(
                                zoneId, 40, y - 5, 60, y + 5, "zk-" + zoneId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object evalResult = evalFuture.get(15, TimeUnit.SECONDS);
                Object zoneResult = zoneFuture.get(15, TimeUnit.SECONDS);
                assertTrue(zoneResult instanceof MutationResponse, "建区必须成功");

                if (evalResult instanceof MutationResponse mr) {
                    // 评估先拿锁：空域版本为旧值、区域集为空，候选 1 必 CLEAR 被选中，
                    // 绝不能出现“携带旧空域版本却使用新区域”而改选候选 2
                    RerouteEvaluationResultDto dto = dataOf(mr);
                    assertEquals(versionBefore, dto.airspaceVersion());
                    assertEquals(1, dto.selectedIndex(),
                            "并发评估携带旧空域版本时不得使用新建区域判定");
                    assertEquals("CLEAR", dto.candidates().get(0).conclusion());
                } else {
                    // 建区先提交：版本已推进，评估必须 409，航线与评估记录都不写入
                    ApiException ex = (ApiException) evalResult;
                    assertEquals(HttpStatus.CONFLICT, ex.status());
                    assertEquals(1, jdbc.queryForObject(
                            "SELECT version FROM route WHERE route_id = ?",
                            Integer.class, routeId));
                    assertEquals(0, jdbc.queryForObject(
                            "SELECT COUNT(*) FROM reroute_evaluation WHERE evaluation_key = ?",
                            Integer.class, "ek-" + routeId));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentRouteReplaceAndEvaluationNeverLeavesReplaceWithoutRecord() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                String routeId = "rr-" + i;
                createRoute(routeId, 0, 10, 100, 10);
                CyclicBarrier barrier = new CyclicBarrier(2);
                List<List<RoutePointDto>> candidates = List.of(
                        pts(0, 80, 100, 80), pts(0, 90, 100, 90));

                Future<Object> evalFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.evaluateReroute(new RerouteEvaluationRequest(
                                "erk-" + routeId, routeId, 1, 0L, candidates));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> replaceFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.replaceRoute(
                                new com.example.starter.api.dto.RouteReplaceRequest(
                                        routeId, 1, pts(0, 0, 100, 0), "rpk-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object evalResult = evalFuture.get(15, TimeUnit.SECONDS);
                Object replaceResult = replaceFuture.get(15, TimeUnit.SECONDS);

                int finalVersion = jdbc.queryForObject(
                        "SELECT version FROM route WHERE route_id = ?", Integer.class, routeId);
                Integer evalCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM reroute_evaluation WHERE evaluation_key = ?",
                        Integer.class, "erk-" + routeId);
                List<Map<String, Object>> finalPoints = jdbc.queryForList(
                        "SELECT y FROM route_point WHERE route_id = ? ORDER BY seq", routeId);

                if (evalResult instanceof MutationResponse) {
                    // 评估先提交：版本 2，点列为选中候选 y=80，评估记录必须存在
                    assertTrue(replaceResult instanceof ApiException,
                            "评估先成功后，同版本替换必须 409");
                    assertEquals(2, finalVersion);
                    assertEquals(1, evalCount);
                    assertEquals(80, ((Number) finalPoints.get(0).get("y")).intValue());
                } else {
                    // 替换先提交：评估必须 409，航线版本为 2 但评估记录不得存在
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) evalResult).status());
                    assertTrue(replaceResult instanceof MutationResponse, "替换必须成功一次");
                    assertEquals(2, finalVersion);
                    assertEquals(0, evalCount, "评估失败时不得残留评估记录");
                    assertEquals(0, ((Number) finalPoints.get(0).get("y")).intValue());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameEvaluationKeyPlaysBackOneOutcome() throws Exception {
        createRoute("r12", 0, 10, 100, 10);
        String key = "eval-concurrent-key";
        int n = 6;
        List<List<RoutePointDto>> candidates = List.of(
                pts(0, 80, 100, 80), pts(0, 90, 100, 90));
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.evaluateReroute(new RerouteEvaluationRequest(
                            key, "r12", 1, 0L, candidates));
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
            assertEquals(1, firstCount, "仅一次评估真正执行业务");
            assertEquals(n - 1, replayCount);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM reroute_evaluation", Integer.class));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM reroute_evaluation_candidate", Integer.class));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT version FROM route WHERE route_id = 'r12'", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                    Integer.class, key));
        } finally {
            pool.shutdownNow();
        }
    }
}
