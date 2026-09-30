package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.AirportCreateRequest;
import com.example.starter.api.dto.DepartureRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyEntry;
import com.example.starter.api.dto.OccupancyView;
import com.example.starter.api.dto.ReservationRequest;
import com.example.starter.api.dto.ReservationResult;
import com.example.starter.service.DiversionService;
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
import java.time.ZoneOffset;
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
 * 备降机场预留业务的 H2 数据库测试（MODE=MySQL）。
 * 覆盖主流程（高优先级挤出低优先级、同级 409）、422/409/404 失败分支、
 * 已起飞不可挤、不可变多级替换链、幂等重放/异参冲突与真实并发下的
 * “按提交时容量裁决、不超卖、不部分替换”。不使用 mock 代替数据库结论。
 */
@SpringBootTest
@DisplayName("备降机场预留 H2 业务")
class DiversionServiceH2Test {

    /** 可控固定时钟（UTC）：created_at/updated_at 确定，时间语义不依赖真实流逝。 */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    private static final long FIXED_NOW = Instant.parse("2026-09-30T00:00:00Z").toEpochMilli();

    @Autowired
    private DiversionService service;
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
        jdbc.update("DELETE FROM diversion_reservation");
        jdbc.update("DELETE FROM diversion_airport");
        jdbc.update("DELETE FROM request_dedup");
    }

    private String reqId(String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    private void createAirport(String airportId, int capacity) {
        MutationResponse resp = service.createAirport(
                new AirportCreateRequest(airportId, capacity, reqId("airport-req")));
        assertFalse(resp.replayed());
    }

    private ReservationRequest reservation(String requestId, String routeId, String airportId,
                                           int priority, long start, long end) {
        return new ReservationRequest(requestId, routeId, airportId, priority, start, end);
    }

    private ReservationResult dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReservationResult.class);
    }

    private MutationResponse reserve(String routeId, String airportId, int priority,
                                     long start, long end) {
        return service.reserve(reservation(reqId("res-req"), routeId, airportId, priority, start, end));
    }

    private Map<String, Object> reservationRow(String reservationId) {
        return jdbc.queryForMap(
                "SELECT reservation_id, airport_id, route_id, priority, status, replaced_by, "
                        + "created_at, updated_at FROM diversion_reservation WHERE reservation_id = ?",
                reservationId);
    }

    private int countByStatus(String status) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM diversion_reservation WHERE status = ?", Integer.class, status);
    }

    // ============================ 题干主场景 ============================

    @Test
    @DisplayName("机场A容量1：未起飞低级L占用，高级H成功替换L，同级申请409")
    void highPriorityReplacesLowerAndSamePriorityConflicts() {
        createAirport("A", 1);

        MutationResponse low = reserve("L", "A", 1, 100, 200);
        ReservationResult lowResult = dataOf(low);
        assertEquals("RESERVED", lowResult.status());
        assertTrue(lowResult.displaced().isEmpty());

        // 高优先级重叠申请：成功且挤出清单包含 L
        MutationResponse high = reserve("H", "A", 9, 150, 250);
        ReservationResult highResult = dataOf(high);
        assertFalse(high.replayed());
        assertEquals(1, highResult.displaced().size());
        assertEquals(lowResult.reservationId(), highResult.displaced().get(0).reservationId());
        assertEquals(highResult.reservationId(), highResult.displaced().get(0).replacedBy());

        // L 已整体挤出，不可变替换关系落库
        Map<String, Object> lRow = reservationRow(lowResult.reservationId());
        assertEquals("DISPLACED", lRow.get("status"));
        assertEquals(highResult.reservationId(), lRow.get("replaced_by"));
        // 当前占用只有 H
        OccupancyView view = service.getOccupancy("A", 0, 1000);
        List<String> reservedNow = view.occupancy().stream()
                .filter(e -> "RESERVED".equals(e.status())).map(OccupancyEntry::reservationId).toList();
        assertEquals(List.of(highResult.reservationId()), reservedNow);

        // 相同优先级不得互相挤出：同级重叠申请 409
        ApiException conflict = assertThrows(ApiException.class,
                () -> reserve("H2", "A", 9, 150, 250));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals("WINDOW_CONFLICT", conflict.code());
        // 拒绝后原预留与容量不变：H 仍在，没有新预留
        assertEquals(1, countByStatus("RESERVED"));
        assertEquals(1, countByStatus("DISPLACED"));
    }

    @Test
    @DisplayName("容量2贪心：超载只挤结束最晚者即可释放后续时段，挤出数量最小")
    void highPriorityDisplacesMinimumViaGreedy() {
        // 合法占用（任一时段不超过 2）：L1[0,100)、L2[50,150)，[50,100) 恰好满 2
        createAirport("A", 2);
        ReservationResult l1 = dataOf(reserve("L1", "A", 1, 0, 100));
        ReservationResult l2 = dataOf(reserve("L2", "A", 1, 50, 150));

        // 新窗 [60,120) 在 [60,100) 造成 3 个占用：贪心挤出结束最晚的 L2，
        // [100,120) 仅剩新申请；L1 不受影响
        ReservationResult h = dataOf(reserve("H", "A", 9, 60, 120));
        assertEquals(List.of(l2.reservationId()),
                h.displaced().stream().map(d -> d.reservationId()).sorted().toList());
        assertEquals("RESERVED", reservationRow(l1.reservationId()).get("status"));
        assertEquals("DISPLACED", reservationRow(l2.reservationId()).get("status"));
        assertEquals(1, countByStatus("DISPLACED"));
        assertEquals(2, countByStatus("RESERVED"));
    }

    @Test
    @DisplayName("容量2：跨两段不相交满载区段的高级申请整体挤出两个低级预留，均按整窗释放")
    void displacesTwoVictimsAcrossDisjointFullSegments() {
        // 合法占用：[0,100) 上 L1、L2 满 2；[200,300) 上 L3、L4 满 2；两段互不相连
        createAirport("A", 2);
        ReservationResult l1 = dataOf(reserve("L1", "A", 1, 0, 100));
        ReservationResult l2 = dataOf(reserve("L2", "A", 1, 0, 150));
        ReservationResult l3 = dataOf(reserve("L3", "A", 1, 200, 300));
        ReservationResult l4 = dataOf(reserve("L4", "A", 1, 200, 350));

        // 高级新窗 [0,400) 先后在两段满载区各挤出一个结束最晚者：L2 与 L4
        ReservationResult h = dataOf(reserve("H", "A", 9, 0, 400));
        assertEquals(List.of(l2.reservationId(), l4.reservationId()),
                h.displaced().stream().map(d -> d.reservationId()).sorted().toList());
        assertEquals("RESERVED", reservationRow(l1.reservationId()).get("status"));
        assertEquals("DISPLACED", reservationRow(l2.reservationId()).get("status"));
        assertEquals("RESERVED", reservationRow(l3.reservationId()).get("status"));
        assertEquals("DISPLACED", reservationRow(l4.reservationId()).get("status"));
        assertEquals(2, countByStatus("DISPLACED"));
        assertEquals(3, countByStatus("RESERVED"));
    }

    @Test
    @DisplayName("UTC 左闭右开：相邻窗口（前窗end=后窗start）不重叠，不挤出不冲突")
    void adjacentWindowsDoNotConflict() {
        createAirport("A", 1);
        ReservationResult l = dataOf(reserve("L", "A", 1, 100, 200));
        // 新窗恰在 200 开始：[200,300) 与 [100,200) 不重叠
        ReservationResult h = dataOf(reserve("H", "A", 9, 200, 300));
        assertTrue(h.displaced().isEmpty());
        assertEquals("RESERVED", reservationRow(l.reservationId()).get("status"));
        assertEquals(2, countByStatus("RESERVED"));
    }

    @Test
    @DisplayName("容量2：超载点含一个同级与一个低级时，只挤低级即可容纳")
    void displaceLowerWhileKeepingSameLevel() {
        createAirport("A", 2);
        ReservationResult s = dataOf(reserve("S", "A", 5, 0, 100));
        ReservationResult l = dataOf(reserve("L", "A", 1, 50, 150));

        ReservationResult h = dataOf(reserve("H", "A", 5, 0, 150));
        assertEquals(List.of(l.reservationId()),
                h.displaced().stream().map(d -> d.reservationId()).toList());
        assertEquals("RESERVED", reservationRow(s.reservationId()).get("status"));
        assertEquals("DISPLACED", reservationRow(l.reservationId()).get("status"));
    }

    // ============================ 已起飞与替换链 ============================

    @Test
    @DisplayName("已起飞预留不能被挤出：即使高优先级重叠也409，原状态与容量不变")
    void departedReservationCannotBeDisplaced() {
        createAirport("A", 1);
        ReservationResult l = dataOf(reserve("L", "A", 1, 100, 200));
        MutationResponse departure = service.depart(
                new DepartureRequest(l.reservationId(), reqId("depart-req")));
        assertEquals("DEPARTED", objectMapper.convertValue(departure.data(), Map.class).get("status"));

        ApiException ex = assertThrows(ApiException.class,
                () -> reserve("H", "A", 9, 150, 250));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        // L 仍为 DEPARTED，没有写入 H，没有任何替换关系
        assertEquals("DEPARTED", reservationRow(l.reservationId()).get("status"));
        assertNull(reservationRow(l.reservationId()).get("replaced_by"));
        assertEquals(0, countByStatus("RESERVED"));
        assertEquals(1, countByStatus("DEPARTED"));
    }

    @Test
    @DisplayName("已被挤出的预留不能再起飞（409）")
    void displacedReservationCannotDepart() {
        createAirport("A", 1);
        ReservationResult l = dataOf(reserve("L", "A", 1, 100, 200));
        dataOf(reserve("H", "A", 9, 150, 250));
        ApiException ex = assertThrows(ApiException.class, () -> service.depart(
                new DepartureRequest(l.reservationId(), reqId("depart-req"))));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("RESERVATION_NOT_ACTIVE", ex.code());
        assertEquals("DISPLACED", reservationRow(l.reservationId()).get("status"));
    }

    @Test
    @DisplayName("重复起飞409；起飞不存在的预留404")
    void duplicateDepartureAndMissingReservation() {
        createAirport("A", 1);
        ReservationResult l = dataOf(reserve("L", "A", 1, 100, 200));
        service.depart(new DepartureRequest(l.reservationId(), reqId("depart-req")));
        ApiException twice = assertThrows(ApiException.class, () -> service.depart(
                new DepartureRequest(l.reservationId(), reqId("depart-req-2"))));
        assertEquals(HttpStatus.CONFLICT, twice.status());

        ApiException missing = assertThrows(ApiException.class, () -> service.depart(
                new DepartureRequest("dr_ghost", reqId("depart-req-3"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    @Test
    @DisplayName("不可变多级替换链：L被M挤出、M被H挤出，链路查询完整；起飞不改写旧结果")
    void multiLevelReplacementChainIsImmutable() {
        createAirport("A", 1);
        ReservationResult l = dataOf(reserve("L", "A", 1, 100, 400));
        ReservationResult m = dataOf(reserve("M", "A", 5, 100, 400));
        ReservationResult h = dataOf(reserve("H", "A", 9, 100, 400));

        OccupancyView view = service.getOccupancy("A", 0, 1000);
        OccupancyEntry lEntry = view.occupancy().stream()
                .filter(e -> e.reservationId().equals(l.reservationId())).findFirst().orElseThrow();
        assertEquals("DISPLACED", lEntry.status());
        // L 的替换链：L→M→H
        assertEquals(2, lEntry.chain().size());
        assertEquals(m.reservationId(), lEntry.chain().get(0).targetReservationId());
        assertEquals(h.reservationId(), lEntry.chain().get(1).targetReservationId());
        assertEquals(9, lEntry.chain().get(1).targetPriority());

        // H 起飞后，旧替换链不改写：L 仍记录被 M 挤出，M 仍记录被 H 挤出；
        // 链上 H 的当前状态变为 DEPARTED，但替换关系本身不变
        service.depart(new DepartureRequest(h.reservationId(), reqId("depart-req")));
        OccupancyView after = service.getOccupancy("A", 0, 1000);
        OccupancyEntry lAfter = after.occupancy().stream()
                .filter(e -> e.reservationId().equals(l.reservationId())).findFirst().orElseThrow();
        assertEquals(2, lAfter.chain().size());
        assertEquals(m.reservationId(), lAfter.chain().get(0).targetReservationId());
        assertEquals(h.reservationId(), lAfter.chain().get(1).targetReservationId());
        assertEquals("DEPARTED", lAfter.chain().get(1).targetStatus());
        assertEquals("DISPLACED",
                reservationRow(m.reservationId()).get("status"));
        assertEquals(h.reservationId(), reservationRow(m.reservationId()).get("replaced_by"));
    }

    @Test
    @DisplayName("查询只返回与查询时段重叠的预留，并按窗口开始排序")
    void occupancyQueryFiltersByWindow() {
        createAirport("A", 2);
        ReservationResult r1 = dataOf(reserve("R1", "A", 1, 0, 100));
        ReservationResult r2 = dataOf(reserve("R2", "A", 1, 200, 300));

        // 查询 [50,200)：与 R1 重叠（[0,100)），与 R2 仅在 200 相邻不重叠
        OccupancyView view = service.getOccupancy("A", 50, 200);
        assertEquals(List.of(r1.reservationId()),
                view.occupancy().stream().map(OccupancyEntry::reservationId).toList());
        assertEquals(2, view.capacity());

        // 宽查询返回两条，按 window_start 排序
        OccupancyView wide = service.getOccupancy("A", 0, 1000);
        assertEquals(List.of(r1.reservationId(), r2.reservationId()),
                wide.occupancy().stream().map(OccupancyEntry::reservationId).toList());
    }

    // ============================ 422 / 409 失败分支 ============================

    @Test
    @DisplayName("容量非正、机场重复、窗口非法、机场不存在均返回规定状态码")
    void invalidParametersRejected() {
        ApiException badCapacity = assertThrows(ApiException.class, () -> service.createAirport(
                new AirportCreateRequest("bad", 0, reqId("airport-req"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, badCapacity.status());
        assertEquals("INVALID_CAPACITY", badCapacity.code());

        ApiException negativeCapacity = assertThrows(ApiException.class, () -> service.createAirport(
                new AirportCreateRequest("bad2", -3, reqId("airport-req"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, negativeCapacity.status());

        createAirport("A", 1);
        ApiException duplicate = assertThrows(ApiException.class, () -> service.createAirport(
                new AirportCreateRequest("A", 2, reqId("airport-req"))));
        assertEquals(HttpStatus.CONFLICT, duplicate.status());

        ApiException badWindow = assertThrows(ApiException.class,
                () -> reserve("R", "A", 1, 200, 200));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, badWindow.status());
        assertEquals("INVALID_WINDOW", badWindow.code());
        ApiException invertedWindow = assertThrows(ApiException.class,
                () -> reserve("R", "A", 1, 300, 200));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, invertedWindow.status());

        ApiException unknownAirport = assertThrows(ApiException.class,
                () -> reserve("R", "NOPE", 1, 0, 100));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, unknownAirport.status());
        assertEquals("AIRPORT_NOT_FOUND", unknownAirport.code());

        // 全部拒绝不产生任何机场/预留
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM diversion_airport", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM diversion_reservation", Integer.class));
    }

    @Test
    @DisplayName("查询机场不存在或时段非法返回422")
    void invalidOccupancyQueryRejected() {
        ApiException noAirport = assertThrows(ApiException.class,
                () -> service.getOccupancy("NOPE", 0, 100));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, noAirport.status());
        createAirport("A", 1);
        ApiException badWindow = assertThrows(ApiException.class,
                () -> service.getOccupancy("A", 100, 100));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, badWindow.status());
    }

    @Test
    @DisplayName("409拒绝整体回滚：已起飞预留占满容量时高级申请被拒，不写入新占用、不改写任何预留")
    void conflictRollsBackWithoutPartialDisplacement() {
        // 容量 2：两个低级预留占满同窗 [100,200)，随后二者都已起飞（仍占容量、不可挤出）
        createAirport("A", 2);
        ReservationResult d1 = dataOf(reserve("D1", "A", 1, 100, 200));
        ReservationResult d2 = dataOf(reserve("D2", "A", 1, 100, 200));
        service.depart(new DepartureRequest(d1.reservationId(), reqId("depart-req-1")));
        service.depart(new DepartureRequest(d2.reservationId(), reqId("depart-req-2")));

        // 即使高优先级也不能挤出任何已起飞预留 → 409
        ApiException ex = assertThrows(ApiException.class,
                () -> reserve("H", "A", 9, 100, 200));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("WINDOW_CONFLICT", ex.code());
        // 两条已起飞预留原样保留，无替换关系
        assertEquals("DEPARTED", reservationRow(d1.reservationId()).get("status"));
        assertEquals("DEPARTED", reservationRow(d2.reservationId()).get("status"));
        assertNull(reservationRow(d1.reservationId()).get("replaced_by"));
        assertNull(reservationRow(d2.reservationId()).get("replaced_by"));
        // 没有写入任何新预留，失败不占用 requestId
        assertEquals(2,
                jdbc.queryForObject("SELECT COUNT(*) FROM diversion_reservation", Integer.class));
    }

    @Test
    @DisplayName("409同级拒绝不挤出任何预留：两个同级占满容量2，第三个同级必被拒且原占用不变")
    void samePriorityConflictDoesNotDisplaceOthers() {
        createAirport("A", 2);
        ReservationResult s1 = dataOf(reserve("S1", "A", 5, 0, 100));
        ReservationResult s2 = dataOf(reserve("S2", "A", 5, 0, 100));
        ApiException ex = assertThrows(ApiException.class,
                () -> reserve("S3", "A", 5, 0, 100));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("RESERVED", reservationRow(s1.reservationId()).get("status"));
        assertEquals("RESERVED", reservationRow(s2.reservationId()).get("status"));
        assertNull(reservationRow(s1.reservationId()).get("replaced_by"));
        assertNull(reservationRow(s2.reservationId()).get("replaced_by"));
        assertEquals(2,
                jdbc.queryForObject("SELECT COUNT(*) FROM diversion_reservation", Integer.class));
    }

    // ============================ 幂等 ============================

    @Test
    @DisplayName("同一requestId重放首次挤出清单，不再次执行；换机场或窗口返回409")
    void reservationIdempotentReplayAndParamMismatch() {
        createAirport("A", 1);
        ReservationResult l = dataOf(reserve("L", "A", 1, 100, 200));

        String requestId = reqId("res-req-fixed");
        ReservationRequest first = reservation(requestId, "H", "A", 9, 150, 250);
        MutationResponse r1 = service.reserve(first);
        ReservationResult h1 = dataOf(r1);
        assertEquals(1, h1.displaced().size());

        // 同键同参重放：replayed=true，返回首次预留与首次挤出清单
        MutationResponse r2 = service.reserve(reservation(requestId, "H", "A", 9, 150, 250));
        assertTrue(r2.replayed());
        ReservationResult h2 = dataOf(r2);
        assertEquals(h1.reservationId(), h2.reservationId());
        assertEquals(h1.displaced(), h2.displaced());
        // 不新建预留、不重复改写：L 仍只被替换一次
        assertEquals(2,
                jdbc.queryForObject("SELECT COUNT(*) FROM diversion_reservation", Integer.class));
        assertEquals(h1.reservationId(), reservationRow(l.reservationId()).get("replaced_by"));
        assertEquals(FIXED_NOW, ((Number) reservationRow(l.reservationId()).get("updated_at")).longValue());

        // 同键换窗口 → 409
        ApiException differentWindow = assertThrows(ApiException.class, () -> service.reserve(
                reservation(requestId, "H", "A", 9, 160, 260)));
        assertEquals(HttpStatus.CONFLICT, differentWindow.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", differentWindow.code());
        // 同键换机场 → 409
        createAirport("B", 1);
        ApiException differentAirport = assertThrows(ApiException.class, () -> service.reserve(
                reservation(requestId, "H", "B", 9, 150, 250)));
        assertEquals(HttpStatus.CONFLICT, differentAirport.status());
        // 同键用于不同类型操作（起飞）→ 409
        ApiException differentKind = assertThrows(ApiException.class, () -> service.depart(
                new DepartureRequest(h1.reservationId(), requestId)));
        assertEquals(HttpStatus.CONFLICT, differentKind.status());
    }

    @Test
    @DisplayName("同键并发申请：恰好一次生效并挤出，其余重放首次结果，不重复替换")
    void concurrentSameRequestIdPlaysBackOneOutcome() throws Exception {
        int n = 5;
        createAirport("A", 1);
        ReservationResult l = dataOf(reserve("L", "A", 1, 100, 200));

        String requestId = "con-same-key-high";
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.reserve(reservation(requestId, "H", "A", 9, 150, 250));
                }));
            }
            int firstCount = 0;
            int replayCount = 0;
            String newReservationId = null;
            for (Future<MutationResponse> f : futures) {
                MutationResponse resp = f.get(20, TimeUnit.SECONDS);
                ReservationResult result = dataOf(resp);
                if (resp.replayed()) {
                    replayCount++;
                } else {
                    firstCount++;
                    newReservationId = result.reservationId();
                }
                // 所有响应都携带首次的同一份挤出清单，replacedBy 指向同一个新预留
                assertEquals(List.of(l.reservationId()),
                        result.displaced().stream().map(d -> d.reservationId()).toList());
                assertEquals(result.reservationId(), result.displaced().get(0).replacedBy());
            }
            assertEquals(1, firstCount, "同键并发只能有一次申请真正生效");
            assertEquals(n - 1, replayCount);
            // 最终数据：只有一条新预留，L 只被替换一次
            assertEquals(2,
                    jdbc.queryForObject("SELECT COUNT(*) FROM diversion_reservation", Integer.class));
            assertEquals(1, countByStatus("RESERVED"));
            assertEquals(1, countByStatus("DISPLACED"));
            assertEquals(newReservationId, reservationRow(l.reservationId()).get("replaced_by"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("422失败不占用requestId；同键随后可成功")
    void failedRequestDoesNotConsumeIdempotencyKey() {
        createAirport("A", 1);
        String requestId = reqId("res-req-fail");
        ApiException failed = assertThrows(ApiException.class, () -> service.reserve(
                reservation(requestId, "R", "A", 1, 500, 400)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, failed.status());
        Integer dedupCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?", Integer.class, requestId);
        assertEquals(0, dedupCount);
        MutationResponse ok = service.reserve(reservation(requestId, "R", "A", 1, 0, 100));
        assertFalse(ok.replayed());
    }

    @Test
    @DisplayName("409容量冲突不占用requestId；窗口释放后同键申请可成功")
    void conflictDoesNotConsumeIdempotencyKey() {
        createAirport("A", 1);
        dataOf(reserve("L", "A", 5, 100, 200));
        String requestId = reqId("res-req-conflict");
        // 同级重叠 → 409
        ApiException conflict = assertThrows(ApiException.class, () -> service.reserve(
                reservation(requestId, "R", "A", 5, 100, 200)));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        // 不占键：换一个与现占用不冲突的窗口，同键可以成功
        MutationResponse later = service.reserve(reservation(requestId, "R", "A", 5, 200, 300));
        assertFalse(later.replayed());
        assertEquals("RESERVED", dataOf(later).status());
    }

    // ============================ 真实并发 ============================

    @Test
    @DisplayName("并发同级申请容量1：恰好一个成功，其余409，不超卖")
    void concurrentSamePriorityExactlyOneWins() throws Exception {
        int n = 6;
        createAirport("A", 1);
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.reserve(reservation(
                                "con-res-" + idx, "route-" + idx, "A", 5, 100, 200));
                    } catch (ApiException ex) {
                        return ex;
                    }
                }));
            }
            int accepted = 0;
            int rejected = 0;
            for (Future<Object> f : futures) {
                Object result = f.get(20, TimeUnit.SECONDS);
                if (result instanceof MutationResponse mr) {
                    accepted++;
                    assertEquals("RESERVED", dataOf(mr).status());
                } else {
                    ApiException ex = (ApiException) result;
                    rejected++;
                    assertEquals(HttpStatus.CONFLICT, ex.status());
                }
            }
            assertEquals(1, accepted, "容量1 的同窗并发只能接受 1 个");
            assertEquals(n - 1, rejected);
            // 最终数据核对：恰好 1 个占用，绝无超卖
            assertEquals(1, countByStatus("RESERVED"));
            assertEquals(0, countByStatus("DISPLACED"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发容量2：三个同级申请恰好两个成功")
    void concurrentCapacityTwoAcceptsExactlyTwo() throws Exception {
        int n = 3;
        createAirport("A", 2);
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.reserve(reservation(
                                "con2-res-" + idx, "route-" + idx, "A", 5, 0, 100));
                    } catch (ApiException ex) {
                        return ex;
                    }
                }));
            }
            int accepted = 0;
            for (Future<Object> f : futures) {
                if (f.get(20, TimeUnit.SECONDS) instanceof MutationResponse) {
                    accepted++;
                }
            }
            assertEquals(2, accepted);
            assertEquals(2, countByStatus("RESERVED"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发高级与低级申请：最终高级必定占用，低级要么被整体挤出要么409，无超卖")
    void concurrentHighAndLowEndsInConsistentState() throws Exception {
        int rounds = 15;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                cleanup();
                createAirport("A", 1);
                final String lowRequestId = "con-low-" + round;
                final String highRequestId = "con-high-" + round;
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Object> lowFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.reserve(reservation(
                                lowRequestId, "low-route", "A", 1, 100, 200));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> highFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.reserve(reservation(
                                highRequestId, "high-route", "A", 9, 100, 200));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object lowResult = lowFuture.get(20, TimeUnit.SECONDS);
                Object highResult = highFuture.get(20, TimeUnit.SECONDS);

                // 高优先级在任意提交顺序下都必须成功：
                // 低级先提交则被高级整体挤出；高级先提交则低级 409
                assertTrue(highResult instanceof MutationResponse,
                        "高优先级申请必须成功（容量未被不可挤预留占用）");
                String highId = dataOf((MutationResponse) highResult).reservationId();
                if (lowResult instanceof MutationResponse lowResp) {
                    String lowId = dataOf(lowResp).reservationId();
                    // 低级先成功则必被整体挤出并写入替换关系
                    assertEquals("DISPLACED", reservationRow(lowId).get("status"));
                    assertEquals(highId, reservationRow(lowId).get("replaced_by"));
                } else {
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) lowResult).status());
                }
                // 任意一轮都不得超卖：占容量记录恰好 1 条（高级 RESERVED）
                assertEquals(1, countByStatus("RESERVED"));
                OccupancyView view = service.getOccupancy("A", 0, 1000);
                long occupying = view.occupancy().stream()
                        .filter(e -> "RESERVED".equals(e.status())).count();
                assertEquals(1, occupying);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发起飞与挤出：结果二选一且均无超卖，不出现既起飞又被挤出")
    void concurrentDepartureAndDisplacementIsConsistent() throws Exception {
        int rounds = 15;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                cleanup();
                createAirport("A", 1);
                ReservationResult l = dataOf(reserve("L", "A", 1, 100, 200));
                final String departRequestId = "con-depart-" + round;
                final String highRequestId = "con-hd-" + round;

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Object> departFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.depart(new DepartureRequest(
                                l.reservationId(), departRequestId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> highFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.reserve(reservation(
                                highRequestId, "H", "A", 9, 100, 200));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object departResult = departFuture.get(20, TimeUnit.SECONDS);
                Object highResult = highFuture.get(20, TimeUnit.SECONDS);
                Map<String, Object> lRow = reservationRow(l.reservationId());

                if (departResult instanceof MutationResponse) {
                    // 起飞先生效：L 为 DEPARTED，高级申请必须 409
                    assertEquals("DEPARTED", lRow.get("status"));
                    assertNull(lRow.get("replaced_by"));
                    assertTrue(highResult instanceof ApiException
                            && ((ApiException) highResult).status() == HttpStatus.CONFLICT);
                } else {
                    // 挤出先生效：L 为 DISPLACED，起飞必须 409
                    assertEquals("DISPLACED", lRow.get("status"));
                    assertNotNull(lRow.get("replaced_by"));
                    assertTrue(departResult instanceof ApiException
                            && ((ApiException) departResult).status() == HttpStatus.CONFLICT);
                    assertTrue(highResult instanceof MutationResponse);
                }
                // 不允许同时成功造成超卖
                boolean bothSucceeded = departResult instanceof MutationResponse
                        && highResult instanceof MutationResponse;
                assertFalse(bothSucceeded, "起飞与挤出不得同时成功导致容量超卖");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
