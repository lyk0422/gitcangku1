package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.ClosureCreateRequest;
import com.example.starter.api.dto.FlightActionRequest;
import com.example.starter.api.dto.FlightBatchReviewRequest;
import com.example.starter.api.dto.FlightBatchReviewResult;
import com.example.starter.api.dto.FlightCreateRequest;
import com.example.starter.api.dto.FlightDetailResult;
import com.example.starter.api.dto.FlightEmergencyConvertRequest;
import com.example.starter.api.dto.FlightResult;
import com.example.starter.api.dto.FlightReviewReasonDto;
import com.example.starter.api.dto.FlightRerouteRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RunwayCreateRequest;
import com.example.starter.api.dto.RunwayWindowsResult;
import com.example.starter.service.AirspaceReviewService;
import com.example.starter.service.RunwayClosureService;
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
 * 跑道关闭与航班起降段审查的 H2 数据库测试（MODE=MySQL）。
 * 覆盖跑道时间窗重叠/端点相接、紧急例外、批量审查事务（整批不批准）、
 * 跑道风险与固化快照、改航/取消/紧急转换、closureKey 幂等与真实并发裁决。
 */
@SpringBootTest
class RunwayClosureServiceH2Test {

    /** 测试基准时刻：2026-01-01T00:00:00Z 的 epoch 毫秒。 */
    private static final long T0 = 1_767_225_600_000L;
    private static final long HOUR = 3_600_000L;

    @Autowired
    private RunwayClosureService service;
    @Autowired
    private AirspaceReviewService routeService;
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
        jdbc.update("DELETE FROM flight_review_item");
        jdbc.update("DELETE FROM flight_review");
        jdbc.update("DELETE FROM flight_risk");
        jdbc.update("DELETE FROM flight");
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

    private void createRoute(String routeId) {
        routeService.createRoute(new RouteCreateRequest(routeId,
                List.of(new RoutePointDto(0, 10), new RoutePointDto(100, 10)),
                "req-" + rid("route")));
    }

    private void createRunway(String runwayId, int hourlyCapacity) {
        service.createRunway(new RunwayCreateRequest(runwayId, hourlyCapacity,
                "req-" + rid("runway")));
    }

    private MutationResponse createClosure(String runwayId, int expectedVersion,
                                           long start, long end, boolean allowEmergency,
                                           String operator) {
        return service.createClosure(new ClosureCreateRequest(runwayId, expectedVersion,
                start, end, allowEmergency, operator));
    }

    private void registerFlight(String flightId, String routeType, String eventNo,
                                String depRunway, long depTime, String arrRunway, long arrTime) {
        createRoute("route-of-" + flightId);
        service.registerFlight(new FlightCreateRequest(flightId, "route-of-" + flightId,
                routeType, eventNo, depRunway, depTime, arrRunway, arrTime,
                "req-" + rid("flight")));
    }

    private FlightBatchReviewResult reviewBatch(String... flightIds) {
        MutationResponse resp = service.reviewBatch(new FlightBatchReviewRequest(
                List.of(flightIds), "req-" + rid("review")));
        return objectMapper.convertValue(resp.data(), FlightBatchReviewResult.class);
    }

    private FlightResult flightData(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), FlightResult.class);
    }

    private String flightStatus(String flightId) {
        return jdbc.queryForObject("SELECT status FROM flight WHERE flight_id = ?",
                String.class, flightId);
    }

    // ============================ 跑道时间窗 ============================

    @Test
    void closureOverlapRejectedButTouchingEndpointsAllowed() {
        createRunway("RWY1", 10);
        MutationResponse first = createClosure("RWY1", 0, T0, T0 + HOUR, false, "op-a");
        assertFalse(first.replayed());

        // 部分重叠 → 409 CLOSURE_WINDOW_OVERLAP
        ApiException overlap = assertThrows(ApiException.class, () ->
                createClosure("RWY1", 1, T0 + HOUR / 2, T0 + 2 * HOUR, false, "op-a"));
        assertEquals(HttpStatus.CONFLICT, overlap.status());
        assertEquals("CLOSURE_WINDOW_OVERLAP", overlap.code());

        // 端点相接（左闭右开）合法
        MutationResponse touching = createClosure("RWY1", 1, T0 + HOUR, T0 + 2 * HOUR, true, "op-b");
        assertFalse(touching.replayed());

        RunwayWindowsResult windows = service.getRunwayWindows("RWY1");
        assertEquals(2, windows.version());
        assertEquals(2, windows.windows().size());
        assertEquals(T0, windows.windows().get(0).startUtc());
        assertEquals(T0 + HOUR, windows.windows().get(1).startUtc());
        assertTrue(windows.windows().get(1).allowEmergency());
    }

    @Test
    void closureRequiresCurrentRunwayVersion() {
        createRunway("RWY2", 10);
        createClosure("RWY2", 0, T0, T0 + HOUR, false, "op-a");
        // 携带旧版本 → 409，且不产生任何窗口
        ApiException stale = assertThrows(ApiException.class, () ->
                createClosure("RWY2", 0, T0 + 2 * HOUR, T0 + 3 * HOUR, false, "op-a"));
        assertEquals(HttpStatus.CONFLICT, stale.status());
        assertEquals("RUNWAY_VERSION_CONFLICT", stale.code());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM runway_closure", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM runway WHERE runway_id = 'RWY2'", Integer.class));
    }

    @Test
    void closureValidationFailures() {
        createRunway("RWY3", 10);
        // 空窗口/反向窗口 → 400
        ApiException invalid = assertThrows(ApiException.class, () ->
                createClosure("RWY3", 0, T0 + HOUR, T0, false, "op-a"));
        assertEquals(HttpStatus.BAD_REQUEST, invalid.status());
        assertEquals("INVALID_CLOSURE_WINDOW", invalid.code());
        // 跑道不存在 → 404
        ApiException missing = assertThrows(ApiException.class, () ->
                createClosure("GHOST", 0, T0, T0 + HOUR, false, "op-a"));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
        assertEquals("RUNWAY_NOT_FOUND", missing.code());
        // 失败不留半成品
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM runway_closure", Integer.class));
    }

    @Test
    void closureKeyReplayAndFailureDoesNotConsumeKey() {
        createRunway("RWY4", 10);
        ClosureCreateRequest req = new ClosureCreateRequest(
                "RWY4", 0, T0, T0 + HOUR, true, "op-x");
        MutationResponse first = service.createClosure(req);
        assertFalse(first.replayed());

        // 同键（同跑道版本、规范化时段、例外标志、操作者）重放原结果
        MutationResponse replay = service.createClosure(req);
        assertTrue(replay.replayed());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM runway_closure", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM runway WHERE runway_id = 'RWY4'", Integer.class));

        // 失败不占键：版本错误失败后，携带正确版本的同一窗口可成功
        ClosureCreateRequest staleReq = new ClosureCreateRequest(
                "RWY4", 0, T0 + 2 * HOUR, T0 + 3 * HOUR, false, "op-y");
        assertThrows(ApiException.class, () -> service.createClosure(staleReq));
        ClosureCreateRequest fixedReq = new ClosureCreateRequest(
                "RWY4", 1, T0 + 2 * HOUR, T0 + 3 * HOUR, false, "op-y");
        MutationResponse fixed = service.createClosure(fixedReq);
        assertFalse(fixed.replayed());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM runway_closure", Integer.class));
    }

    // ============================ 批量审查：关闭影响 ============================

    @Test
    void normalFlightIntersectingClosureIsRejectedWithReason() {
        createRunway("RWY5", 10);
        createClosure("RWY5", 0, T0, T0 + HOUR, false, "op-a");
        registerFlight("F-N1", "NORMAL", null, "RWY5", T0 + 1000, "RWY5", T0 + 2 * HOUR);

        FlightBatchReviewResult result = reviewBatch("F-N1");
        assertFalse(result.approved());
        assertEquals(1, result.items().size());
        assertEquals("REJECTED", result.items().get(0).result());
        assertEquals("RUNWAY_CLOSED", result.items().get(0).reason());
        // 拒绝不留下半成品状态：航班仍为 PENDING
        assertEquals("PENDING", flightStatus("F-N1"));
        // 审查原因可查询
        FlightReviewReasonDto reason = service.getFlightReviewReason("F-N1");
        assertEquals("RUNWAY_CLOSED", reason.reason());
        assertEquals(result.reviewId(), reason.reviewId());
    }

    @Test
    void closureWindowIsHalfOpen() {
        createRunway("RWY6", 10);
        createClosure("RWY6", 0, T0, T0 + HOUR, false, "op-a");
        // 起飞时刻恰为窗口结束（右开）→ 不命中
        registerFlight("F-EDGE", "NORMAL", null, "RWY6", T0 + HOUR, "RWY6", T0 + 2 * HOUR);
        assertTrue(reviewBatch("F-EDGE").approved());
        // 起飞时刻恰为窗口开始（左闭）→ 命中
        registerFlight("F-EDGE2", "NORMAL", null, "RWY6", T0, "RWY6", T0 + 2 * HOUR);
        FlightBatchReviewResult hit = reviewBatch("F-EDGE2");
        assertFalse(hit.approved());
        assertEquals("RUNWAY_CLOSED", hit.items().get(0).reason());
    }

    @Test
    void emergencyFlightExceptionRules() {
        createRunway("RWY7", 10);
        createRunway("RWY8", 10);
        // RWY7 允许例外；RWY8 不允许
        createClosure("RWY7", 0, T0, T0 + HOUR, true, "op-a");
        createClosure("RWY8", 0, T0, T0 + HOUR, false, "op-a");

        // 允许例外 + 附事件号 → 通过
        registerFlight("F-E1", "EMERGENCY", "EV-1", "RWY7", T0 + 1000, "RWY7", T0 + 2 * HOUR);
        assertTrue(reviewBatch("F-E1").approved());
        assertEquals("APPROVED", flightStatus("F-E1"));

        // 允许例外但未附事件号 → 422 MISSING_EVENT_NO
        registerFlight("F-E2", "EMERGENCY", null, "RWY7", T0 + 2000, "RWY7", T0 + 2 * HOUR);
        FlightBatchReviewResult missing = reviewBatch("F-E2");
        assertFalse(missing.approved());
        assertEquals("MISSING_EVENT_NO", missing.items().get(0).reason());

        // 窗口不允许例外 → 422 EMERGENCY_EXCEPTION_NOT_ALLOWED
        registerFlight("F-E3", "EMERGENCY", "EV-3", "RWY8", T0 + 1000, "RWY8", T0 + 2 * HOUR);
        FlightBatchReviewResult notAllowed = reviewBatch("F-E3");
        assertFalse(notAllowed.approved());
        assertEquals("EMERGENCY_EXCEPTION_NOT_ALLOWED", notAllowed.items().get(0).reason());
    }

    @Test
    void batchReviewIsAllOrNothing() {
        createRunway("RWY9", 10);
        createClosure("RWY9", 0, T0, T0 + HOUR, false, "op-a");
        // F-OK 不命中窗口；F-BAD 命中
        registerFlight("F-OK", "NORMAL", null, "RWY9", T0 + 2 * HOUR, "RWY9", T0 + 3 * HOUR);
        registerFlight("F-BAD", "NORMAL", null, "RWY9", T0 + 1000, "RWY9", T0 + 2 * HOUR);

        FlightBatchReviewResult result = reviewBatch("F-OK", "F-BAD");
        assertFalse(result.approved());
        assertEquals(2, result.items().size());
        // 明细按 flightId 升序：F-BAD 拒绝、F-OK 单项通过但整批不批准
        assertEquals("REJECTED", result.items().get(0).result());
        assertEquals("RUNWAY_CLOSED", result.items().get(0).reason());
        assertEquals("APPROVED", result.items().get(1).result());
        // 整批不批准：两个航班都不改状态
        assertEquals("PENDING", flightStatus("F-OK"));
        assertEquals("PENDING", flightStatus("F-BAD"));
        // 拒绝的批次同样落库可查询
        assertFalse(service.getFlightReview(result.reviewId()).approved());
    }

    @Test
    void batchReviewRejectsNonPendingFlight() {
        createRunway("RWY10", 10);
        registerFlight("F-DONE", "NORMAL", null, "RWY10", T0, "RWY10", T0 + HOUR);
        assertTrue(reviewBatch("F-DONE").approved());
        // 已批准航班再次普通审查 → 拒绝 FLIGHT_NOT_REVIEWABLE
        FlightBatchReviewResult again = reviewBatch("F-DONE");
        assertFalse(again.approved());
        assertEquals("FLIGHT_NOT_REVIEWABLE", again.items().get(0).reason());
        assertEquals("APPROVED", flightStatus("F-DONE"));
    }

    @Test
    void batchReviewRejectsDuplicateAndUnknownFlight() {
        createRunway("RWY11", 10);
        registerFlight("F-X", "NORMAL", null, "RWY11", T0, "RWY11", T0 + HOUR);
        ApiException dup = assertThrows(ApiException.class, () ->
                reviewBatch("F-X", "F-X"));
        assertEquals(HttpStatus.BAD_REQUEST, dup.status());
        ApiException ghost = assertThrows(ApiException.class, () ->
                reviewBatch("F-X", "F-GHOST"));
        assertEquals(HttpStatus.NOT_FOUND, ghost.status());
        // 请求级失败不落审查记录
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flight_review", Integer.class));
    }

    // ============================ 批量审查：容量 ============================

    @Test
    void capacityExceededRejectsWholeBatch() {
        // 容量 1：同一跑道同一 UTC 小时最多 1 次起飞 + 1 次落地之外的占用
        createRunway("RWY12", 1);
        createRunway("RWY13", 10);
        registerFlight("F-C1", "NORMAL", null, "RWY12", T0, "RWY13", T0 + HOUR);
        registerFlight("F-C2", "NORMAL", null, "RWY12", T0 + 60_000, "RWY13", T0 + HOUR);
        // 先批准 F-C1，占用 RWY12 该小时槽位
        assertTrue(reviewBatch("F-C1").approved());
        // F-C2 同跑道同小时起飞 → 容量超限
        FlightBatchReviewResult exceeded = reviewBatch("F-C2");
        assertFalse(exceeded.approved());
        assertEquals("CAPACITY_EXCEEDED", exceeded.items().get(0).reason());
        assertEquals("PENDING", flightStatus("F-C2"));
        // 下一小时不再超限
        registerFlight("F-C3", "NORMAL", null, "RWY12", T0 + HOUR, "RWY13", T0 + 2 * HOUR);
        assertTrue(reviewBatch("F-C3").approved());
    }

    @Test
    void capacityComputedAcrossWholeBatch() {
        createRunway("RWY14", 1);
        createRunway("RWY15", 10);
        // 同批两个航班占用同一槽位：先计算全部最终容量，第二个超限 → 整批不批准
        registerFlight("F-B1", "NORMAL", null, "RWY14", T0, "RWY15", T0 + HOUR);
        registerFlight("F-B2", "NORMAL", null, "RWY14", T0 + 60_000, "RWY15", T0 + HOUR);
        FlightBatchReviewResult result = reviewBatch("F-B1", "F-B2");
        assertFalse(result.approved());
        assertEquals("APPROVED", result.items().get(0).result());
        assertEquals("CAPACITY_EXCEEDED", result.items().get(1).reason());
        // 整批不批准：单项通过的 F-B1 也不改状态
        assertEquals("PENDING", flightStatus("F-B1"));
        assertEquals("PENDING", flightStatus("F-B2"));
    }

    // ============================ 跑道风险 ============================

    @Test
    void newClosureMarksApprovedNormalFlightsAsRiskWithSnapshot() {
        createRunway("RWY16", 10);
        registerFlight("F-R1", "NORMAL", null, "RWY16", T0 + 1000, "RWY16", T0 + 3 * HOUR);
        registerFlight("F-R2", "NORMAL", null, "RWY16", T0 + 2000, "RWY16", T0 + 3 * HOUR);
        assertTrue(reviewBatch("F-R1", "F-R2").approved());
        // F-R1 起飞后不受新窗口影响
        service.depart(new FlightActionRequest("F-R1", "req-" + rid("depart")));
        assertEquals("DEPARTED", flightStatus("F-R1"));

        // 新关闭窗口命中 F-R1（已起飞）与 F-R2（已批准未起飞）的起飞段
        MutationResponse closure = createClosure("RWY16", 0, T0, T0 + HOUR, true, "op-risk");
        assertFalse(closure.replayed());
        assertEquals("DEPARTED", flightStatus("F-R1"));
        assertEquals("RUNWAY_RISK", flightStatus("F-R2"));

        // 风险航班固化窗口快照
        FlightDetailResult detail = service.getFlight("F-R2");
        assertEquals("RUNWAY_RISK", detail.flight().status());
        assertEquals(1, detail.risks().size());
        assertEquals(T0, detail.risks().get(0).startUtc());
        assertEquals(T0 + HOUR, detail.risks().get(0).endUtc());
        assertTrue(detail.risks().get(0).allowEmergency());
        assertEquals("op-risk", detail.risks().get(0).operator());
        assertEquals(1, detail.risks().get(0).runwayVersion());
        assertEquals("RWY16", detail.risks().get(0).runwayId());
        assertNotNull(detail.risks().get(0).closureId());
        // 已起飞航班无风险快照
        assertTrue(service.getFlight("F-R1").risks().isEmpty());
    }

    @Test
    void riskFlightCannotBeNormallyReapproved() {
        createRunway("RWY17", 10);
        registerFlight("F-RISK", "NORMAL", null, "RWY17", T0 + 1000, "RWY17", T0 + 2 * HOUR);
        assertTrue(reviewBatch("F-RISK").approved());
        createClosure("RWY17", 0, T0, T0 + HOUR, false, "op-a");
        assertEquals("RUNWAY_RISK", flightStatus("F-RISK"));
        // 风险航班不能普通再次批准
        FlightBatchReviewResult again = reviewBatch("F-RISK");
        assertFalse(again.approved());
        assertEquals("FLIGHT_NOT_REVIEWABLE", again.items().get(0).reason());
        assertEquals("RUNWAY_RISK", flightStatus("F-RISK"));
    }

    @Test
    void riskFlightRerouteThenReviewApproved() {
        createRunway("RWY18", 10);
        createRunway("RWY19", 10);
        registerFlight("F-RR", "NORMAL", null, "RWY18", T0 + 1000, "RWY18", T0 + 2 * HOUR);
        assertTrue(reviewBatch("F-RR").approved());
        createClosure("RWY18", 0, T0, T0 + HOUR, false, "op-a");
        assertEquals("RUNWAY_RISK", flightStatus("F-RR"));

        // 非风险航班不可改航
        registerFlight("F-NORM", "NORMAL", null, "RWY19", T0, "RWY19", T0 + HOUR);
        ApiException notReroutable = assertThrows(ApiException.class, () ->
                service.reroute(new FlightRerouteRequest("F-NORM", "RWY19", T0,
                        "RWY19", T0 + HOUR, "req-" + rid("reroute"))));
        assertEquals(HttpStatus.CONFLICT, notReroutable.status());

        // 改航到无关闭窗口的跑道 → 回到 PENDING，风险快照清除
        MutationResponse rerouted = service.reroute(new FlightRerouteRequest(
                "F-RR", "RWY19", T0 + 1000, "RWY19", T0 + 2 * HOUR, "req-" + rid("reroute")));
        assertEquals("PENDING", flightData(rerouted).status());
        assertEquals("RWY19", flightData(rerouted).depRunwayId());
        assertTrue(service.getFlight("F-RR").risks().isEmpty());
        // 重新审查通过
        assertTrue(reviewBatch("F-RR").approved());
        assertEquals("APPROVED", flightStatus("F-RR"));
    }

    @Test
    void riskFlightCancelAndTerminalStates() {
        createRunway("RWY20", 10);
        registerFlight("F-CXL", "NORMAL", null, "RWY20", T0 + 1000, "RWY20", T0 + 2 * HOUR);
        assertTrue(reviewBatch("F-CXL").approved());
        createClosure("RWY20", 0, T0, T0 + HOUR, false, "op-a");
        assertEquals("RUNWAY_RISK", flightStatus("F-CXL"));

        // 风险航班可取消
        MutationResponse cancelled = service.cancel(
                new FlightActionRequest("F-CXL", "req-" + rid("cancel")));
        assertEquals("CANCELLED", flightData(cancelled).status());
        // 终态不可再取消
        ApiException twice = assertThrows(ApiException.class, () ->
                service.cancel(new FlightActionRequest("F-CXL", "req-" + rid("cancel2"))));
        assertEquals(HttpStatus.CONFLICT, twice.status());
        assertEquals("FLIGHT_ALREADY_CANCELLED", twice.code());
    }

    @Test
    void riskFlightEmergencyConvertRules() {
        createRunway("RWY21", 10);
        createRunway("RWY22", 10);
        // F-OK 命中的窗口允许例外；F-NO 命中的窗口不允许
        registerFlight("F-OK", "NORMAL", null, "RWY21", T0 + 1000, "RWY21", T0 + 2 * HOUR);
        registerFlight("F-NO", "NORMAL", null, "RWY22", T0 + 1000, "RWY22", T0 + 2 * HOUR);
        assertTrue(reviewBatch("F-OK", "F-NO").approved());
        createClosure("RWY21", 0, T0, T0 + HOUR, true, "op-a");
        createClosure("RWY22", 0, T0, T0 + HOUR, false, "op-a");
        assertEquals("RUNWAY_RISK", flightStatus("F-OK"));
        assertEquals("RUNWAY_RISK", flightStatus("F-NO"));

        // 窗口不允许例外 → 422，状态不变
        ApiException notAllowed = assertThrows(ApiException.class, () ->
                service.convertToEmergency(new FlightEmergencyConvertRequest(
                        "F-NO", "EV-9", "req-" + rid("convert"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, notAllowed.status());
        assertEquals("EMERGENCY_EXCEPTION_NOT_ALLOWED", notAllowed.code());
        assertEquals("RUNWAY_RISK", flightStatus("F-NO"));

        // 允许例外 → 转为 EMERGENCY 并回到 PENDING，重新审查通过
        MutationResponse converted = service.convertToEmergency(
                new FlightEmergencyConvertRequest("F-OK", "EV-1", "req-" + rid("convert")));
        FlightResult convertedFlight = flightData(converted);
        assertEquals("EMERGENCY", convertedFlight.routeType());
        assertEquals("EV-1", convertedFlight.eventNo());
        assertEquals("PENDING", convertedFlight.status());
        assertTrue(reviewBatch("F-OK").approved());
        assertEquals("APPROVED", flightStatus("F-OK"));
    }

    // ============================ 起飞 ============================

    @Test
    void departOnlyFromApproved() {
        createRunway("RWY23", 10);
        registerFlight("F-DEP", "NORMAL", null, "RWY23", T0, "RWY23", T0 + HOUR);
        // PENDING 不可起飞
        ApiException pending = assertThrows(ApiException.class, () ->
                service.depart(new FlightActionRequest("F-DEP", "req-" + rid("depart"))));
        assertEquals(HttpStatus.CONFLICT, pending.status());
        assertEquals("FLIGHT_NOT_DEPARTABLE", pending.code());

        assertTrue(reviewBatch("F-DEP").approved());
        MutationResponse departed = service.depart(
                new FlightActionRequest("F-DEP", "req-" + rid("depart")));
        assertEquals("DEPARTED", flightData(departed).status());
        // 已起飞不可取消
        ApiException cancelDeparted = assertThrows(ApiException.class, () ->
                service.cancel(new FlightActionRequest("F-DEP", "req-" + rid("cancel"))));
        assertEquals("FLIGHT_ALREADY_DEPARTED", cancelDeparted.code());
    }

    // ============================ 幂等 ============================

    @Test
    void flightRegisterAndReviewReplay() {
        createRunway("RWY24", 10);
        createRoute("route-replay");
        FlightCreateRequest createReq = new FlightCreateRequest("F-IDEM", "route-replay",
                "NORMAL", null, "RWY24", T0, "RWY24", T0 + HOUR, "req-idem-create");
        MutationResponse first = service.registerFlight(createReq);
        MutationResponse replay = service.registerFlight(createReq);
        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flight", Integer.class));

        FlightBatchReviewRequest reviewReq = new FlightBatchReviewRequest(
                List.of("F-IDEM"), "req-idem-review");
        MutationResponse reviewFirst = service.reviewBatch(reviewReq);
        MutationResponse reviewReplay = service.reviewBatch(reviewReq);
        assertFalse(reviewFirst.replayed());
        assertTrue(reviewReplay.replayed());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flight_review", Integer.class));
        // 重放整批批准的审查不会重复推进状态
        assertEquals("APPROVED", flightStatus("F-IDEM"));
    }

    @Test
    void rejectedBatchReviewIsPersistedAndReplayed() {
        createRunway("RWY25", 10);
        createClosure("RWY25", 0, T0, T0 + HOUR, false, "op-a");
        registerFlight("F-REJ", "NORMAL", null, "RWY25", T0 + 1000, "RWY25", T0 + 2 * HOUR);
        FlightBatchReviewRequest req = new FlightBatchReviewRequest(
                List.of("F-REJ"), "req-rej-review");
        MutationResponse first = service.reviewBatch(req);
        FlightBatchReviewResult firstResult = objectMapper.convertValue(
                first.data(), FlightBatchReviewResult.class);
        assertFalse(firstResult.approved());
        // 同键重放同一拒绝结论，不新增审查记录
        MutationResponse replay = service.reviewBatch(req);
        assertTrue(replay.replayed());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flight_review", Integer.class));
        FlightBatchReviewResult queried = service.getFlightReview(firstResult.reviewId());
        assertFalse(queried.approved());
        assertEquals("RUNWAY_CLOSED", queried.items().get(0).reason());
    }

    // ============================ 并发 ============================

    @Test
    void concurrentSameClosureKeyPlaysBackOneOutcome() throws Exception {
        createRunway("RWY26", 10);
        ClosureCreateRequest req = new ClosureCreateRequest(
                "RWY26", 0, T0, T0 + HOUR, true, "op-race");
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.createClosure(req);
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
            assertEquals(1, firstCount, "同一 closureKey 只能有一次真正生效");
            assertEquals(n - 1, replayCount);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM runway_closure", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT version FROM runway WHERE runway_id = 'RWY26'", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentClosureAndDepartAreSerialized() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                String runway = "RWYC-" + i;
                String flight = "F-RACE-" + i;
                createRunway(runway, 10);
                registerFlight(flight, "NORMAL", null, runway, T0 + 1000, runway, T0 + 2 * HOUR);
                assertTrue(reviewBatch(flight).approved());

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Object> departFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.depart(new FlightActionRequest(
                                flight, "req-depart-" + flight));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> closureFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return createClosure(runway, 0, T0, T0 + HOUR, false, "op-race");
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object departResult = departFuture.get(15, TimeUnit.SECONDS);
                Object closureResult = closureFuture.get(15, TimeUnit.SECONDS);
                assertTrue(closureResult instanceof MutationResponse, "关闭登记必须成功");

                String status = flightStatus(flight);
                if (departResult instanceof MutationResponse) {
                    // 起飞先提交：航班 DEPARTED，随后的关闭窗口不影响已起飞航班
                    assertEquals("DEPARTED", status);
                    assertTrue(service.getFlight(flight).risks().isEmpty());
                } else {
                    // 关闭先提交：航班已转 RUNWAY_RISK，起飞必须 409 失败
                    ApiException ex = (ApiException) departResult;
                    assertEquals(HttpStatus.CONFLICT, ex.status());
                    assertEquals("FLIGHT_NOT_DEPARTABLE", ex.code());
                    assertEquals("RUNWAY_RISK", status);
                    assertEquals(1, service.getFlight(flight).risks().size());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentClosureAndBatchReviewAreSerialized() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                String runway = "RWYR-" + i;
                String flight = "F-RV-" + i;
                createRunway(runway, 10);
                registerFlight(flight, "NORMAL", null, runway, T0 + 1000, runway, T0 + 2 * HOUR);

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.reviewBatch(new FlightBatchReviewRequest(
                                List.of(flight), "req-review-" + flight));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> closureFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return createClosure(runway, 0, T0, T0 + HOUR, false, "op-race");
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object closureResult = closureFuture.get(15, TimeUnit.SECONDS);
                assertTrue(closureResult instanceof MutationResponse, "关闭登记必须成功");
                assertTrue(reviewResult instanceof MutationResponse, "审查必须完成（批准或拒绝）");

                FlightBatchReviewResult result = objectMapper.convertValue(
                        ((MutationResponse) reviewResult).data(), FlightBatchReviewResult.class);
                String status = flightStatus(flight);
                if (result.approved()) {
                    // 审查先裁决：批准；随后关闭窗口生效 → 转为 RUNWAY_RISK
                    assertEquals("RUNWAY_RISK", status);
                    assertEquals(1, service.getFlight(flight).risks().size());
                } else {
                    // 关闭先裁决：审查必须看到窗口并拒绝，航班保持 PENDING
                    assertEquals("RUNWAY_CLOSED", result.items().get(0).reason());
                    assertEquals("PENDING", status);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
