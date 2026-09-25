package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.BatchReviewRequest;
import com.example.starter.api.dto.ClosureCreateRequest;
import com.example.starter.api.dto.ClosureResult;
import com.example.starter.api.dto.EmergencyExceptionRequest;
import com.example.starter.api.dto.FlightPlanDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.RouteRiskResult;
import com.example.starter.api.dto.RouteStateRequest;
import com.example.starter.api.dto.RunwayClosuresResult;
import com.example.starter.api.dto.RunwayCreateRequest;
import com.example.starter.service.AirspaceReviewService;
import com.example.starter.service.RunwayClosureService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * 跑道关闭与航线审查容量联动的 H2 数据库测试（MODE=MySQL）。
 * 覆盖跑道时间窗（重叠/端点相接/左闭右开）、紧急例外优先级、航线风险固化、
 * 批量事务全有或全无、容量约束、幂等重放与真实并发互斥。
 * 时钟使用可控的 MutableClock，保证“未来已批准”判定确定。
 */
@SpringBootTest
class RunwayClosureServiceH2Test {

    /** 固定“当前”时刻（2027-01-15T06:40:00Z），测试中的起飞时刻均在其之后。 */
    private static final long NOW = 1_800_000_000_000L;
    private static final long HOUR = 3_600_000L;
    /** 计划起飞基准时刻（未来）。 */
    private static final long DEP = NOW + 10 * HOUR;

    private static final MutableClock CLOCK = new MutableClock(Instant.ofEpochMilli(NOW));

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock mutableClock() {
            return CLOCK;
        }
    }

    /** 可推进的 UTC 时钟。 */
    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant value) {
            this.instant = value;
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

    @Autowired
    private AirspaceReviewService reviewService;
    @Autowired
    private RunwayClosureService closureService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void setUp() {
        CLOCK.setInstant(Instant.ofEpochMilli(NOW));
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM route_risk");
        jdbc.update("DELETE FROM runway_closure");
        jdbc.update("DELETE FROM runway");
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

    private static List<RoutePointDto> pts() {
        return List.of(new RoutePointDto(0, 10), new RoutePointDto(100, 10));
    }

    private void createRunway(String runwayId, int capacityPerHour) {
        MutationResponse resp = closureService.createRunway(
                new RunwayCreateRequest(runwayId, capacityPerHour, "req-" + rid("runway")));
        assertFalse(resp.replayed());
    }

    private MutationResponse addClosure(String key, String runwayId, int expectedVersion,
                                        long start, long end, boolean allowEmergency) {
        return closureService.createClosure(new ClosureCreateRequest(
                key, runwayId, expectedVersion, start, end, allowEmergency, "op-test"));
    }

    private void createRoute(String routeId, FlightPlanDto plan) {
        reviewService.createRoute(new RouteCreateRequest(routeId, pts(), plan, "req-" + rid("route")));
    }

    private MutationResponse review(String routeId) {
        return reviewService.review(new ReviewRequest(routeId, 1, 0L, "req-" + rid("review")));
    }

    private String routeStatus(String routeId) {
        return jdbc.queryForObject("SELECT status FROM route WHERE route_id = ?",
                String.class, routeId);
    }

    private int reviewCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class);
    }

    // ============================ 跑道时间窗 ============================

    @Test
    void closureWindowRulesOverlapTouchingAndVersion() {
        createRunway("rw1", 5);
        // 主窗口 [DEP, DEP+2h)，跑道版本 1 → 2
        MutationResponse c1 = addClosure("ck-1", "rw1", 1, DEP, DEP + 2 * HOUR, false);
        ClosureResult r1 = objectMapper.convertValue(c1.data(), ClosureResult.class);
        assertEquals(2, r1.runwayVersion());
        assertTrue(r1.riskRouteIds().isEmpty());

        // 端点相接合法：[DEP+2h, DEP+3h)
        MutationResponse c2 = addClosure("ck-2", "rw1", 2, DEP + 2 * HOUR, DEP + 3 * HOUR, true);
        assertEquals(3, objectMapper.convertValue(c2.data(), ClosureResult.class).runwayVersion());

        // 重叠（含部分重叠与包含）→ 409 CLOSURE_OVERLAP
        ApiException overlap = assertThrows(ApiException.class, () ->
                addClosure("ck-3", "rw1", 3, DEP + HOUR, DEP + 4 * HOUR, false));
        assertEquals(HttpStatus.CONFLICT, overlap.status());
        assertEquals("CLOSURE_OVERLAP", overlap.code());
        // 端点“半相接”不算相接：end == 既有 start 合法，但 start < 既有 end 且 end > 既有 start 冲突
        ApiException touchInside = assertThrows(ApiException.class, () ->
                addClosure("ck-4", "rw1", 3, DEP - HOUR, DEP + 1, false));
        assertEquals("CLOSURE_OVERLAP", touchInside.code());
        // 恰好首尾相接于前一个端点合法：[DEP-1h, DEP)
        MutationResponse c5 = addClosure("ck-5", "rw1", 3, DEP - HOUR, DEP, false);
        assertEquals(4, objectMapper.convertValue(c5.data(), ClosureResult.class).runwayVersion());

        // 跑道版本不匹配 → 409
        ApiException wrongVersion = assertThrows(ApiException.class, () ->
                addClosure("ck-6", "rw1", 1, DEP + 5 * HOUR, DEP + 6 * HOUR, false));
        assertEquals(HttpStatus.CONFLICT, wrongVersion.status());
        assertEquals("RUNWAY_VERSION_CONFLICT", wrongVersion.code());

        // 非法窗口（start >= end）→ 400
        ApiException invalid = assertThrows(ApiException.class, () ->
                addClosure("ck-7", "rw1", 4, DEP + 5 * HOUR, DEP + 5 * HOUR, false));
        assertEquals(HttpStatus.BAD_REQUEST, invalid.status());
        assertEquals("INVALID_CLOSURE_WINDOW", invalid.code());

        // 跑道不存在 → 404
        ApiException noRunway = assertThrows(ApiException.class, () ->
                addClosure("ck-8", "ghost", 1, DEP, DEP + HOUR, false));
        assertEquals(HttpStatus.NOT_FOUND, noRunway.status());

        // 查询跑道窗口：3 个窗口按开始时刻升序，当前版本 4
        RunwayClosuresResult view = closureService.getRunwayClosures("rw1");
        assertEquals(4, view.version());
        assertEquals(5, view.capacityPerHour());
        assertEquals(3, view.closures().size());
        assertEquals(DEP - HOUR, view.closures().get(0).startUtc());
        assertEquals(DEP, view.closures().get(1).startUtc());
        assertTrue(view.closures().get(2).allowEmergency());
        assertEquals("op-test", view.closures().get(1).operator());

        ApiException queryMissing = assertThrows(ApiException.class,
                () -> closureService.getRunwayClosures("ghost"));
        assertEquals(HttpStatus.NOT_FOUND, queryMissing.status());
    }

    @Test
    void closureKeyIdempotencyReplayConflictAndFailureNotConsuming() {
        createRunway("rw1", 5);
        MutationResponse first = addClosure("ck-fixed", "rw1", 1, DEP, DEP + HOUR, true);
        assertFalse(first.replayed());

        // 同键同参重放：返回首次结果，不产生第二个窗口，跑道版本不再推进
        MutationResponse replay = addClosure("ck-fixed", "rw1", 1, DEP, DEP + HOUR, true);
        assertTrue(replay.replayed());
        assertEquals(objectMapper.convertValue(first.data(), ClosureResult.class).closureId(),
                objectMapper.convertValue(replay.data(), ClosureResult.class).closureId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM runway_closure", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT version FROM runway WHERE runway_id = 'rw1'", Integer.class));

        // 同键异参（指纹含跑道版本/时段/例外标志/操作者）→ 409
        ApiException diffWindow = assertThrows(ApiException.class, () ->
                addClosure("ck-fixed", "rw1", 1, DEP, DEP + 2 * HOUR, true));
        assertEquals(HttpStatus.CONFLICT, diffWindow.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", diffWindow.code());
        ApiException diffFlag = assertThrows(ApiException.class, () ->
                addClosure("ck-fixed", "rw1", 1, DEP, DEP + HOUR, false));
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", diffFlag.code());
        ApiException diffOperator = assertThrows(ApiException.class, () ->
                closureService.createClosure(new ClosureCreateRequest(
                        "ck-fixed", "rw1", 1, DEP, DEP + HOUR, true, "op-other")));
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", diffOperator.code());

        // 失败不占键：先因重叠失败，随后同键合法参数可成功
        addClosure("ck-a", "rw1", 2, DEP + 10 * HOUR, DEP + 11 * HOUR, false);
        ApiException failed = assertThrows(ApiException.class, () ->
                addClosure("ck-fail", "rw1", 3, DEP + 10 * HOUR + 1, DEP + 12 * HOUR, false));
        assertEquals("CLOSURE_OVERLAP", failed.code());
        assertNull(jdbc.queryForList(
                "SELECT request_id FROM request_dedup WHERE request_id = 'ck-fail'")
                .stream().findFirst().orElse(null));
        MutationResponse ok = addClosure("ck-fail", "rw1", 3, DEP + 12 * HOUR, DEP + 13 * HOUR, false);
        assertFalse(ok.replayed());
    }

    // ============================ 审查：关闭窗口与优先级例外 ============================

    @Test
    void normalRouteIntersectingClosureIsRejected422AndHalfOpenBounds() {
        createRunway("rw1", 5);
        addClosure("ck-1", "rw1", 1, DEP, DEP + 2 * HOUR, true);

        // NORMAL 起飞时刻落入窗口 → 422 RUNWAY_CLOSED，不留审查记录，状态仍 DRAFT
        createRoute("r-normal", new FlightPlanDto("NORMAL", null, "rw1", DEP + HOUR, null, null));
        ApiException ex = assertThrows(ApiException.class, () -> review("r-normal"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("RUNWAY_CLOSED", ex.code());
        assertEquals(0, reviewCount());
        assertEquals("DRAFT", routeStatus("r-normal"));

        // 左闭：起飞时刻恰为窗口开始 → 相交，422
        createRoute("r-start", new FlightPlanDto("NORMAL", null, "rw1", DEP, null, null));
        ApiException atStart = assertThrows(ApiException.class, () -> review("r-start"));
        assertEquals("RUNWAY_CLOSED", atStart.code());

        // 右开：起飞时刻恰为窗口结束 → 不相交，通过
        createRoute("r-end", new FlightPlanDto("NORMAL", null, "rw1", DEP + 2 * HOUR, null, null));
        ReviewResultDto atEnd = objectMapper.convertValue(review("r-end").data(), ReviewResultDto.class);
        assertEquals("CLEAR", atEnd.conclusion());
        assertEquals("CLEAR", atEnd.reasonCode());
        assertEquals("APPROVED", routeStatus("r-end"));

        // 降落段落入窗口同样 422
        createRoute("r-arr", new FlightPlanDto("NORMAL", null, null, null, "rw1", DEP + HOUR));
        ApiException arr = assertThrows(ApiException.class, () -> review("r-arr"));
        assertEquals("RUNWAY_CLOSED", arr.code());
    }

    @Test
    void emergencyExceptionRequiresAllowingWindowAndEventNo() {
        createRunway("rw1", 5);
        createRunway("rw2", 5);
        addClosure("ck-allow", "rw1", 1, DEP, DEP + 2 * HOUR, true);
        addClosure("ck-deny", "rw2", 1, DEP, DEP + 2 * HOUR, false);

        // EMERGENCY + 允许例外窗口 + 事件号 → 通过，原因 EMERGENCY_EXCEPTION 并记录窗口
        createRoute("r-emg", new FlightPlanDto("EMERGENCY", "EV-001", "rw1", DEP + HOUR, null, null));
        ReviewResultDto ok = objectMapper.convertValue(review("r-emg").data(), ReviewResultDto.class);
        assertEquals("CLEAR", ok.conclusion());
        assertEquals("EMERGENCY_EXCEPTION", ok.reasonCode());
        String closureId = jdbc.queryForObject(
                "SELECT closure_id FROM runway_closure WHERE closure_key = 'ck-allow'", String.class);
        assertEquals(List.of(closureId), ok.hitClosureIds());
        assertEquals("APPROVED", routeStatus("r-emg"));
        // 审查原因可查询
        assertEquals("EMERGENCY_EXCEPTION",
                reviewService.getReview(ok.reviewId()).reasonCode());

        // EMERGENCY 无事件号 → 422 EMERGENCY_EVENT_NO_REQUIRED
        createRoute("r-no-ev", new FlightPlanDto("EMERGENCY", null, "rw1", DEP + HOUR, null, null));
        ApiException noEvent = assertThrows(ApiException.class, () -> review("r-no-ev"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, noEvent.status());
        assertEquals("EMERGENCY_EVENT_NO_REQUIRED", noEvent.code());

        // EMERGENCY 但窗口不允许例外 → 422 EMERGENCY_EXCEPTION_NOT_ALLOWED
        createRoute("r-deny", new FlightPlanDto("EMERGENCY", "EV-002", "rw2", DEP + HOUR, null, null));
        ApiException denied = assertThrows(ApiException.class, () -> review("r-deny"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, denied.status());
        assertEquals("EMERGENCY_EXCEPTION_NOT_ALLOWED", denied.code());

        // NORMAL 即使窗口允许例外也 422
        createRoute("r-normal", new FlightPlanDto("NORMAL", null, "rw1", DEP + HOUR, null, null));
        ApiException normal = assertThrows(ApiException.class, () -> review("r-normal"));
        assertEquals("RUNWAY_CLOSED", normal.code());
    }

    // ============================ 容量约束 ============================

    @Test
    void capacityExceededRejectsAndOtherHourPasses() {
        createRunway("rw1", 1);
        // 第一条航线占用 rw1 在 DEP 所在小时桶的唯一容量
        createRoute("r-cap1", new FlightPlanDto("NORMAL", null, "rw1", DEP, null, null));
        assertEquals("CLEAR", objectMapper.convertValue(
                review("r-cap1").data(), ReviewResultDto.class).conclusion());

        // 同一跑道同一小时 → 422 CAPACITY_EXCEEDED，不留审查记录
        createRoute("r-cap2", new FlightPlanDto("NORMAL", null, "rw1", DEP + HOUR / 2, null, null));
        ApiException full = assertThrows(ApiException.class, () -> review("r-cap2"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, full.status());
        assertEquals("CAPACITY_EXCEEDED", full.code());
        assertEquals(1, reviewCount());
        assertEquals("DRAFT", routeStatus("r-cap2"));

        // 下一小时桶 → 通过
        createRoute("r-cap3", new FlightPlanDto("NORMAL", null, "rw1", DEP + HOUR, null, null));
        assertEquals("CLEAR", objectMapper.convertValue(
                review("r-cap3").data(), ReviewResultDto.class).conclusion());
    }

    // ============================ 批量审查：全有或全无 ============================

    @Test
    void batchReviewApprovesAllOrNothing() {
        createRunway("rw1", 10);
        addClosure("ck-1", "rw1", 1, DEP, DEP + 2 * HOUR, false);
        createRoute("b-ok1", new FlightPlanDto("NORMAL", null, "rw1", DEP + 3 * HOUR, null, null));
        createRoute("b-ok2", new FlightPlanDto("NORMAL", null, "rw1", DEP + 4 * HOUR, null, null));
        createRoute("b-hit", new FlightPlanDto("NORMAL", null, "rw1", DEP + HOUR, null, null));

        // 全部通过 → 两条审查记录，两条航线 APPROVED
        MutationResponse ok = reviewService.reviewBatch(new BatchReviewRequest(0L,
                List.of(new BatchReviewRequest.BatchReviewItem("b-ok1", 1),
                        new BatchReviewRequest.BatchReviewItem("b-ok2", 1)),
                "req-" + rid("batch")));
        assertFalse(ok.replayed());
        assertEquals(2, reviewCount());
        assertEquals("APPROVED", routeStatus("b-ok1"));
        assertEquals("APPROVED", routeStatus("b-ok2"));

        // 任一拒绝（b-hit 命中关闭窗口）→ 整批 422，无任何记录或状态变更
        String batchKey = "req-" + rid("batch");
        ApiException rejected = assertThrows(ApiException.class, () ->
                reviewService.reviewBatch(new BatchReviewRequest(0L,
                        List.of(new BatchReviewRequest.BatchReviewItem("b-ok1", 1),
                                new BatchReviewRequest.BatchReviewItem("b-hit", 1)),
                        batchKey)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejected.status());
        assertEquals("RUNWAY_CLOSED", rejected.code());
        assertEquals(2, reviewCount());
        assertEquals("DRAFT", routeStatus("b-hit"));
        // 失败不占键：同键换用合法参数（仅含通过航线）可成功
        MutationResponse retry = reviewService.reviewBatch(new BatchReviewRequest(0L,
                List.of(new BatchReviewRequest.BatchReviewItem("b-ok1", 1)),
                batchKey));
        assertFalse(retry.replayed());
        assertEquals(3, reviewCount());
    }

    @Test
    void batchReviewComputesFinalCapacityAcrossBatch() {
        createRunway("rw1", 1);
        createRoute("b-c1", new FlightPlanDto("NORMAL", null, "rw1", DEP, null, null));
        createRoute("b-c2", new FlightPlanDto("NORMAL", null, "rw1", DEP + HOUR / 2, null, null));
        // 批内两条航线占用同一小时桶，容量 1 → 整批 422，无审查记录
        ApiException ex = assertThrows(ApiException.class, () ->
                reviewService.reviewBatch(new BatchReviewRequest(0L,
                        List.of(new BatchReviewRequest.BatchReviewItem("b-c1", 1),
                                new BatchReviewRequest.BatchReviewItem("b-c2", 1)),
                        "req-" + rid("batch"))));
        assertEquals("CAPACITY_EXCEEDED", ex.code());
        assertEquals(0, reviewCount());
        assertEquals("DRAFT", routeStatus("b-c1"));
        assertEquals("DRAFT", routeStatus("b-c2"));

        // 批内重复航线 → 400
        ApiException dup = assertThrows(ApiException.class, () ->
                reviewService.reviewBatch(new BatchReviewRequest(0L,
                        List.of(new BatchReviewRequest.BatchReviewItem("b-c1", 1),
                                new BatchReviewRequest.BatchReviewItem("b-c1", 1)),
                        "req-" + rid("batch"))));
        assertEquals(HttpStatus.BAD_REQUEST, dup.status());
        assertEquals("DUPLICATE_ROUTE_IN_BATCH", dup.code());
    }

    // ============================ 航线风险 ============================

    @Test
    void newClosureMarksFutureApprovedNormalRoutesAtRiskWithSnapshot() {
        createRunway("rw1", 5);
        createRoute("r-risk", new FlightPlanDto("NORMAL", null, "rw1", DEP + HOUR, null, null));
        createRoute("r-safe", new FlightPlanDto("NORMAL", null, "rw1", DEP + 3 * HOUR, null, null));
        review("r-risk");
        review("r-safe");
        assertEquals("APPROVED", routeStatus("r-risk"));

        // 新增关闭窗口命中 r-risk → 转 RUNWAY_RISK 并固化快照；r-safe 不受影响
        MutationResponse resp = addClosure("ck-1", "rw1", 1, DEP, DEP + 2 * HOUR, true);
        ClosureResult result = objectMapper.convertValue(resp.data(), ClosureResult.class);
        assertEquals(List.of("r-risk"), result.riskRouteIds());
        assertEquals("RUNWAY_RISK", routeStatus("r-risk"));
        assertEquals("APPROVED", routeStatus("r-safe"));

        // 风险快照可查询且与窗口一致
        RouteRiskResult risk = closureService.getRouteRisk("r-risk");
        assertEquals("RUNWAY_RISK", risk.status());
        assertEquals(result.closureId(), risk.closureId());
        assertEquals("rw1", risk.runwayId());
        assertEquals("DEPARTURE", risk.segment());
        assertEquals(DEP, risk.startUtc());
        assertEquals(DEP + 2 * HOUR, risk.endUtc());
        assertTrue(risk.allowEmergency());
        assertEquals("op-test", risk.operator());
        assertEquals(2, risk.runwayVersion());

        // 风险航线不能普通再次批准
        ApiException reReview = assertThrows(ApiException.class, () -> review("r-risk"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, reReview.status());
        assertEquals("ROUTE_RUNWAY_RISK", reReview.code());
        // 风险航线不能起飞
        ApiException depart = assertThrows(ApiException.class, () ->
                closureService.depart(new RouteStateRequest("r-risk", "req-" + rid("depart"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, depart.status());
        assertEquals("ROUTE_RUNWAY_RISK", depart.code());
    }

    @Test
    void departedRouteIsNotAffectedByNewClosure() {
        createRunway("rw1", 5);
        createRoute("r-fly", new FlightPlanDto("NORMAL", null, "rw1", DEP + HOUR, null, null));
        review("r-fly");
        // 起飞后 → DEPARTED
        MutationResponse departed = closureService.depart(
                new RouteStateRequest("r-fly", "req-" + rid("depart")));
        assertEquals("DEPARTED", objectMapper.convertValue(departed.data(), Map.class).get("status"));

        // 新关闭窗口命中其起降段：已起飞航线不改状态、不固化风险
        MutationResponse resp = addClosure("ck-1", "rw1", 1, DEP, DEP + 2 * HOUR, false);
        assertTrue(objectMapper.convertValue(resp.data(), ClosureResult.class).riskRouteIds().isEmpty());
        assertEquals("DEPARTED", routeStatus("r-fly"));
        ApiException noRisk = assertThrows(ApiException.class,
                () -> closureService.getRouteRisk("r-fly"));
        assertEquals(HttpStatus.NOT_FOUND, noRisk.status());
    }

    @Test
    void riskRouteCanRerouteCancelOrConvertOnly() {
        createRunway("rw1", 5);
        createRunway("rw2", 5);
        // 改航路径：风险航线替换到安全跑道后重新审查批准
        createRoute("r-reroute", new FlightPlanDto("NORMAL", null, "rw1", DEP + HOUR, null, null));
        review("r-reroute");
        addClosure("ck-1", "rw1", 1, DEP, DEP + 2 * HOUR, false);
        assertEquals("RUNWAY_RISK", routeStatus("r-reroute"));

        MutationResponse rerouted = reviewService.replaceRoute(new RouteReplaceRequest(
                "r-reroute", 1, pts(),
                new FlightPlanDto("NORMAL", null, "rw2", DEP + HOUR, null, null),
                "req-" + rid("replace")));
        assertFalse(rerouted.replayed());
        assertEquals("DRAFT", routeStatus("r-reroute"));
        // 风险快照已清除
        ApiException noRisk = assertThrows(ApiException.class,
                () -> closureService.getRouteRisk("r-reroute"));
        assertEquals(HttpStatus.NOT_FOUND, noRisk.status());
        // 改航后可正常审查批准（航线版本已推进到 2）
        ReviewResultDto approved = objectMapper.convertValue(
                reviewService.review(new ReviewRequest("r-reroute", 2, 0L, "req-" + rid("review")))
                        .data(), ReviewResultDto.class);
        assertEquals("CLEAR", approved.conclusion());
        assertEquals("APPROVED", routeStatus("r-reroute"));

        // 取消路径：风险航线可取消，风险清除，之后不可再审查
        // 起飞时刻避开既有窗口 [DEP, DEP+2h)，但落入随后登记的相接窗口 [DEP+2h, DEP+3h)
        createRoute("r-cancel", new FlightPlanDto("NORMAL", null, "rw1", DEP + 5 * HOUR / 2, null, null));
        review("r-cancel");
        addClosure("ck-2", "rw1", 2, DEP + 2 * HOUR, DEP + 3 * HOUR, false);
        assertEquals("RUNWAY_RISK", routeStatus("r-cancel"));
        closureService.cancel(new RouteStateRequest("r-cancel", "req-" + rid("cancel")));
        assertEquals("CANCELLED", routeStatus("r-cancel"));
        ApiException cancelled = assertThrows(ApiException.class, () -> review("r-cancel"));
        assertEquals(HttpStatus.CONFLICT, cancelled.status());
        assertEquals("ROUTE_ALREADY_CANCELLED", cancelled.code());
    }

    @Test
    void riskRouteEmergencyConversionFollowsSnapshotAllowance() {
        createRunway("rw1", 5);
        createRunway("rw2", 5);
        // 允许例外的窗口：可转合格紧急例外
        createRoute("r-conv", new FlightPlanDto("NORMAL", null, "rw1", DEP + HOUR, null, null));
        review("r-conv");
        addClosure("ck-allow", "rw1", 1, DEP, DEP + 2 * HOUR, true);
        assertEquals("RUNWAY_RISK", routeStatus("r-conv"));

        MutationResponse converted = closureService.convertToEmergencyException(
                new EmergencyExceptionRequest("r-conv", "EV-900", "req-" + rid("emg")));
        assertEquals("APPROVED", objectMapper.convertValue(converted.data(), Map.class).get("status"));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT category, event_no, status FROM route WHERE route_id = 'r-conv'");
        assertEquals("EMERGENCY", row.get("category"));
        assertEquals("EV-900", row.get("event_no"));
        assertEquals("APPROVED", row.get("status"));
        ApiException noRisk = assertThrows(ApiException.class,
                () -> closureService.getRouteRisk("r-conv"));
        assertEquals(HttpStatus.NOT_FOUND, noRisk.status());

        // 不允许例外的窗口：转换 422，状态与快照保持
        createRoute("r-deny", new FlightPlanDto("NORMAL", null, "rw2", DEP + HOUR, null, null));
        review("r-deny");
        addClosure("ck-deny", "rw2", 1, DEP, DEP + 2 * HOUR, false);
        assertEquals("RUNWAY_RISK", routeStatus("r-deny"));
        ApiException denied = assertThrows(ApiException.class, () ->
                closureService.convertToEmergencyException(
                        new EmergencyExceptionRequest("r-deny", "EV-901", "req-" + rid("emg"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, denied.status());
        assertEquals("EMERGENCY_EXCEPTION_NOT_ALLOWED", denied.code());
        assertEquals("RUNWAY_RISK", routeStatus("r-deny"));
        assertNotNull(closureService.getRouteRisk("r-deny"));

        // 非风险航线不能转紧急例外
        ApiException notAtRisk = assertThrows(ApiException.class, () ->
                closureService.convertToEmergencyException(
                        new EmergencyExceptionRequest("r-conv", "EV-902", "req-" + rid("emg"))));
        assertEquals(HttpStatus.CONFLICT, notAtRisk.status());
        assertEquals("ROUTE_NOT_AT_RISK", notAtRisk.code());
    }

    // ============================ 起飞与取消状态机 ============================

    @Test
    void departAndCancelStateMachine() {
        createRunway("rw1", 5);
        createRoute("r-d", new FlightPlanDto("NORMAL", null, "rw1", DEP + HOUR, null, null));
        // 未批准不能起飞
        ApiException notApproved = assertThrows(ApiException.class, () ->
                closureService.depart(new RouteStateRequest("r-d", "req-" + rid("depart"))));
        assertEquals(HttpStatus.CONFLICT, notApproved.status());
        assertEquals("ROUTE_NOT_APPROVED", notApproved.code());

        review("r-d");
        closureService.depart(new RouteStateRequest("r-d", "req-" + rid("depart")));
        assertEquals("DEPARTED", routeStatus("r-d"));
        // 重复起飞 → 409；已起飞不可取消、不可改航、不可再审查
        ApiException again = assertThrows(ApiException.class, () ->
                closureService.depart(new RouteStateRequest("r-d", "req-" + rid("depart"))));
        assertEquals("ROUTE_ALREADY_DEPARTED", again.code());
        ApiException cancelDeparted = assertThrows(ApiException.class, () ->
                closureService.cancel(new RouteStateRequest("r-d", "req-" + rid("cancel"))));
        assertEquals("ROUTE_ALREADY_DEPARTED", cancelDeparted.code());
        ApiException replaceDeparted = assertThrows(ApiException.class, () ->
                reviewService.replaceRoute(new RouteReplaceRequest(
                        "r-d", 1, pts(), "req-" + rid("replace"))));
        assertEquals("ROUTE_ALREADY_DEPARTED", replaceDeparted.code());
        ApiException reviewDeparted = assertThrows(ApiException.class, () -> review("r-d"));
        assertEquals("ROUTE_ALREADY_DEPARTED", reviewDeparted.code());

        // 取消后不可再取消、不可改航
        createRoute("r-c", null);
        closureService.cancel(new RouteStateRequest("r-c", "req-" + rid("cancel")));
        ApiException recancel = assertThrows(ApiException.class, () ->
                closureService.cancel(new RouteStateRequest("r-c", "req-" + rid("cancel"))));
        assertEquals("ROUTE_ALREADY_CANCELLED", recancel.code());
        ApiException replaceCancelled = assertThrows(ApiException.class, () ->
                reviewService.replaceRoute(new RouteReplaceRequest(
                        "r-c", 1, pts(), "req-" + rid("replace"))));
        assertEquals("ROUTE_ALREADY_CANCELLED", replaceCancelled.code());
    }

    // ============================ 并发与幂等 ============================

    @Test
    void concurrentClosureCreateAndReviewAreSerialized() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                String runwayId = "rw-" + i;
                String routeId = "cr-" + i;
                createRunway(runwayId, 5);
                createRoute(routeId, new FlightPlanDto(
                        "NORMAL", null, runwayId, DEP + HOUR, null, null));
                int iteration = i;
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return review(routeId);
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> closureFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return addClosure("ck-race-" + iteration, runwayId, 1,
                                DEP, DEP + 2 * HOUR, false);
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object closureResult = closureFuture.get(15, TimeUnit.SECONDS);
                assertTrue(closureResult instanceof MutationResponse, "关闭登记必须成功");

                if (reviewResult instanceof MutationResponse) {
                    // 审查先裁决：批准成功，随后关闭变更必须把它转为 RUNWAY_RISK
                    assertEquals("RUNWAY_RISK", routeStatus(routeId));
                    assertNotNull(closureService.getRouteRisk(routeId));
                } else {
                    // 关闭先裁决：审查 422 RUNWAY_CLOSED，航线仍 DRAFT 且无风险快照
                    ApiException ex = (ApiException) reviewResult;
                    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
                    assertEquals("RUNWAY_CLOSED", ex.code());
                    assertEquals("DRAFT", routeStatus(routeId));
                    ApiException noRisk = assertThrows(ApiException.class,
                            () -> closureService.getRouteRisk(routeId));
                    assertEquals(HttpStatus.NOT_FOUND, noRisk.status());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDepartAndClosureCreateHaveSingleOrdering() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                String runwayId = "rw-d" + i;
                String routeId = "cd-" + i;
                createRunway(runwayId, 5);
                createRoute(routeId, new FlightPlanDto(
                        "NORMAL", null, runwayId, DEP + HOUR, null, null));
                review(routeId);
                int iteration = i;
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Object> departFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return closureService.depart(
                                new RouteStateRequest(routeId, "req-depart-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> closureFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return addClosure("ck-depart-" + iteration, runwayId, 1,
                                DEP, DEP + 2 * HOUR, false);
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Object departResult = departFuture.get(15, TimeUnit.SECONDS);
                Object closureResult = closureFuture.get(15, TimeUnit.SECONDS);
                assertTrue(closureResult instanceof MutationResponse, "关闭登记必须成功");

                if (departResult instanceof MutationResponse) {
                    // 起飞先裁决：已起飞航线不受关闭影响
                    assertEquals("DEPARTED", routeStatus(routeId));
                    assertTrue(objectMapper.convertValue(
                            ((MutationResponse) closureResult).data(), ClosureResult.class)
                            .riskRouteIds().isEmpty());
                } else {
                    // 关闭先裁决：航线转风险，起飞被 422 拒绝
                    ApiException ex = (ApiException) departResult;
                    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
                    assertEquals("ROUTE_RUNWAY_RISK", ex.code());
                    assertEquals("RUNWAY_RISK", routeStatus(routeId));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameClosureKeyPlaysBackOneOutcome() throws Exception {
        createRunway("rw1", 5);
        String closureKey = "ck-concurrent";
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return addClosure(closureKey, "rw1", 1, DEP, DEP + HOUR, false);
                }));
            }
            int firstCount = 0;
            int replayCount = 0;
            String closureId = null;
            for (Future<MutationResponse> f : futures) {
                MutationResponse resp = f.get(15, TimeUnit.SECONDS);
                ClosureResult result = objectMapper.convertValue(resp.data(), ClosureResult.class);
                if (closureId == null) {
                    closureId = result.closureId();
                }
                assertEquals(closureId, result.closureId(), "重放必须返回同一窗口");
                if (resp.replayed()) {
                    replayCount++;
                } else {
                    firstCount++;
                }
            }
            assertEquals(1, firstCount, "仅一次关闭变更真正生效");
            assertEquals(n - 1, replayCount);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM runway_closure", Integer.class));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT version FROM runway WHERE runway_id = 'rw1'", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }
}
