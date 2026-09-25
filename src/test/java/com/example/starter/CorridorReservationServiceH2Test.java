package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.AvailabilityResult;
import com.example.starter.api.dto.CorridorCapacityAdjustRequest;
import com.example.starter.api.dto.CorridorCreateRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyResult;
import com.example.starter.api.dto.ReservationCancelRequest;
import com.example.starter.api.dto.ReservationCreateRequest;
import com.example.starter.api.dto.ReservationResult;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.service.AirspaceReviewService;
import com.example.starter.service.CorridorReservationService;
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
 * 走廊时段预约与容量占用核验的 H2 数据库测试（MODE=MySQL）。
 * 覆盖容量上限与 429、时段重叠判定（左闭右开）、审核前置依赖（404/422）、
 * 取消与历史保留、容量仅上调、幂等边界与真实并发不超卖/不重复释放。
 */
@SpringBootTest
class CorridorReservationServiceH2Test {

    @Autowired
    private CorridorReservationService corridorService;
    @Autowired
    private AirspaceReviewService airspaceService;
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
        jdbc.update("DELETE FROM corridor_reservation");
        jdbc.update("DELETE FROM corridor");
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

    /** 创建走廊（默认矩形 (0,0)-(100,100)）。 */
    private void newCorridor(String corridorId, int capacity) {
        corridorService.createCorridor(new CorridorCreateRequest(
                corridorId, 0, 0, 100, 100, capacity, "ck-" + rid("corridor")));
    }

    /** 创建穿过 (0,10)-(100,10) 的航线并提交 CLEAR 审核，返回 reviewId。 */
    private String newClearReview(String routeId) {
        airspaceService.createRoute(
                new RouteCreateRequest(routeId, pts(0, 10, 100, 10), "req-" + rid("route")));
        MutationResponse resp = airspaceService.review(
                new ReviewRequest(routeId, 1, 0L, "req-" + rid("review")));
        ReviewResultDto dto = objectMapper.convertValue(resp.data(), ReviewResultDto.class);
        assertEquals("CLEAR", dto.conclusion());
        return dto.reviewId();
    }

    private ReservationResult dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReservationResult.class);
    }

    private MutationResponse reserve(String key, String corridorId,
                                     String start, String end, String reviewId) {
        return corridorService.createReservation(
                new ReservationCreateRequest(key, corridorId, start, end, reviewId));
    }

    private int reservationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM corridor_reservation", Integer.class);
    }

    private int activeCount(String corridorId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM corridor_reservation "
                + "WHERE corridor_id = ? AND status = 'ACTIVE'", Integer.class, corridorId);
    }

    // ============================ 主流程与只读查询 ============================

    @Test
    void createReserveQueryOccupancyAndProbe() {
        newCorridor("c1", 2);
        String reviewId = newClearReview("r1");

        MutationResponse resp = reserve("rk-main-1", "c1",
                "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", reviewId);
        assertFalse(resp.replayed());
        ReservationResult created = dataOf(resp);
        assertEquals("ACTIVE", created.status());
        assertEquals("c1", created.corridorId());
        assertEquals(reviewId, created.reviewId());
        assertEquals("r1", created.routeId());
        assertEquals("2026-10-01T10:00:00Z", created.startTime());

        // 时段内时刻占用 1；左闭右开：结束时刻不占
        OccupancyResult inside = corridorService.getOccupancy("c1", "2026-10-01T10:30:00Z");
        assertEquals(1, inside.occupancy());
        assertEquals(2, inside.capacity());
        assertEquals(created.reservationId(), inside.reservations().get(0).reservationId());
        assertEquals(0, corridorService.getOccupancy("c1", "2026-10-01T11:00:00Z").occupancy());
        assertEquals(0, corridorService.getOccupancy("c1", "2026-10-01T09:59:59Z").occupancy());

        // 探测只读：容量未满返回可预约，且不建立预约
        AvailabilityResult probe = corridorService.probeAvailability(
                "c1", "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z");
        assertTrue(probe.available());
        assertEquals(1, probe.occupancy());
        assertEquals(1, reservationCount());

        // 预约详情与时段历史查询
        assertEquals("ACTIVE", corridorService.getReservation(created.reservationId()).status());
        List<ReservationResult> history = corridorService.listReservations(
                "c1", "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z");
        assertEquals(1, history.size());
        assertEquals(created.reservationId(), history.get(0).reservationId());
        // 不相交时段的历史查询为空
        assertTrue(corridorService.listReservations(
                "c1", "2026-10-02T00:00:00Z", "2026-10-03T00:00:00Z").isEmpty());

        // 预约不消费、不改写审核记录：审核仍只有一条且结论不变
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class));
        assertEquals("CLEAR", airspaceService.getReview(reviewId).conclusion());
    }

    // ============================ 容量上限与重叠判定 ============================

    @Test
    void capacityExceededReturns429WithOccupancyAndAdjacentSlotAllowed() {
        newCorridor("c1", 1);
        String reviewId = newClearReview("r1");
        reserve("rk-cap-1", "c1", "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", reviewId);

        // 重叠时段达到容量上限 → 429，并返回当前占用数
        ApiException ex = assertThrows(ApiException.class, () -> reserve("rk-cap-2", "c1",
                "2026-10-01T10:30:00Z", "2026-10-01T11:30:00Z", reviewId));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.status());
        assertEquals("CORRIDOR_CAPACITY_EXCEEDED", ex.code());
        assertNotNull(ex.details());
        assertEquals(1, ex.details().get("occupancy"));
        assertEquals(1, ex.details().get("capacity"));

        // 探测同样报告不可预约（只读）
        AvailabilityResult probe = corridorService.probeAvailability(
                "c1", "2026-10-01T10:30:00Z", "2026-10-01T11:30:00Z");
        assertFalse(probe.available());
        assertEquals(1, probe.occupancy());

        // 左闭右开：紧邻时段 [11:00,12:00) 不重叠，可预约
        MutationResponse adjacent = reserve("rk-cap-3", "c1",
                "2026-10-01T11:00:00Z", "2026-10-01T12:00:00Z", reviewId);
        assertFalse(adjacent.replayed());
        assertEquals(2, reservationCount());
        // 11:00 时刻只有新预约生效
        assertEquals(1, corridorService.getOccupancy("c1", "2026-10-01T11:00:00Z").occupancy());
    }

    @Test
    void capacityTwoAllowsTwoOverlappingButNotThird() {
        newCorridor("c1", 2);
        String reviewId = newClearReview("r1");
        reserve("rk-t2-1", "c1", "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", reviewId);
        reserve("rk-t2-2", "c1", "2026-10-01T10:30:00Z", "2026-10-01T11:30:00Z", reviewId);
        ApiException ex = assertThrows(ApiException.class, () -> reserve("rk-t2-3", "c1",
                "2026-10-01T10:45:00Z", "2026-10-01T11:45:00Z", reviewId));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.status());
        assertEquals(2, ex.details().get("occupancy"));
        assertEquals(2, activeCount("c1"));
    }

    // ============================ 时段与走廊参数校验 ============================

    @Test
    void invalidTimeRangeRejected() {
        newCorridor("c1", 1);
        String reviewId = newClearReview("r1");
        // 时长 0
        ApiException zero = assertThrows(ApiException.class, () -> reserve("rk-bad-1", "c1",
                "2026-10-01T10:00:00Z", "2026-10-01T10:00:00Z", reviewId));
        assertEquals(HttpStatus.BAD_REQUEST, zero.status());
        // 不足 1 分钟
        ApiException shortSlot = assertThrows(ApiException.class, () -> reserve("rk-bad-2", "c1",
                "2026-10-01T10:00:00Z", "2026-10-01T10:00:30Z", reviewId));
        assertEquals(HttpStatus.BAD_REQUEST, shortSlot.status());
        // 超过 120 分钟
        ApiException longSlot = assertThrows(ApiException.class, () -> reserve("rk-bad-3", "c1",
                "2026-10-01T10:00:00Z", "2026-10-01T12:01:00Z", reviewId));
        assertEquals(HttpStatus.BAD_REQUEST, longSlot.status());
        // 结束早于开始
        ApiException reversed = assertThrows(ApiException.class, () -> reserve("rk-bad-4", "c1",
                "2026-10-01T11:00:00Z", "2026-10-01T10:00:00Z", reviewId));
        assertEquals(HttpStatus.BAD_REQUEST, reversed.status());
        // 非法时刻格式
        ApiException malformed = assertThrows(ApiException.class, () -> reserve("rk-bad-5", "c1",
                "10:00", "2026-10-01T11:00:00Z", reviewId));
        assertEquals(HttpStatus.BAD_REQUEST, malformed.status());
        // 边界：恰好 1 分钟与 120 分钟合法
        reserve("rk-ok-1min", "c1", "2026-10-01T10:00:00Z", "2026-10-01T10:01:00Z", reviewId);
        reserve("rk-ok-120min", "c1", "2026-10-01T12:00:00Z", "2026-10-01T14:00:00Z", reviewId);
        assertEquals(2, reservationCount());
    }

    @Test
    void corridorValidationAndNotFound() {
        // 退化矩形 → 400
        ApiException degenerate = assertThrows(ApiException.class,
                () -> corridorService.createCorridor(new CorridorCreateRequest(
                        "bad-rect", 10, 10, 10, 20, 1, "ck-" + rid("corridor"))));
        assertEquals(HttpStatus.BAD_REQUEST, degenerate.status());
        // 容量越界 → 400（服务层兜底，不依赖 Web 校验）
        ApiException cap0 = assertThrows(ApiException.class,
                () -> corridorService.createCorridor(new CorridorCreateRequest(
                        "bad-cap0", 0, 0, 10, 10, 0, "ck-" + rid("corridor"))));
        assertEquals(HttpStatus.BAD_REQUEST, cap0.status());
        ApiException cap51 = assertThrows(ApiException.class,
                () -> corridorService.createCorridor(new CorridorCreateRequest(
                        "bad-cap51", 0, 0, 10, 10, 51, "ck-" + rid("corridor"))));
        assertEquals(HttpStatus.BAD_REQUEST, cap51.status());
        // 重复 corridorId → 409
        newCorridor("dup", 1);
        ApiException dup = assertThrows(ApiException.class,
                () -> corridorService.createCorridor(new CorridorCreateRequest(
                        "dup", 0, 0, 10, 10, 1, "ck-" + rid("corridor"))));
        assertEquals(HttpStatus.CONFLICT, dup.status());
        // 走廊不存在：预约 / 占用 / 探测 / 调容 → 404
        String reviewId = newClearReview("r1");
        ApiException reserve404 = assertThrows(ApiException.class, () -> reserve(
                "rk-ghost", "ghost", "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", reviewId));
        assertEquals(HttpStatus.NOT_FOUND, reserve404.status());
        assertThrows(ApiException.class,
                () -> corridorService.getOccupancy("ghost", "2026-10-01T10:00:00Z"));
        assertThrows(ApiException.class, () -> corridorService.probeAvailability(
                "ghost", "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z"));
        ApiException adjust404 = assertThrows(ApiException.class,
                () -> corridorService.adjustCapacity(
                        new CorridorCapacityAdjustRequest("ghost", 5, "ck-" + rid("corridor"))));
        assertEquals(HttpStatus.NOT_FOUND, adjust404.status());
    }

    // ============================ 审核前置依赖 ============================

    @Test
    void reviewPreconditionsMissingBlockedStaleAndNotIntersecting() {
        newCorridor("c1", 1);
        String reviewId = newClearReview("r1");

        // 审核不存在 → 404
        ApiException missing = assertThrows(ApiException.class, () -> reserve("rk-pre-1", "c1",
                "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", "rv_ghost"));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        // BLOCKED 审核 → 422
        airspaceService.createZone(new ZoneCreateRequest(
                "z-block", 40, 5, 60, 15, "req-" + rid("zone")));
        MutationResponse blockedResp = airspaceService.review(
                new ReviewRequest("r1", 1, 1L, "req-" + rid("review")));
        String blockedReviewId = objectMapper.convertValue(
                blockedResp.data(), ReviewResultDto.class).reviewId();
        ApiException blocked = assertThrows(ApiException.class, () -> reserve("rk-pre-2", "c1",
                "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", blockedReviewId));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, blocked.status());
        assertEquals("REVIEW_NOT_CLEAR", blocked.code());

        // CLEAR 审核变为 STALE（空域版本变化）→ 422，要求重新审核
        ApiException stale = assertThrows(ApiException.class, () -> reserve("rk-pre-3", "c1",
                "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", reviewId));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, stale.status());
        assertEquals("REVIEW_STALE", stale.code());

        // 航线替换导致 STALE → 422
        airspaceService.createRoute(
                new RouteCreateRequest("r2", pts(0, 10, 100, 10), "req-" + rid("route")));
        MutationResponse rev2 = airspaceService.review(
                new ReviewRequest("r2", 1, 1L, "req-" + rid("review")));
        String staleByRoute = objectMapper.convertValue(rev2.data(), ReviewResultDto.class).reviewId();
        airspaceService.replaceRoute(new RouteReplaceRequest(
                "r2", 1, pts(0, 20, 100, 20), "req-" + rid("replace")));
        ApiException stale2 = assertThrows(ApiException.class, () -> reserve("rk-pre-4", "c1",
                "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", staleByRoute));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, stale2.status());
        assertEquals("REVIEW_STALE", stale2.code());
    }

    @Test
    void routeNotIntersectingCorridorRejected422() {
        // 走廊在 (200,200)-(300,300)，航线在 y=10 不相交
        corridorService.createCorridor(new CorridorCreateRequest(
                "far", 200, 200, 300, 300, 1, "ck-" + rid("corridor")));
        String reviewId = newClearReview("r1");
        ApiException ex = assertThrows(ApiException.class, () -> reserve("rk-geo-1", "far",
                "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", reviewId));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("ROUTE_NOT_INTERSECT_CORRIDOR", ex.code());
        // 边界接触算相交：走廊底边 y=10 与航线接触 → 可预约
        corridorService.createCorridor(new CorridorCreateRequest(
                "touch", 40, 10, 60, 30, 1, "ck-" + rid("corridor")));
        MutationResponse ok = reserve("rk-geo-2", "touch",
                "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", reviewId);
        assertFalse(ok.replayed());
    }

    // ============================ 取消与历史 ============================

    @Test
    void cancelRemovesFromOccupancyKeepsHistoryAndRejectsDoubleCancel() {
        newCorridor("c1", 1);
        String reviewId = newClearReview("r1");
        MutationResponse created = reserve("rk-cancel-1", "c1",
                "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", reviewId);
        String reservationId = dataOf(created).reservationId();
        assertEquals(1, corridorService.getOccupancy("c1", "2026-10-01T10:30:00Z").occupancy());

        MutationResponse cancelled = corridorService.cancelReservation(
                new ReservationCancelRequest(reservationId, "req-" + rid("cancel")));
        assertEquals("CANCELLED", dataOf(cancelled).status());
        // 取消后立即从容量计数中移除
        assertEquals(0, corridorService.getOccupancy("c1", "2026-10-01T10:30:00Z").occupancy());
        assertTrue(corridorService.probeAvailability(
                "c1", "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z").available());
        // 历史保留：详情与时段查询仍可见，状态 CANCELLED
        assertEquals("CANCELLED", corridorService.getReservation(reservationId).status());
        List<ReservationResult> history = corridorService.listReservations(
                "c1", "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z");
        assertEquals(1, history.size());
        assertEquals("CANCELLED", history.get(0).status());
        // 容量已释放：同时段可再预约
        reserve("rk-cancel-2", "c1", "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", reviewId);
        assertEquals(1, activeCount("c1"));

        // 重复取消 → 409；取消不存在 → 404
        ApiException twice = assertThrows(ApiException.class,
                () -> corridorService.cancelReservation(
                        new ReservationCancelRequest(reservationId, "req-" + rid("cancel"))));
        assertEquals(HttpStatus.CONFLICT, twice.status());
        ApiException missing = assertThrows(ApiException.class,
                () -> corridorService.cancelReservation(
                        new ReservationCancelRequest("rsv_ghost", "req-" + rid("cancel"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    // ============================ 容量调整 ============================

    @Test
    void capacityAdjustOnlyIncreaseAndEffectiveImmediately() {
        newCorridor("c1", 1);
        String reviewId = newClearReview("r1");
        reserve("rk-adj-1", "c1", "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", reviewId);
        // 已满：重叠预约 429
        assertThrows(ApiException.class, () -> reserve("rk-adj-2", "c1",
                "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", reviewId));

        // 下调与持平 → 409
        ApiException same = assertThrows(ApiException.class,
                () -> corridorService.adjustCapacity(
                        new CorridorCapacityAdjustRequest("c1", 1, "ck-" + rid("corridor"))));
        assertEquals(HttpStatus.CONFLICT, same.status());
        // 先上调到 3，再下调 → 409
        corridorService.adjustCapacity(
                new CorridorCapacityAdjustRequest("c1", 3, "ck-" + rid("corridor")));
        ApiException down = assertThrows(ApiException.class,
                () -> corridorService.adjustCapacity(
                        new CorridorCapacityAdjustRequest("c1", 2, "ck-" + rid("corridor"))));
        assertEquals(HttpStatus.CONFLICT, down.status());

        // 上调立即生效：同时段可再预约；既有预约不受影响
        reserve("rk-adj-3", "c1", "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", reviewId);
        OccupancyResult occupancy = corridorService.getOccupancy("c1", "2026-10-01T10:30:00Z");
        assertEquals(2, occupancy.occupancy());
        assertEquals(3, occupancy.capacity());
        assertEquals(2, activeCount("c1"));
    }

    // ============================ 幂等 ============================

    @Test
    void reservationKeyReplayConflictAndFailureNotConsumed() {
        newCorridor("c1", 1);
        String reviewId = newClearReview("r1");
        ReservationCreateRequest req = new ReservationCreateRequest("rk-idem-1", "c1",
                "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", reviewId);
        MutationResponse first = corridorService.createReservation(req);
        assertFalse(first.replayed());

        // 同键同参重放：返回首次结果，不产生新预约
        MutationResponse replay = corridorService.createReservation(req);
        assertTrue(replay.replayed());
        assertEquals(dataOf(first).reservationId(), dataOf(replay).reservationId());
        assertEquals(1, reservationCount());

        // 同键异参 → 409
        ApiException mismatch = assertThrows(ApiException.class,
                () -> corridorService.createReservation(new ReservationCreateRequest("rk-idem-1",
                        "c1", "2026-10-01T12:00:00Z", "2026-10-01T13:00:00Z", reviewId)));
        assertEquals(HttpStatus.CONFLICT, mismatch.status());

        // 失败不占键：先以不相交走廊触发 422，再同键成功
        corridorService.createCorridor(new CorridorCreateRequest(
                "far", 200, 200, 300, 300, 1, "ck-" + rid("corridor")));
        ApiException failed = assertThrows(ApiException.class, () -> reserve("rk-idem-2", "far",
                "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", reviewId));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, failed.status());
        assertNull(jdbc.queryForList(
                "SELECT request_id FROM request_dedup WHERE request_id = 'rk-idem-2'")
                .stream().findFirst().orElse(null));
        MutationResponse ok = reserve("rk-idem-2", "c1",
                "2026-10-01T12:00:00Z", "2026-10-01T13:00:00Z", reviewId);
        assertFalse(ok.replayed());

        // 走廊写操作 corridorKey 同样幂等：同键同参重放，异参 409
        CorridorCreateRequest corridorReq = new CorridorCreateRequest(
                "c-idem", 0, 0, 10, 10, 1, "ck-idem-1");
        corridorService.createCorridor(corridorReq);
        assertTrue(corridorService.createCorridor(corridorReq).replayed());
        ApiException corridorMismatch = assertThrows(ApiException.class,
                () -> corridorService.createCorridor(new CorridorCreateRequest(
                        "c-idem", 0, 0, 20, 20, 1, "ck-idem-1")));
        assertEquals(HttpStatus.CONFLICT, corridorMismatch.status());
    }

    // ============================ 并发 ============================

    @Test
    void concurrentReservationsNeverOversell() throws Exception {
        newCorridor("c1", 1);
        String reviewId = newClearReview("r1");
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Object>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                String key = "rk-race-" + i;
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return reserve(key, "c1", "2026-10-01T10:00:00Z",
                                "2026-10-01T11:00:00Z", reviewId);
                    } catch (ApiException ex) {
                        return ex;
                    }
                }));
            }
            int success = 0;
            int rejected = 0;
            for (Future<Object> f : futures) {
                Object result = f.get(15, TimeUnit.SECONDS);
                if (result instanceof MutationResponse) {
                    success++;
                } else {
                    ApiException ex = (ApiException) result;
                    assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.status());
                    assertEquals(1, ex.details().get("occupancy"));
                    rejected++;
                }
            }
            // 容量 1：恰好一个成功，其余 429，绝不超卖
            assertEquals(1, success);
            assertEquals(n - 1, rejected);
            assertEquals(1, activeCount("c1"));
            assertEquals(1, corridorService.getOccupancy("c1", "2026-10-01T10:30:00Z").occupancy());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameReservationKeyPlaysBackOneOutcome() throws Exception {
        newCorridor("c1", 2);
        String reviewId = newClearReview("r1");
        int n = 5;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return reserve("rk-same-key", "c1", "2026-10-01T10:00:00Z",
                            "2026-10-01T11:00:00Z", reviewId);
                }));
            }
            int firstCount = 0;
            int replayCount = 0;
            String reservationId = null;
            for (Future<MutationResponse> f : futures) {
                MutationResponse resp = f.get(15, TimeUnit.SECONDS);
                if (resp.replayed()) {
                    replayCount++;
                } else {
                    firstCount++;
                }
                String id = dataOf(resp).reservationId();
                if (reservationId == null) {
                    reservationId = id;
                }
                assertEquals(reservationId, id);
            }
            assertEquals(1, firstCount);
            assertEquals(n - 1, replayCount);
            assertEquals(1, reservationCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentCreateAndCancelNeverOversellNorDoubleRelease() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 8; round++) {
                final int roundNo = round;
                String corridorId = "cc-" + roundNo;
                newCorridor(corridorId, 1);
                String reviewId = newClearReview("rc-" + roundNo);
                MutationResponse created = reserve("rk-round-a-" + roundNo, corridorId,
                        "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", reviewId);
                String reservationA = dataOf(created).reservationId();

                CyclicBarrier barrier = new CyclicBarrier(3);
                // 取消方：取消 A
                Future<Object> cancelFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return corridorService.cancelReservation(new ReservationCancelRequest(
                                reservationA, "req-cancel-" + corridorId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                // 创建方：同时段新预约 B
                Future<Object> createFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return reserve("rk-round-b-" + roundNo, corridorId,
                                "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", reviewId);
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                // 重复取消方：与取消方并发取消同一预约（不同 requestId）
                Future<Object> cancelTwiceFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return corridorService.cancelReservation(new ReservationCancelRequest(
                                reservationA, "req-cancel2-" + corridorId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object cancelResult = cancelFuture.get(15, TimeUnit.SECONDS);
                Object createResult = createFuture.get(15, TimeUnit.SECONDS);
                Object cancelTwiceResult = cancelTwiceFuture.get(15, TimeUnit.SECONDS);

                // 两个并发取消按提交顺序裁决：恰好一个成功，另一个 409，不重复释放
                int cancelSuccess = (cancelResult instanceof MutationResponse ? 1 : 0)
                        + (cancelTwiceResult instanceof MutationResponse ? 1 : 0);
                assertEquals(1, cancelSuccess, "同一预约并发取消只能成功一次");
                Object cancelLoser = cancelResult instanceof MutationResponse
                        ? cancelTwiceResult : cancelResult;
                assertEquals(HttpStatus.CONFLICT, ((ApiException) cancelLoser).status());

                // 创建方：取消先提交则成功，否则 429；无论哪种结果最终占用不超过容量
                if (createResult instanceof ApiException createEx) {
                    assertEquals(HttpStatus.TOO_MANY_REQUESTS, createEx.status());
                }
                assertEquals("CANCELLED",
                        corridorService.getReservation(reservationA).status());
                int active = activeCount(corridorId);
                assertTrue(active <= 1, "容量 1 时生效预约不得超过 1，实际 " + active);
                assertEquals(createResult instanceof MutationResponse ? 1 : 0, active);
                assertEquals(active, corridorService.getOccupancy(
                        corridorId, "2026-10-01T10:30:00Z").occupancy());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
