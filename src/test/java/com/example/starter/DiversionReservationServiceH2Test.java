package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.AirportCreateRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyReportDto;
import com.example.starter.api.dto.ReplacementDto;
import com.example.starter.api.dto.ReservationCreateRequest;
import com.example.starter.api.dto.ReservationDepartRequest;
import com.example.starter.api.dto.ReservationResultDto;
import com.example.starter.api.dto.ReservationStateDto;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 备降机场预留业务的真实 H2 数据库测试（MODE=MySQL）。
 * 覆盖题干主流程、409/422 失败分支、不可变替换链（含多跳）、起飞保护、
 * 幂等重放/异参冲突/失败不占键，以及机场行锁串行化下的真实并发裁决。
 */
@SpringBootTest
@DisplayName("备降机场预留 H2 业务")
class DiversionReservationServiceH2Test {

    private static final String AIRPORT = "AP1";
    private static final long T0 = 1_700_000_000_000L;
    private static final long W_START = T0;
    private static final long W_END = T0 + 3_600_000L;

    @Autowired
    private com.example.starter.service.DiversionReservationService service;
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
        jdbc.update("DELETE FROM reservation_replacement");
        jdbc.update("DELETE FROM diversion_reservation");
        jdbc.update("DELETE FROM diversion_airport");
        jdbc.update("DELETE FROM request_dedup");
    }

    private String req(String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    private void createAirport(int capacity) {
        MutationResponse resp = service.createAirport(
                new AirportCreateRequest(AIRPORT, capacity, req("req-airport")));
        assertFalse(resp.replayed());
    }

    private ReservationCreateRequest reserveReq(String routeId, int priority,
                                                long start, long end) {
        return new ReservationCreateRequest(AIRPORT, routeId, priority, start, end,
                req("req-reserve"));
    }

    private ReservationResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReservationResultDto.class);
    }

    private ReservationResultDto reserve(String routeId, int priority) {
        return dataOf(service.reserve(reserveReq(routeId, priority, W_START, W_END)));
    }

    // ============================ 主流程：题干示例 ============================

    @Test
    @DisplayName("容量1：低优先级 L 占用后，高优先级 H 成功并整体替换 L；同级返回 409")
    void highPriorityReplacesLowerAndSamePriorityConflicts() {
        createAirport(1);

        ReservationResultDto low = reserve("route-L", 1);
        assertEquals("ACTIVE", low.status());
        assertTrue(low.displacedReservationIds().isEmpty());

        // 高优先级 H 申请成功并替换 L
        ReservationResultDto high = dataOf(service.reserve(
                reserveReq("route-H", 10, W_START, W_END)));
        assertEquals("ACTIVE", high.status());
        assertEquals(List.of(low.reservationId()), high.displacedReservationIds());
        assertEquals(1, high.replacements().size());
        ReplacementDto event = high.replacements().get(0);
        assertEquals(low.reservationId(), event.replacedReservationId());
        assertEquals("route-L", event.replacedRouteId());
        assertEquals(1, event.replacedPriority());
        assertEquals(high.reservationId(), event.replacementReservationId());
        assertEquals("route-H", event.replacementRouteId());
        assertEquals(10, event.replacementPriority());
        assertEquals(W_START, event.windowStart());
        assertEquals(W_END, event.windowEnd());
        assertNotNull(event.replacementId());

        // 原预留 L 已被挤出，不再占用容量；H 独占
        OccupancyReportDto report = service.queryOccupancy(AIRPORT, W_START, W_END);
        assertEquals(1, report.occupancies().size());
        assertEquals(high.reservationId(), report.occupancies().get(0).reservationId());
        assertEquals(10, report.occupancies().get(0).priority());
        // 查询返回不可变替换链
        assertEquals(1, report.replacementChain().size());
        assertEquals(event.replacementId(), report.replacementChain().get(0).replacementId());

        // 同级申请不得挤出 H：409，且原占用与容量不变
        ApiException same = assertThrows(ApiException.class,
                () -> service.reserve(reserveReq("route-H2", 10, W_START, W_END)));
        assertEquals(HttpStatus.CONFLICT, same.status());
        assertEquals("WINDOW_CAPACITY_CONFLICT", same.code());

        OccupancyReportDto after = service.queryOccupancy(AIRPORT, W_START, W_END);
        assertEquals(1, after.occupancies().size());
        assertEquals(high.reservationId(), after.occupancies().get(0).reservationId());
        assertEquals(1, after.replacementChain().size());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM diversion_reservation WHERE status = 'DISPLACED'",
                Integer.class));
    }

    @Test
    @DisplayName("容量充足或窗口首尾相接时无需挤出")
    void freeOrAdjacentWindowNeedsNoDisplacement() {
        createAirport(2);
        ReservationResultDto a = reserve("route-a", 1);
        ReservationResultDto b = reserve("route-b", 1);
        assertTrue(b.displacedReservationIds().isEmpty());
        assertEquals(2, service.queryOccupancy(AIRPORT, W_START, W_END).occupancies().size());

        // UTC 左闭右开：[W_END, W_END+1h) 与既有窗口首尾相接，不重叠，容量 1 也可接纳
        ReservationResultDto adjacent = dataOf(service.reserve(
                reserveReq("route-c", 1, W_END, W_END + 3_600_000L)));
        assertTrue(adjacent.displacedReservationIds().isEmpty());
        assertEquals(1, service.queryOccupancy(AIRPORT, W_END, W_END + 3_600_000L)
                .occupancies().size());
    }

    @Test
    @DisplayName("高优先级跨窗口申请时整体挤出两条部分重叠低优先级预留")
    void highPriorityAcrossTwoLowReservationsDisplacesBoth() {
        // 容量 2：低优先级 a=[0,30m)、b=[20m,60m) 可并存（重叠段占用 2）
        createAirport(2);
        ReservationResultDto a = dataOf(service.reserve(
                reserveReq("route-a", 1, W_START, W_START + 1_800_000L)));
        ReservationResultDto b = dataOf(service.reserve(
                reserveReq("route-b", 1, W_START + 1_200_000L, W_END)));
        assertTrue(a.displacedReservationIds().isEmpty());
        assertTrue(b.displacedReservationIds().isEmpty());

        // 容量降为 1 的等价场景通过新机场验证：先在容量1机场放置首尾相接两条低优先级，
        // 高优先级用跨两者的大窗口申请，必须把两条整体挤出
        String airport2 = "AP2";
        service.createAirport(new AirportCreateRequest(airport2, 1, req("req-airport2")));
        long s1 = W_START;
        long e1 = W_START + 1_800_000L;
        long s2 = e1; // 左闭右开首尾相接
        long e2 = W_END;
        ReservationResultDto l1 = dataOf(service.reserve(
                new ReservationCreateRequest(airport2, "low-1", 1, s1, e1, req("req-l1"))));
        ReservationResultDto l2 = dataOf(service.reserve(
                new ReservationCreateRequest(airport2, "low-2", 1, s2, e2, req("req-l2"))));

        ReservationResultDto high = dataOf(service.reserve(
                new ReservationCreateRequest(airport2, "high", 9, s1, e2, req("req-high"))));
        assertEquals(2, high.displacedReservationIds().size());
        assertTrue(high.displacedReservationIds().containsAll(
                List.of(l1.reservationId(), l2.reservationId())));
        assertEquals(2, high.replacements().size());

        OccupancyReportDto report = service.queryOccupancy(airport2, s1, e2);
        assertEquals(1, report.occupancies().size());
        assertEquals(high.reservationId(), report.occupancies().get(0).reservationId());
        assertEquals(2, report.replacementChain().size());
    }

    // ============================ 409 / 422 失败分支 ============================

    @Test
    @DisplayName("窗口重叠且无法合法挤出：低优先级不能挤出高优先级，返回 409 且状态不变")
    void lowerPriorityCannotDisplaceHigherAndStateStays() {
        createAirport(1);
        ReservationResultDto high = reserve("route-high", 10);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.reserve(reserveReq("route-low", 1, W_START, W_END)));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("WINDOW_CAPACITY_CONFLICT", ex.code());

        // 任何拒绝都保持原预留和容量：无新预留、无替换事件、高优先级仍独占
        assertEquals(1, service.queryOccupancy(AIRPORT, W_START, W_END).occupancies().size());
        assertEquals(high.reservationId(),
                service.queryOccupancy(AIRPORT, W_START, W_END).occupancies().get(0).reservationId());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM diversion_reservation", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation_replacement", Integer.class));
    }

    @Test
    @DisplayName("容量参数非法返回 422；重复创建机场返回 409")
    void invalidAirportCapacityIs422AndDuplicateIs409() {
        ApiException zero = assertThrows(ApiException.class, () -> service.createAirport(
                new AirportCreateRequest("BAD0", 0, req("req-bad0"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, zero.status());
        assertEquals("INVALID_AIRPORT_CAPACITY", zero.code());

        ApiException negative = assertThrows(ApiException.class, () -> service.createAirport(
                new AirportCreateRequest("BADN", -3, req("req-badn"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, negative.status());

        createAirport(1);
        ApiException dup = assertThrows(ApiException.class, () -> service.createAirport(
                new AirportCreateRequest(AIRPORT, 2, req("req-dup"))));
        assertEquals(HttpStatus.CONFLICT, dup.status());
        // 容量保持首次值
        assertEquals(1, jdbc.queryForObject(
                "SELECT capacity FROM diversion_airport WHERE airport_id = ?",
                Integer.class, AIRPORT));
    }

    @Test
    @DisplayName("时间参数非法（start>=end）返回 422；机场不存在返回 422")
    void invalidWindowAndUnknownAirportAre422() {
        createAirport(1);

        ApiException equal = assertThrows(ApiException.class, () -> service.reserve(
                reserveReq("route-e", 1, W_START, W_START)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, equal.status());
        assertEquals("INVALID_WINDOW", equal.code());

        ApiException reversed = assertThrows(ApiException.class, () -> service.reserve(
                reserveReq("route-r", 1, W_END, W_START)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, reversed.status());

        ApiException noAirport = assertThrows(ApiException.class, () -> service.reserve(
                new ReservationCreateRequest("GHOST", "route-x", 1, W_START, W_END,
                        req("req-ghost"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, noAirport.status());
        assertEquals("AIRPORT_NOT_FOUND", noAirport.code());

        // 全部拒绝不产生任何预留
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM diversion_reservation", Integer.class));
    }

    @Test
    @DisplayName("查询时段非法返回 422；机场不存在返回 404")
    void invalidQueryWindowIs422AndUnknownAirportIs404() {
        createAirport(1);
        ApiException bad = assertThrows(ApiException.class,
                () -> service.queryOccupancy(AIRPORT, W_END, W_START));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, bad.status());

        ApiException missing = assertThrows(ApiException.class,
                () -> service.queryOccupancy("GHOST", W_START, W_END));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    // ============================ 起飞保护 ============================

    @Test
    @DisplayName("已起飞预留不能被挤出：高优先级申请 409，原占用保留")
    void departedReservationCannotBeDisplaced() {
        createAirport(1);
        ReservationResultDto low = reserve("route-L", 1);
        MutationResponse departResp = service.depart(
                new ReservationDepartRequest(low.reservationId(), req("req-depart")));
        ReservationStateDto state = objectMapper.convertValue(
                departResp.data(), ReservationStateDto.class);
        assertTrue(state.departed());
        assertEquals("ACTIVE", state.status());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.reserve(reserveReq("route-H", 10, W_START, W_END)));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("DEPARTED_RESERVATION_BLOCKS", ex.code());

        OccupancyReportDto report = service.queryOccupancy(AIRPORT, W_START, W_END);
        assertEquals(1, report.occupancies().size());
        assertEquals(low.reservationId(), report.occupancies().get(0).reservationId());
        assertTrue(report.occupancies().get(0).departed());
        assertEquals(0, report.replacementChain().size());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation_replacement", Integer.class));
    }

    @Test
    @DisplayName("已被挤出的预留不能起飞；重复起飞 409；挤出方先起飞不改写旧替换链")
    void displacedCannotDepartAndChainSurvivesLaterDeparture() {
        createAirport(1);
        ReservationResultDto low = reserve("route-L", 1);
        ReservationResultDto high = dataOf(service.reserve(
                reserveReq("route-H", 10, W_START, W_END)));

        // L 已被挤出，不能起飞
        ApiException displaced = assertThrows(ApiException.class, () -> service.depart(
                new ReservationDepartRequest(low.reservationId(), req("req-depart-l"))));
        assertEquals(HttpStatus.CONFLICT, displaced.status());
        assertEquals("RESERVATION_DISPLACED", displaced.code());

        // H 正常起飞
        service.depart(new ReservationDepartRequest(high.reservationId(), req("req-depart-h")));
        // 重复起飞 409
        ApiException twice = assertThrows(ApiException.class, () -> service.depart(
                new ReservationDepartRequest(high.reservationId(), req("req-depart-h2"))));
        assertEquals(HttpStatus.CONFLICT, twice.status());

        // 后续起飞状态不改写旧替换结果：事件仍在，且记录的是挤出时快照
        OccupancyReportDto report = service.queryOccupancy(AIRPORT, W_START, W_END);
        assertEquals(1, report.occupancies().size());
        assertTrue(report.occupancies().get(0).departed());
        assertEquals(1, report.replacementChain().size());
        ReplacementDto event = report.replacementChain().get(0);
        assertEquals(low.reservationId(), event.replacedReservationId());
        assertEquals(high.reservationId(), event.replacementReservationId());
        assertEquals(1, event.replacedPriority());
        assertEquals(10, event.replacementPriority());
    }

    @Test
    @DisplayName("起飞不存在的预留返回 404")
    void departUnknownReservationIs404() {
        createAirport(1);
        ApiException ex = assertThrows(ApiException.class, () -> service.depart(
                new ReservationDepartRequest("rsv_ghost", req("req-depart-x"))));
        assertEquals(HttpStatus.NOT_FOUND, ex.status());
    }

    // ============================ 多跳不可变替换链 ============================

    @Test
    @DisplayName("多跳挤出链：L1 被 H1 挤出、H1 又被 H2 挤出，链不可变且 L1 保持 DISPLACED")
    void multiHopReplacementChainIsImmutable() {
        createAirport(1);
        ReservationResultDto l1 = reserve("route-L1", 1);
        ReservationResultDto h1 = dataOf(service.reserve(
                reserveReq("route-H1", 5, W_START, W_END)));
        ReservationResultDto h2 = dataOf(service.reserve(
                reserveReq("route-H2", 9, W_START, W_END)));

        assertEquals(List.of(l1.reservationId()), h1.displacedReservationIds());
        assertEquals(List.of(h1.reservationId()), h2.displacedReservationIds());

        // 最终只有 H2 占用；L1、H1 均 DISPLACED；两条不可变事件都在
        OccupancyReportDto report = service.queryOccupancy(AIRPORT, W_START, W_END);
        assertEquals(1, report.occupancies().size());
        assertEquals(h2.reservationId(), report.occupancies().get(0).reservationId());
        assertEquals(2, report.replacementChain().size());

        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation_replacement", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM diversion_reservation WHERE status = 'DISPLACED'",
                Integer.class));
        // 被两次挤出过程中 L1 的状态没有被复活或二次改写
        assertEquals("DISPLACED", jdbc.queryForObject(
                "SELECT status FROM diversion_reservation WHERE reservation_id = ?",
                String.class, l1.reservationId()));
    }

    // ============================ 幂等 ============================

    @Test
    @DisplayName("同键同参重放：返回首次结果（含首次被替换清单），不再次占用或写事件")
    void sameRequestReplaysFirstOutcomeIncludingReplacements() {
        createAirport(1);
        ReservationResultDto low = reserve("route-L", 1);

        String requestId = req("req-high-fixed");
        ReservationCreateRequest request = new ReservationCreateRequest(
                AIRPORT, "route-H", 10, W_START, W_END, requestId);
        MutationResponse first = service.reserve(request);
        assertFalse(first.replayed());
        ReservationResultDto firstData = dataOf(first);
        assertEquals(List.of(low.reservationId()), firstData.displacedReservationIds());

        // 同键同参重放：原样返回首次结果（含替换清单），replayed=true
        MutationResponse replay = service.reserve(
                new ReservationCreateRequest(AIRPORT, "route-H", 10, W_START, W_END, requestId));
        assertTrue(replay.replayed());
        ReservationResultDto replayData = dataOf(replay);
        assertEquals(firstData.reservationId(), replayData.reservationId());
        assertEquals(firstData.displacedReservationIds(), replayData.displacedReservationIds());
        assertEquals(1, replayData.replacements().size());
        assertEquals(firstData.replacements().get(0).replacementId(),
                replayData.replacements().get(0).replacementId());

        // 没有第二条新预留或重复事件
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM diversion_reservation", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation_replacement", Integer.class));
    }

    @Test
    @DisplayName("同键换机场或窗口返回 409；同键异种操作也 409")
    void sameRequestWithDifferentAirportOrWindowConflicts() {
        createAirport(1);
        service.createAirport(new AirportCreateRequest("AP2", 1, req("req-ap2")));
        String requestId = req("req-immut");
        service.reserve(new ReservationCreateRequest(
                AIRPORT, "route-x", 1, W_START, W_END, requestId));

        ApiException diffWindow = assertThrows(ApiException.class, () -> service.reserve(
                new ReservationCreateRequest(AIRPORT, "route-x", 1,
                        W_START + 1_000L, W_END, requestId)));
        assertEquals(HttpStatus.CONFLICT, diffWindow.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", diffWindow.code());

        ApiException diffAirport = assertThrows(ApiException.class, () -> service.reserve(
                new ReservationCreateRequest("AP2", "route-x", 1, W_START, W_END, requestId)));
        assertEquals(HttpStatus.CONFLICT, diffAirport.status());

        ApiException diffKind = assertThrows(ApiException.class, () -> service.depart(
                new ReservationDepartRequest("anything", requestId)));
        assertEquals(HttpStatus.CONFLICT, diffKind.status());
    }

    @Test
    @DisplayName("被 409 拒绝的申请不占用 requestId，同键随后可成功")
    void rejectedRequestDoesNotConsumeIdempotencyKey() {
        createAirport(1);
        reserve("route-high", 10);
        String requestId = req("req-retry");
        ApiException rejected = assertThrows(ApiException.class, () -> service.reserve(
                new ReservationCreateRequest(AIRPORT, "route-low", 1,
                        W_START, W_END, requestId)));
        assertEquals(HttpStatus.CONFLICT, rejected.status());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                Integer.class, requestId));

        // 高优先级释放容量的方式：被挤出场景不存在“释放”，这里用不重叠窗口验证键可复用
        MutationResponse ok = service.reserve(new ReservationCreateRequest(
                AIRPORT, "route-next", 10, W_END, W_END + 1_000L, requestId));
        assertFalse(ok.replayed());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                Integer.class, requestId));
    }

    // ============================ 并发：按提交时容量裁决 ============================

    @Test
    @DisplayName("并发：高优先级 H 与低优先级 L 竞争容量1，绝不超卖且 L 不会与 H 同时 ACTIVE")
    void concurrentHighAndLowNeverOversell() throws Exception {
        int rounds = 15;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < rounds; i++) {
                final int round = i;
                String airport = "CP" + round;
                service.createAirport(
                        new AirportCreateRequest(airport, 1, "req-cp-airport-" + round));
                CyclicBarrier barrier = new CyclicBarrier(2);

                Future<Object> highFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.reserve(new ReservationCreateRequest(
                                airport, "route-H", 10, W_START, W_END, "req-cp-high-" + round));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> lowFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.reserve(new ReservationCreateRequest(
                                airport, "route-L", 1, W_START, W_END, "req-cp-low-" + round));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object highResult = highFuture.get(20, TimeUnit.SECONDS);
                Object lowResult = lowFuture.get(20, TimeUnit.SECONDS);

                // H 先提交 → L 必须 409；L 先提交 → H 整体挤出 L。
                // 两种顺序的终态都只能是：H 独占 ACTIVE，L 要么不存在 409、要么 DISPLACED。
                Integer activeCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM diversion_reservation "
                                + "WHERE airport_id = ? AND status = 'ACTIVE'",
                        Integer.class, airport);
                assertEquals(1, activeCount, "容量 1 任意提交顺序下都只能有一个 ACTIVE 占用");
                Integer activeHigh = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM diversion_reservation "
                                + "WHERE airport_id = ? AND route_id = 'route-H' AND status = 'ACTIVE'",
                        Integer.class, airport);
                assertEquals(1, activeHigh, "高优先级 H 在任何提交顺序下最终都必须 ACTIVE");
                Integer activeLow = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM diversion_reservation "
                                + "WHERE airport_id = ? AND route_id = 'route-L' AND status = 'ACTIVE'",
                        Integer.class, airport);
                assertEquals(0, activeLow, "低优先级 L 不得与 H 同时 ACTIVE");
                if (lowResult instanceof MutationResponse mr) {
                    // L 曾短暂成功：则其最终必须已被 H 挤出，且 H 也成功
                    assertTrue(highResult instanceof MutationResponse);
                    ReservationResultDto l = objectMapper.convertValue(
                            mr.data(), ReservationResultDto.class);
                    assertEquals("DISPLACED", jdbc.queryForObject(
                            "SELECT status FROM diversion_reservation WHERE reservation_id = ?",
                            String.class, l.reservationId()));
                } else {
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) lowResult).status());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发：同级竞争容量1，恰好一个成功、另一个 409，无超卖")
    void concurrentSamePriorityExactlyOneWins() throws Exception {
        int rounds = 15;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < rounds; i++) {
                final int round = i;
                String airport = "SP" + round;
                service.createAirport(
                        new AirportCreateRequest(airport, 1, "req-sp-airport-" + round));
                int n = 2;
                CyclicBarrier barrier = new CyclicBarrier(n);
                List<Future<Object>> futures = new java.util.ArrayList<>();
                for (int t = 0; t < n; t++) {
                    final int tt = t;
                    futures.add(pool.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        try {
                            return service.reserve(new ReservationCreateRequest(
                                    airport, "route-" + tt, 5, W_START, W_END,
                                    "req-sp-" + round + "-" + tt));
                        } catch (ApiException ex) {
                            return ex;
                        }
                    }));
                }
                int success = 0;
                int conflict = 0;
                for (Future<Object> f : futures) {
                    Object result = f.get(20, TimeUnit.SECONDS);
                    if (result instanceof MutationResponse) {
                        success++;
                    } else {
                        assertEquals(HttpStatus.CONFLICT, ((ApiException) result).status());
                        conflict++;
                    }
                }
                assertEquals(1, success, "同级并发恰好一个申请成功");
                assertEquals(1, conflict);
                assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM diversion_reservation "
                                + "WHERE airport_id = ? AND status = 'ACTIVE'",
                        Integer.class, airport));
                assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM reservation_replacement", Integer.class));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发：高优先级申请与起飞竞争，终态占用不超卖，替换不半成")
    void concurrentReserveAndDepartNeverOversell() throws Exception {
        int rounds = 15;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < rounds; i++) {
                final int round = i;
                String airport = "DP" + round;
                service.createAirport(
                        new AirportCreateRequest(airport, 1, "req-dp-airport-" + round));
                ReservationResultDto low = dataOf(service.reserve(
                        new ReservationCreateRequest(airport, "route-L", 1,
                                W_START, W_END, "req-dp-low-" + round)));

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Object> reserveFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.reserve(new ReservationCreateRequest(
                                airport, "route-H", 10, W_START, W_END,
                                "req-dp-high-" + round));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> departFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.depart(new ReservationDepartRequest(
                                low.reservationId(), "req-dp-depart-" + round));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reserveResult = reserveFuture.get(20, TimeUnit.SECONDS);
                Object departResult = departFuture.get(20, TimeUnit.SECONDS);

                Integer active = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM diversion_reservation "
                                + "WHERE airport_id = ? AND status = 'ACTIVE'",
                        Integer.class, airport);
                // 起飞先提交 → H 被已起飞预留阻挡（409），L 保持 ACTIVE；
                // 预留先提交 → L 被挤出，起飞 409，H 独占 ACTIVE。
                // 任一合法终态 ACTIVE 数都必须恰为 1，绝不超卖。
                assertEquals(1, active, "起飞与高优先级申请竞争后容量仍必须恰为 1");

                boolean lowDepartedActive = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT departed FROM diversion_reservation WHERE reservation_id = ?",
                        Boolean.class, low.reservationId()))
                        && "ACTIVE".equals(jdbc.queryForObject(
                        "SELECT status FROM diversion_reservation WHERE reservation_id = ?",
                        String.class, low.reservationId()));
                if (lowDepartedActive) {
                    // 起飞赢：H 必须 409，不能挤出已起飞预留
                    assertTrue(reserveResult instanceof ApiException);
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) reserveResult).status());
                    assertTrue(departResult instanceof MutationResponse);
                } else {
                    // 挤出赢：L 必须 DISPLACED、departed=FALSE，H ACTIVE
                    assertEquals("DISPLACED", jdbc.queryForObject(
                            "SELECT status FROM diversion_reservation WHERE reservation_id = ?",
                            String.class, low.reservationId()));
                    assertTrue(reserveResult instanceof MutationResponse);
                    assertTrue(departResult instanceof ApiException);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发：同键同参恰好一次业务生效，其余线程重放同一预留与替换结果")
    void concurrentSameRequestIdPlaysBackOneOutcome() throws Exception {
        createAirport(1);
        // 先放一条低优先级占用，使首次成功携带替换清单
        ReservationResultDto low = reserve("route-L", 1);
        String requestId = "concurrent-same-reservation-key";
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.reserve(new ReservationCreateRequest(
                            AIRPORT, "route-H", 10, W_START, W_END, requestId));
                }));
            }
            int first = 0;
            int replay = 0;
            String reservationId = null;
            for (Future<MutationResponse> f : futures) {
                MutationResponse resp = f.get(20, TimeUnit.SECONDS);
                ReservationResultDto dto = objectMapper.convertValue(
                        resp.data(), ReservationResultDto.class);
                if (resp.replayed()) {
                    replay++;
                } else {
                    first++;
                }
                if (reservationId == null) {
                    reservationId = dto.reservationId();
                } else {
                    assertEquals(reservationId, dto.reservationId());
                }
                assertEquals(List.of(low.reservationId()), dto.displacedReservationIds());
            }
            assertEquals(1, first);
            assertEquals(n - 1, replay);
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM diversion_reservation", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM reservation_replacement", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                    Integer.class, requestId));
        } finally {
            pool.shutdownNow();
        }
    }
}
