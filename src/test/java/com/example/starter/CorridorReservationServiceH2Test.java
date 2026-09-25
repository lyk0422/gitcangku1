package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.CapacityExceededException;
import com.example.starter.api.dto.CorridorCapacityRequest;
import com.example.starter.api.dto.CorridorCreateRequest;
import com.example.starter.api.dto.CorridorResult;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyView;
import com.example.starter.api.dto.ProbeResult;
import com.example.starter.api.dto.ReservationCancelRequest;
import com.example.starter.api.dto.ReservationCreateRequest;
import com.example.starter.api.dto.ReservationHistoryDto;
import com.example.starter.api.dto.ReservationResultDto;
import com.example.starter.api.dto.ReservationWindowDto;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 走廊预约业务的 H2 数据库测试（MODE=MySQL）。
 * 覆盖容量校验、时段重叠判定（左闭右开）、审核前置依赖（CLEAR/STALE/相交）、
 * 容量调整、取消释放、只读查询、幂等边界与真实并发不超卖/不重复释放。
 */
@SpringBootTest
class CorridorReservationServiceH2Test {

    private static final long T = 1_800_000_000_000L;
    private static final long MIN = 60_000L;

    @Autowired
    private CorridorReservationService corridorService;
    @Autowired
    private AirspaceReviewService reviewService;
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

    private String corridor(String id, int capacity) {
        // 走廊矩形 [40,60] x [5,15]，测试航线 y=10 水平穿过
        corridorService.createCorridor(new CorridorCreateRequest(
                id, 40, 5, 60, 15, capacity, "req-" + rid("corridor")));
        return id;
    }

    /** 创建穿过走廊的航线并完成 CLEAR 审核，返回 reviewId。 */
    private String clearReviewForRoute(String routeId) {
        reviewService.createRoute(new RouteCreateRequest(
                routeId, pts(0, 10, 100, 10), "req-" + rid("route")));
        long airspaceVersion = jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id=1", Long.class);
        MutationResponse resp = reviewService.review(new ReviewRequest(
                routeId, 1, airspaceVersion, "req-" + rid("review")));
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class).reviewId();
    }

    private ReservationResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReservationResultDto.class);
    }

    private CorridorResult corridorDataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), CorridorResult.class);
    }

    private ReservationCreateRequest reservation(String key, String corridorId,
                                                 String reviewId, long start, long end) {
        return new ReservationCreateRequest(key, corridorId, start, end, reviewId,
                "req-" + rid("reservation"));
    }

    // ============================ 主流程与容量校验 ============================

    @Test
    void createReservationSucceedsAndAppearsInOccupancyWithHalfOpenWindow() {
        String cId = corridor("c1", 2);
        String reviewId = clearReviewForRoute("r1");
        MutationResponse resp = corridorService.createReservation(
                reservation("k1", cId, reviewId, T, T + 30 * MIN));
        assertFalse(resp.replayed());
        ReservationResultDto dto = dataOf(resp);
        assertEquals("ACTIVE", dto.status());
        assertEquals(reviewId, dto.reviewId());
        assertNull(dto.cancelledAt());

        // 起点（含）生效
        OccupancyView atStart = corridorService.occupancyAt(cId, T);
        assertEquals(1, atStart.count());
        assertEquals(2, atStart.capacity());
        // 中间时刻生效
        assertEquals(1, corridorService.occupancyAt(cId, T + 10 * MIN).count());
        // 终点（不含）不再生效：左闭右开
        assertEquals(0, corridorService.occupancyAt(cId, T + 30 * MIN).count());
        assertEquals(0, corridorService.occupancyAt(cId, T - 1).count());

        // 审核记录不被消费或改写：仍可按原 id 查询且结论不变
        assertEquals("CLEAR", reviewService.getReview(reviewId).conclusion());
    }

    @Test
    void overlappingReservationBeyondCapacityIs429WithCurrentOccupancy() {
        String cId = corridor("c2", 1);
        String reviewId = clearReviewForRoute("r2");
        corridorService.createReservation(
                reservation("ka", cId, reviewId, T, T + 30 * MIN));

        // 时间部分重叠且容量已满 → 429，携带当前占用数
        ApiException ex = assertThrows(ApiException.class, () -> corridorService.createReservation(
                reservation("kb", cId, reviewId, T + 10 * MIN, T + 40 * MIN)));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.status());
        assertEquals("CORRIDOR_CAPACITY_EXCEEDED", ex.code());
        CapacityExceededException cap = (CapacityExceededException) ex;
        assertEquals(1, cap.currentOccupancy());
        assertEquals(1, cap.capacity());

        // 首尾相接（前者终点 == 后者起点）按左闭右开不重叠，可以创建
        MutationResponse adjacent = corridorService.createReservation(
                reservation("kc", cId, reviewId, T + 30 * MIN, T + 60 * MIN));
        assertEquals("ACTIVE", dataOf(adjacent).status());
        // 相接时刻两者不叠加占用
        assertEquals(1, corridorService.occupancyAt(cId, T + 30 * MIN).count());
    }

    @Test
    void cancellingReleasesCapacityImmediatelyAndKeepsHistory() {
        String cId = corridor("c3", 1);
        String reviewId = clearReviewForRoute("r3");
        corridorService.createReservation(
                reservation("kx", cId, reviewId, T, T + 20 * MIN));
        // 满员
        assertThrows(ApiException.class, () -> corridorService.createReservation(
                reservation("ky", cId, reviewId, T + 5 * MIN, T + 25 * MIN)));

        // 取消后容量立即释放，同一时段可再预约
        MutationResponse cancel = corridorService.cancelReservation(
                new ReservationCancelRequest("kx", "req-" + rid("cancel")));
        assertEquals("CANCELLED", dataOf(cancel).status());

        MutationResponse retry = corridorService.createReservation(
                reservation("kz", cId, reviewId, T + 5 * MIN, T + 25 * MIN));
        assertEquals("ACTIVE", dataOf(retry).status());

        // 历史保留已取消记录与新生效记录
        ReservationHistoryDto history = corridorService.history(cId);
        assertEquals(2, history.count());
        List<String> statuses = history.reservations().stream()
                .map(ReservationResultDto::status).toList();
        assertTrue(statuses.contains("CANCELLED"));
        assertTrue(statuses.contains("ACTIVE"));
    }

    // ============================ 审核前置依赖 ============================

    @Test
    void missingReviewBlockedReviewAndNonIntersectingRouteAre422() {
        String cId = corridor("c4", 1);

        // 关联审核不存在 → 422
        ApiException noReview = assertThrows(ApiException.class, () ->
                corridorService.createReservation(
                        reservation("k-norev", cId, "rv_missing", T, T + 10 * MIN)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, noReview.status());
        assertEquals("REVIEW_REQUIRED", noReview.code());

        // 航线不与走廊相交（航线在远处），审核为 CLEAR → 422
        reviewService.createRoute(new RouteCreateRequest(
                "far", pts(-500, -500, -400, -400), "req-" + rid("route")));
        MutationResponse farReview = reviewService.review(new ReviewRequest(
                "far", 1, 0L, "req-" + rid("review")));
        String farReviewId = objectMapper.convertValue(
                farReview.data(), ReviewResultDto.class).reviewId();
        ApiException noIntersect = assertThrows(ApiException.class, () ->
                corridorService.createReservation(
                        reservation("k-nointer", cId, farReviewId, T, T + 10 * MIN)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, noIntersect.status());
        assertEquals("ROUTE_NOT_INTERSECT_CORRIDOR", noIntersect.code());

        // BLOCKED 审核（建一个与航线相交的禁飞区）不能用于预约 → 422
        reviewService.createZone(new ZoneCreateRequest(
                "z-block", 40, 5, 60, 15, "req-" + rid("zone")));
        reviewService.createRoute(new RouteCreateRequest(
                "blocked-route", pts(0, 10, 100, 10), "req-" + rid("route")));
        MutationResponse blockedReview = reviewService.review(new ReviewRequest(
                "blocked-route", 1, 1L, "req-" + rid("review")));
        String blockedReviewId = objectMapper.convertValue(
                blockedReview.data(), ReviewResultDto.class).reviewId();
        ApiException blocked = assertThrows(ApiException.class, () ->
                corridorService.createReservation(
                        reservation("k-blocked", cId, blockedReviewId, T, T + 10 * MIN)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, blocked.status());
        assertEquals("REVIEW_NOT_CLEAR", blocked.code());
    }

    @Test
    void staleReviewAfterRouteOrAirspaceChangeIs422AndRereviewFixesIt() {
        String cId = corridor("c5", 2);
        // 航线穿过走廊，CLEAR 审核
        reviewService.createRoute(new RouteCreateRequest(
                "sr", pts(0, 10, 100, 10), "req-" + rid("route")));
        MutationResponse rev = reviewService.review(new ReviewRequest(
                "sr", 1, 0L, "req-" + rid("review")));
        String reviewId = objectMapper.convertValue(rev.data(), ReviewResultDto.class).reviewId();

        // 空域变化（在远处建禁飞区，不影响几何相交判定）→ 审核 STALE → 422
        reviewService.createZone(new ZoneCreateRequest(
                "z-far", -500, -500, -400, -400, "req-" + rid("zone")));
        ApiException staleByZone = assertThrows(ApiException.class, () ->
                corridorService.createReservation(
                        reservation("k-stale1", cId, reviewId, T, T + 10 * MIN)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, staleByZone.status());
        assertEquals("REVIEW_STALE", staleByZone.code());

        // 重新审核（CLEAR）后预约成功
        MutationResponse rev2 = reviewService.review(new ReviewRequest(
                "sr", 1, 1L, "req-" + rid("review")));
        String reviewId2 = objectMapper.convertValue(rev2.data(), ReviewResultDto.class).reviewId();
        corridorService.createReservation(
                reservation("k-ok1", cId, reviewId2, T, T + 10 * MIN));

        // 航线替换后旧审核再次 STALE → 422
        reviewService.replaceRoute(new com.example.starter.api.dto.RouteReplaceRequest(
                "sr", 1, pts(0, 10, 100, 10), "req-" + rid("replace")));
        ApiException staleByRoute = assertThrows(ApiException.class, () ->
                corridorService.createReservation(
                        reservation("k-stale2", cId, reviewId2, T + 20 * MIN, T + 30 * MIN)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, staleByRoute.status());
        assertEquals("REVIEW_STALE", staleByRoute.code());
    }

    // ============================ 失败分支 ============================

    @Test
    void invalidWindowDurationAndMissingCorridorRejected() {
        String reviewId = clearReviewForRoute("r4");

        // start >= end → 400
        ApiException badOrder = assertThrows(ApiException.class, () ->
                corridorService.createReservation(reservation(
                        "k-order", "ghost", reviewId, T + 10 * MIN, T + 10 * MIN)));
        assertEquals(HttpStatus.BAD_REQUEST, badOrder.status());

        // 时长不足 1 分钟 → 400
        ApiException tooShort = assertThrows(ApiException.class, () ->
                corridorService.createReservation(reservation(
                        "k-short", "ghost", reviewId, T, T + MIN - 1)));
        assertEquals(HttpStatus.BAD_REQUEST, tooShort.status());
        assertEquals("INVALID_DURATION", tooShort.code());

        // 时长超过 120 分钟 → 400
        ApiException tooLong = assertThrows(ApiException.class, () ->
                corridorService.createReservation(reservation(
                        "k-long", "ghost", reviewId, T, T + 121 * MIN)));
        assertEquals(HttpStatus.BAD_REQUEST, tooLong.status());

        // 恰好 120 分钟合法，但走廊不存在 → 404
        ApiException noCorridor = assertThrows(ApiException.class, () ->
                corridorService.createReservation(reservation(
                        "k-noc", "ghost", reviewId, T, T + 120 * MIN)));
        assertEquals(HttpStatus.NOT_FOUND, noCorridor.status());
    }

    @Test
    void duplicateCorridorAndDegenerateRectangleRejected() {
        corridor("c-dup", 3);
        ApiException dup = assertThrows(ApiException.class, () ->
                corridorService.createCorridor(new CorridorCreateRequest(
                        "c-dup", 0, 0, 10, 10, 3, "req-" + rid("corridor"))));
        assertEquals(HttpStatus.CONFLICT, dup.status());

        ApiException badRect = assertThrows(ApiException.class, () ->
                corridorService.createCorridor(new CorridorCreateRequest(
                        "c-bad", 10, 0, 10, 10, 3, "req-" + rid("corridor"))));
        assertEquals(HttpStatus.BAD_REQUEST, badRect.status());
        assertEquals("INVALID_CORRIDOR_RECTANGLE", badRect.code());
    }

    @Test
    void cancelMissingIs404AndCancelTwiceIs409() {
        ApiException missing = assertThrows(ApiException.class, () ->
                corridorService.cancelReservation(new ReservationCancelRequest(
                        "ghost-key", "req-" + rid("cancel"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        String cId = corridor("c6", 1);
        String reviewId = clearReviewForRoute("r6");
        corridorService.createReservation(
                reservation("k-cancel", cId, reviewId, T, T + 10 * MIN));
        corridorService.cancelReservation(new ReservationCancelRequest(
                "k-cancel", "req-" + rid("cancel")));
        ApiException twice = assertThrows(ApiException.class, () ->
                corridorService.cancelReservation(new ReservationCancelRequest(
                        "k-cancel", "req-" + rid("cancel2"))));
        assertEquals(HttpStatus.CONFLICT, twice.status());
        assertEquals("RESERVATION_ALREADY_CANCELLED", twice.code());
    }

    // ============================ 容量调整 ============================

    @Test
    void capacityCanOnlyIncreaseAndTakesEffectImmediately() {
        String cId = corridor("c7", 1);
        String reviewId = clearReviewForRoute("r7");
        corridorService.createReservation(
                reservation("ka7", cId, reviewId, T, T + 30 * MIN));
        assertThrows(ApiException.class, () -> corridorService.createReservation(
                reservation("kb7", cId, reviewId, T + 5 * MIN, T + 35 * MIN)));

        // 下调（含相等）→ 409
        ApiException down = assertThrows(ApiException.class, () ->
                corridorService.adjustCapacity(new CorridorCapacityRequest(
                        cId, 1, "req-" + rid("cap"))));
        assertEquals(HttpStatus.CONFLICT, down.status());
        assertEquals("CAPACITY_ONLY_INCREASABLE", down.code());

        // 走廊不存在 → 404
        ApiException missing = assertThrows(ApiException.class, () ->
                corridorService.adjustCapacity(new CorridorCapacityRequest(
                        "ghost", 5, "req-" + rid("cap"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        // 上调立即生效：第二个重叠预约成功，既有预约不受影响
        MutationResponse adjusted = corridorService.adjustCapacity(
                new CorridorCapacityRequest(cId, 2, "req-" + rid("cap")));
        assertEquals(2, corridorDataOf(adjusted).capacity());
        MutationResponse second = corridorService.createReservation(
                reservation("kb7", cId, reviewId, T + 5 * MIN, T + 35 * MIN));
        assertEquals("ACTIVE", dataOf(second).status());
        assertEquals(2, corridorService.occupancyAt(cId, T + 10 * MIN).count());
    }

    // ============================ 只读查询与探测 ============================

    @Test
    void windowQueryAndProbeAreReadOnly() {
        String cId = corridor("c8", 2);
        String reviewId = clearReviewForRoute("r8");
        corridorService.createReservation(
                reservation("kw1", cId, reviewId, T, T + 30 * MIN));
        corridorService.createReservation(
                reservation("kw2", cId, reviewId, T + 40 * MIN, T + 70 * MIN));

        // 时段查询：窗与第一段重叠、与第二段不重叠
        ReservationWindowDto window = corridorService.activeWindow(
                cId, T - 20 * MIN, T + 20 * MIN);
        assertEquals(1, window.count());
        assertEquals("kw1", window.reservations().get(0).reservationKey());

        // 非法查询窗 → 400
        ApiException bad = assertThrows(ApiException.class, () ->
                corridorService.activeWindow(cId, T, T));
        assertEquals(HttpStatus.BAD_REQUEST, bad.status());

        // 探测：覆盖两段之间空档的整窗峰值为 1，容量 2 → 可预约
        ProbeResult probeOk = corridorService.probe(
                cId, T - 10 * MIN, T + 80 * MIN);
        assertEquals(1, probeOk.peakOccupancy());
        assertTrue(probeOk.available());

        // 探测只读：没有产生新预约
        assertEquals(2, corridorService.history(cId).count());

        // 容量降到场景：新走廊容量 1，同一窗内两段不互相重叠（峰值 1），
        // 但若窗只覆盖第一段同时再放一个同段预约则不可约——用重叠预约构造
        String cId2 = corridor("c9", 1);
        corridorService.createReservation(
                reservation("kp1", cId2, reviewId, T, T + 30 * MIN));
        ProbeResult probeFull = corridorService.probe(
                cId2, T + 10 * MIN, T + 20 * MIN);
        assertFalse(probeFull.available());
        assertEquals(1, probeFull.peakOccupancy());
        // 探测仍然只读
        assertEquals(1, corridorService.history(cId2).count());

        // 查询不存在的走廊 → 404
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () ->
                corridorService.occupancyAt("ghost", T)).status());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () ->
                corridorService.probe("ghost", T, T + 10 * MIN)).status());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () ->
                corridorService.history("ghost")).status());
    }

    // ============================ 幂等 ============================

    @Test
    void sameRequestReplaysDifferentParamsConflictsAndFailureDoesNotConsumeKey() {
        String cId = corridor("c10", 1);
        String reviewId = clearReviewForRoute("r10");
        String requestId = "fixed-reservation-1";
        ReservationCreateRequest req = new ReservationCreateRequest(
                "ki1", cId, T, T + 20 * MIN, reviewId, requestId);

        MutationResponse first = corridorService.createReservation(req);
        assertFalse(first.replayed());
        // 同键同参重放：返回首次结果，不重复插入
        MutationResponse replay = corridorService.createReservation(
                new ReservationCreateRequest("ki1", cId, T, T + 20 * MIN, reviewId, requestId));
        assertTrue(replay.replayed());
        assertEquals(dataOf(first).reservationKey(), dataOf(replay).reservationKey());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM corridor_reservation", Integer.class));

        // 同键异参 → 409
        ApiException diff = assertThrows(ApiException.class, () ->
                corridorService.createReservation(new ReservationCreateRequest(
                        "ki1", cId, T, T + 30 * MIN, reviewId, requestId)));
        assertEquals(HttpStatus.CONFLICT, diff.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", diff.code());

        // 失败（429）不占键：先让另一预约占满容量
        String failingKey = "fixed-failing-1";
        corridorService.createReservation(
                reservation("ki-busy", cId, reviewId, T + 40 * MIN, T + 60 * MIN));
        ApiException full = assertThrows(ApiException.class, () ->
                corridorService.createReservation(new ReservationCreateRequest(
                        "ki2", cId, T + 40 * MIN, T + 50 * MIN, reviewId, failingKey)));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, full.status());
        // 取消占位预约后，同一 requestId 可成功提交，说明失败未占键
        corridorService.cancelReservation(new ReservationCancelRequest(
                "ki-busy", "req-" + rid("cancel")));
        MutationResponse afterFail = corridorService.createReservation(new ReservationCreateRequest(
                "ki2", cId, T + 40 * MIN, T + 50 * MIN, reviewId, failingKey));
        assertFalse(afterFail.replayed());
    }

    @Test
    void reservationKeyIsGloballyUnique() {
        String c1 = corridor("c11", 5);
        String c2 = corridor("c12", 5);
        String reviewId = clearReviewForRoute("r11");
        corridorService.createReservation(
                reservation("shared-key", c1, reviewId, T, T + 10 * MIN));
        // 同一 reservationKey 用于另一走廊 → 409
        ApiException dup = assertThrows(ApiException.class, () ->
                corridorService.createReservation(
                        reservation("shared-key", c2, reviewId, T + 20 * MIN, T + 30 * MIN)));
        assertEquals(HttpStatus.CONFLICT, dup.status());
        assertEquals("RESERVATION_KEY_ALREADY_EXISTS", dup.code());
    }

    @Test
    void cancelIdempotencyReplaysAndDoesNotDoubleRelease() {
        String cId = corridor("c13", 1);
        String reviewId = clearReviewForRoute("r13");
        corridorService.createReservation(
                reservation("kc1", cId, reviewId, T, T + 20 * MIN));
        String cancelRequestId = "fixed-cancel-1";
        MutationResponse first = corridorService.cancelReservation(
                new ReservationCancelRequest("kc1", cancelRequestId));
        assertFalse(first.replayed());
        MutationResponse replay = corridorService.cancelReservation(
                new ReservationCancelRequest("kc1", cancelRequestId));
        assertTrue(replay.replayed());
        // 历史中仍只有一条该预约记录
        assertEquals(1, (int) corridorService.history(cId).reservations().stream()
                .filter(r -> r.reservationKey().equals("kc1")).count());
    }

    // ============================ 并发 ============================

    @Test
    void concurrentCreateAtCapacityNeverOversells() throws Exception {
        String cId = corridor("cc1", 1);
        String reviewId = clearReviewForRoute("rc1");
        int n = 8;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return corridorService.createReservation(reservation(
                                "cc1-k" + idx, cId, reviewId, T, T + 30 * MIN));
                    } catch (ApiException ex) {
                        return ex;
                    }
                }));
            }
            int success = 0;
            int rejected = 0;
            for (Future<Object> f : futures) {
                Object result = f.get(30, TimeUnit.SECONDS);
                if (result instanceof MutationResponse) {
                    success++;
                } else {
                    ApiException ex = (ApiException) result;
                    assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.status());
                    rejected++;
                }
            }
            assertEquals(1, success, "容量为 1 时只允许一个预约成功");
            assertEquals(n - 1, rejected);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM corridor_reservation WHERE status='ACTIVE'",
                    Integer.class));
            // 任意时刻占用都不超过容量
            assertEquals(1, corridorService.occupancyAt(cId, T + 10 * MIN).count());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentCancelAndCreateAreOrderedByCommitWithoutDoubleRelease() throws Exception {
        String cId = corridor("cc2", 1);
        String reviewId = clearReviewForRoute("rc2");

        int rounds = 10;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                final int r = round;
                String heldKey = "cc2-held-" + r;
                String newKey = "cc2-new-" + r;
                long base = T + r * 120 * MIN;
                corridorService.createReservation(
                        reservation(heldKey, cId, reviewId, base, base + 30 * MIN));
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Object> cancelFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return corridorService.cancelReservation(new ReservationCancelRequest(
                                heldKey, "req-cancel-" + r));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> createFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return corridorService.createReservation(reservation(
                                newKey, cId, reviewId, base, base + 30 * MIN));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Object cancelResult = cancelFuture.get(30, TimeUnit.SECONDS);
                Object createResult = createFuture.get(30, TimeUnit.SECONDS);
                // 取消必须恰好成功一次（不重复释放）
                assertTrue(cancelResult instanceof MutationResponse, "取消必须成功");
                // 创建要么在取消提交后成功（容量已释放），要么在取消提交前被 429 拒绝；
                // 无论裁决顺序如何，最终该窗 ACTIVE 数量只能是 0 或 1，绝不超卖
                if (createResult instanceof MutationResponse) {
                    assertEquals(1, corridorService.occupancyAt(cId, base + 10 * MIN).count());
                } else {
                    assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                            ((ApiException) createResult).status());
                    assertEquals(0, corridorService.occupancyAt(cId, base + 10 * MIN).count());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameRequestIdForReservationPlaysBackOneOutcome() throws Exception {
        String cId = corridor("cc3", 5);
        String reviewId = clearReviewForRoute("rc3");
        int n = 6;
        String requestId = "concurrent-reservation-key";
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return corridorService.createReservation(new ReservationCreateRequest(
                            "cc3-k", cId, T, T + 10 * MIN, reviewId, requestId));
                }));
            }
            int first = 0;
            int replay = 0;
            for (Future<MutationResponse> f : futures) {
                MutationResponse resp = f.get(30, TimeUnit.SECONDS);
                if (resp.replayed()) {
                    replay++;
                } else {
                    first++;
                }
            }
            assertEquals(1, first);
            assertEquals(n - 1, replay);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM corridor_reservation", Integer.class));
            // 同键异参事后仍 409
            ApiException ex = assertThrows(ApiException.class, () ->
                    corridorService.createReservation(new ReservationCreateRequest(
                            "cc3-k", cId, T, T + 11 * MIN, reviewId, requestId)));
            assertEquals(HttpStatus.CONFLICT, ex.status());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentCancelTwiceWithDifferentRequestIdsSucceedsOnce() throws Exception {
        String cId = corridor("cc4", 1);
        String reviewId = clearReviewForRoute("rc4");
        corridorService.createReservation(
                reservation("cc4-k", cId, reviewId, T, T + 10 * MIN));
        int n = 4;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return corridorService.cancelReservation(new ReservationCancelRequest(
                                "cc4-k", "req-cc4-cancel-" + idx));
                    } catch (ApiException ex) {
                        return ex;
                    }
                }));
            }
            int success = 0;
            int conflict = 0;
            for (Future<Object> f : futures) {
                Object result = f.get(30, TimeUnit.SECONDS);
                if (result instanceof MutationResponse) {
                    success++;
                } else {
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) result).status());
                    conflict++;
                }
            }
            assertEquals(1, success, "取消只能生效一次，不得重复释放");
            assertEquals(n - 1, conflict);
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT status, cancelled_at FROM corridor_reservation WHERE reservation_key='cc4-k'");
            assertEquals("CANCELLED", row.get("status"));
        } finally {
            pool.shutdownNow();
        }
    }
}
