package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.BandConfigDto;
import com.example.starter.api.dto.BandConfigureRequest;
import com.example.starter.api.dto.BandConfigureResult;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupationCancelRequest;
import com.example.starter.api.dto.OccupationCreateRequest;
import com.example.starter.api.dto.OccupationResultDto;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.VerticalZoneDto;
import com.example.starter.api.dto.ZoneBandsView;
import com.example.starter.api.dto.ZoneCreateRequest;
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
 * 高度带配置、垂直分离审查与高度层占用的 H2 数据库测试（MODE=MySQL）。
 * 覆盖高度层重叠判断（左闭右开）、容量校验（429）、版本失效（STALE→422）、
 * 取消释放容量、配置乐观锁（409）与并发不超卖、幂等重放边界。
 */
@SpringBootTest
class AltitudeBandOccupationH2Test {

    private static final long T0 = 1_700_000_000_000L;
    private static final long T1 = T0 + 3_600_000L;
    private static final long T2 = T1 + 3_600_000L;

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
        jdbc.update("DELETE FROM altitude_occupation");
        jdbc.update("DELETE FROM altitude_band");
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

    private void zone(String zoneId, int xMin, int yMin, int xMax, int yMax) {
        service.createZone(new ZoneCreateRequest(zoneId, xMin, yMin, xMax, yMax,
                "req-" + rid("zone")));
    }

    private BandConfigureResult bands(String zoneId, int expectedVersion, BandConfigDto... bands) {
        MutationResponse resp = service.configureBands(new BandConfigureRequest(
                zoneId, expectedVersion, List.of(bands), "req-" + rid("band")));
        assertFalse(resp.replayed());
        return objectMapper.convertValue(resp.data(), BandConfigureResult.class);
    }

    /** 创建穿越 (0,10)-(100,10) 的航线，巡航高度与时间窗可指定。 */
    private void route(String routeId, int cruise, long startUtc, long endUtc) {
        service.createRoute(new RouteCreateRequest(routeId, pts(0, 10, 100, 10),
                cruise, startUtc, endUtc, "req-" + rid("route")));
    }

    private ReviewResultDto review(String routeId, int routeVersion, long airspaceVersion) {
        MutationResponse resp = service.review(
                new ReviewRequest(routeId, routeVersion, airspaceVersion, "req-" + rid("review")));
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    private OccupationResultDto occupy(String reviewId, String zoneId, String bandId) {
        MutationResponse resp = service.createOccupation(
                new OccupationCreateRequest(reviewId, zoneId, bandId, "req-" + rid("occ")));
        assertFalse(resp.replayed());
        return objectMapper.convertValue(resp.data(), OccupationResultDto.class);
    }

    private OccupationResultDto occData(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), OccupationResultDto.class);
    }

    private int activeCount(String bandId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM altitude_occupation WHERE band_id = ? AND status = 'ACTIVE'",
                Integer.class, bandId);
        return count == null ? 0 : count;
    }

    /** 登记管理空域 za（40,5,60,15）：b-low [0,500) 容量 3，b-high [500,1000) 容量 2。 */
    private void setupManagedZone() {
        zone("za", 40, 5, 60, 15);
        bands("za", 1,
                new BandConfigDto("b-low", 0, 500, 3),
                new BandConfigDto("b-high", 500, 1000, 2));
    }

    // ============================ 高度带配置 ============================

    @Test
    void bandConfigureHappyPathRaiseCapacityAndQuery() {
        zone("za", 40, 5, 60, 15);
        // 端点相接的两个带合法（左闭右开，[0,500) 与 [500,1000) 不重叠）
        BandConfigureResult result = bands("za", 1,
                new BandConfigDto("b-low", 0, 500, 3),
                new BandConfigDto("b-high", 500, 1000, 2));
        assertEquals(2, result.zoneVersion());
        assertEquals(2L, result.airspaceVersion());
        assertEquals(2, result.bands().size());

        ZoneBandsView view = service.getZoneBands("za");
        assertEquals("ACTIVE", view.status());
        assertEquals(2, view.zoneVersion());
        assertEquals(2, view.bands().size());
        // 按下限升序：b-low [0,500) 在前，b-high [500,1000) 在后
        assertEquals("b-low", view.bands().get(0).bandId());
        assertEquals("b-high", view.bands().get(1).bandId());

        // 只允许上调容量：b-low 3 → 10 合法
        BandConfigureResult raised = bands("za", 2,
                new BandConfigDto("b-low", 0, 500, 10),
                new BandConfigDto("b-high", 500, 1000, 2));
        assertEquals(3, raised.zoneVersion());
        assertEquals(3L, raised.airspaceVersion());
        assertEquals(10, service.getZoneBands("za").bands().get(0).capacity());

        // 过期 expectedVersion → 409
        ApiException stale = assertThrows(ApiException.class, () -> bands("za", 2,
                new BandConfigDto("b-low", 0, 500, 12),
                new BandConfigDto("b-high", 500, 1000, 2)));
        assertEquals(HttpStatus.CONFLICT, stale.status());
        // 区域版本未被失败配置推进
        assertEquals(3, service.getZoneBands("za").zoneVersion());
    }

    @Test
    void bandConfigureRejectsInvalidConfigs() {
        zone("za", 40, 5, 60, 15);
        bands("za", 1, new BandConfigDto("b1", 0, 500, 3));

        // 请求内两带重叠 → 400
        ApiException overlapInRequest = assertThrows(ApiException.class, () -> bands("za", 2,
                new BandConfigDto("b1", 0, 500, 3),
                new BandConfigDto("b2", 400, 600, 2)));
        assertEquals(HttpStatus.BAD_REQUEST, overlapInRequest.status());
        assertEquals("BAND_OVERLAP", overlapInRequest.code());

        // 新增带与已有带重叠 → 400
        ApiException overlapExisting = assertThrows(ApiException.class, () -> bands("za", 2,
                new BandConfigDto("b1", 0, 500, 3),
                new BandConfigDto("b2", 499, 700, 2)));
        assertEquals(HttpStatus.BAD_REQUEST, overlapExisting.status());

        // 端点相接合法：[500,800) 与 [0,500) 相接不重叠
        BandConfigureResult touching = bands("za", 2,
                new BandConfigDto("b1", 0, 500, 3),
                new BandConfigDto("b2", 500, 800, 2));
        assertEquals(3, touching.zoneVersion());

        // 下限不小于上限 → 400
        ApiException badRange = assertThrows(ApiException.class, () -> bands("za", 3,
                new BandConfigDto("b1", 0, 500, 3),
                new BandConfigDto("b2", 500, 800, 2),
                new BandConfigDto("b3", 900, 900, 2)));
        assertEquals(HttpStatus.BAD_REQUEST, badRange.status());
        assertEquals("INVALID_BAND_RANGE", badRange.code());

        // 容量越界 → 400
        ApiException badCapacity = assertThrows(ApiException.class, () -> bands("za", 3,
                new BandConfigDto("b1", 0, 500, 3),
                new BandConfigDto("b2", 500, 800, 2),
                new BandConfigDto("b3", 900, 1000, 51)));
        assertEquals(HttpStatus.BAD_REQUEST, badCapacity.status());
        assertEquals("INVALID_BAND_CAPACITY", badCapacity.code());

        // 下调容量 → 400
        ApiException lowerCapacity = assertThrows(ApiException.class, () -> bands("za", 3,
                new BandConfigDto("b1", 0, 500, 2),
                new BandConfigDto("b2", 500, 800, 2)));
        assertEquals(HttpStatus.BAD_REQUEST, lowerCapacity.status());
        assertEquals("BAND_CAPACITY_NOT_RAISABLE", lowerCapacity.code());

        // 删除已有带 → 400
        ApiException removal = assertThrows(ApiException.class, () -> bands("za", 3,
                new BandConfigDto("b1", 0, 500, 3)));
        assertEquals(HttpStatus.BAD_REQUEST, removal.status());
        assertEquals("BAND_REMOVAL_NOT_ALLOWED", removal.code());

        // 修改已有带边界 → 400
        ApiException rangeChange = assertThrows(ApiException.class, () -> bands("za", 3,
                new BandConfigDto("b1", 0, 600, 3),
                new BandConfigDto("b2", 500, 800, 2)));
        assertEquals(HttpStatus.BAD_REQUEST, rangeChange.status());
        assertEquals("BAND_RANGE_IMMUTABLE", rangeChange.code());

        // 全部失败均不推进区域版本
        assertEquals(3, service.getZoneBands("za").zoneVersion());
        // 区域不存在 → 404；已撤销区域 → 409
        ApiException missing = assertThrows(ApiException.class, () -> bands("ghost", 1,
                new BandConfigDto("b1", 0, 500, 3)));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
        zone("zr", 0, 0, 10, 10);
        service.revokeZone(new com.example.starter.api.dto.ZoneRevokeRequest(
                "zr", "req-" + rid("revoke")));
        ApiException revoked = assertThrows(ApiException.class, () -> bands("zr", 1,
                new BandConfigDto("b1", 0, 500, 3)));
        assertEquals(HttpStatus.CONFLICT, revoked.status());
    }

    @Test
    void bandConfigureIsIdempotentAndBumpsAirspaceVersion() {
        // 区域远离航线，审查结论为 CLEAR
        zone("za", -50, -50, -40, -40);
        route("ra", 900, T0, T1);
        ReviewResultDto review = review("ra", 1, 1L);
        assertEquals("CLEAR", review.conclusion());

        String requestId = "band-idem-1";
        BandConfigureRequest request = new BandConfigureRequest("za", 1,
                List.of(new BandConfigDto("b1", 0, 1000, 5)), requestId);
        MutationResponse first = service.configureBands(request);
        assertFalse(first.replayed());
        // 高度带配置推进全局空域版本，关联审查随之 STALE
        assertEquals(2L, jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id = 1", Long.class));
        assertEquals("STALE", service.getCurrentReview("ra").conclusion());

        // 同键同参重放：不再次推进版本
        MutationResponse replay = service.configureBands(
                new BandConfigureRequest("za", 1, List.of(new BandConfigDto("b1", 0, 1000, 5)),
                        requestId));
        assertTrue(replay.replayed());
        assertEquals(2L, jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id = 1", Long.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM altitude_band", Integer.class));
        // 同键异参 → 409
        ApiException mismatch = assertThrows(ApiException.class, () -> service.configureBands(
                new BandConfigureRequest("za", 1, List.of(new BandConfigDto("b1", 0, 900, 5)),
                        requestId)));
        assertEquals(HttpStatus.CONFLICT, mismatch.status());
    }

    // ============================ 垂直分离审查 ============================

    @Test
    void managedZoneNeverBlocksAndDetailShowsPerBandOverlap() {
        setupManagedZone();
        // 巡航 900 进入 b-high [500,1000)：管理空域不拦截，结论 CLEAR，明细逐带标注
        route("ra", 900, T0, T1);
        ReviewResultDto review = review("ra", 1, 2L);
        assertEquals("CLEAR", review.conclusion());
        assertTrue(review.hitZoneIds().isEmpty());
        assertEquals(1, review.verticalSeparation().size());
        VerticalZoneDto zoneDetail = review.verticalSeparation().get(0);
        assertEquals("za", zoneDetail.zoneId());
        assertFalse(zoneDetail.blocked());
        assertEquals(2, zoneDetail.bands().size());
        assertEquals("b-low", zoneDetail.bands().get(0).bandId());
        assertFalse(zoneDetail.bands().get(0).altitudeOverlap());
        assertEquals("b-high", zoneDetail.bands().get(1).bandId());
        assertTrue(zoneDetail.bands().get(1).altitudeOverlap());
        assertEquals(2, zoneDetail.bands().get(1).capacity());

        // 明细查询端点返回同一不可变快照
        List<VerticalZoneDto> detail = service.getVerticalSeparation(review.reviewId());
        assertEquals(1, detail.size());
        assertEquals("za", detail.get(0).zoneId());
        assertTrue(detail.get(0).bands().get(1).altitudeOverlap());
        ApiException missing = assertThrows(ApiException.class,
                () -> service.getVerticalSeparation("rv_ghost"));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    @Test
    void altitudeBandIsLeftClosedRightOpen() {
        setupManagedZone();
        // 巡航 500：进入 [500,1000)（左闭），不进入 [0,500)（右开）
        route("r-low-edge", 500, T0, T1);
        ReviewResultDto atLower = review("r-low-edge", 1, 2L);
        VerticalZoneDto detail = atLower.verticalSeparation().get(0);
        assertFalse(detail.bands().get(0).altitudeOverlap(), "500 不进入 [0,500)");
        assertTrue(detail.bands().get(1).altitudeOverlap(), "500 进入 [500,1000)");

        // 巡航 1000：恰为 b-high 上限（右开），两个带都不进入
        route("r-upper-edge", 1000, T0, T1);
        ReviewResultDto atUpper = review("r-upper-edge", 1, 2L);
        VerticalZoneDto upperDetail = atUpper.verticalSeparation().get(0);
        assertFalse(upperDetail.bands().get(0).altitudeOverlap());
        assertFalse(upperDetail.bands().get(1).altitudeOverlap(), "1000 不进入 [500,1000)");
        assertEquals("CLEAR", atUpper.conclusion());

        // 巡航 499：只进入 b-low
        route("r-mid", 499, T0, T1);
        ReviewResultDto mid = review("r-mid", 1, 2L);
        assertTrue(mid.verticalSeparation().get(0).bands().get(0).altitudeOverlap());
        assertFalse(mid.verticalSeparation().get(0).bands().get(1).altitudeOverlap());
    }

    // ============================ 占用：容量与 429 ============================

    @Test
    void occupationConsumesCapacityAndRejectsOverflowWith429() {
        setupManagedZone();
        // b-high 容量 2：两条航线占用成功，第三条 429 且给出区域、高度带与占用数
        route("r1", 900, T0, T1);
        route("r2", 900, T0, T1);
        route("r3", 900, T0, T1);
        ReviewResultDto rev1 = review("r1", 1, 2L);
        ReviewResultDto rev2 = review("r2", 1, 2L);
        ReviewResultDto rev3 = review("r3", 1, 2L);

        OccupationResultDto occ1 = occupy(rev1.reviewId(), "za", "b-high");
        assertEquals("ACTIVE", occ1.status());
        assertEquals("r1", occ1.routeId());
        assertEquals(rev1.reviewId(), occ1.reviewId());
        assertEquals(T0, occ1.startUtc());
        assertEquals(T1, occ1.endUtc());
        assertEquals(900, occ1.cruiseAltitude());
        assertNull(occ1.cancelledAt());
        occupy(rev2.reviewId(), "za", "b-high");
        assertEquals(2, activeCount("b-high"));

        ApiException full = assertThrows(ApiException.class,
                () -> occupy(rev3.reviewId(), "za", "b-high"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, full.status());
        assertEquals("CAPACITY_EXCEEDED", full.code());
        assertNotNull(full.details());
        assertEquals("za", full.details().get("zoneId"));
        assertEquals("b-high", full.details().get("bandId"));
        assertEquals(2, full.details().get("activeCount"));
        assertEquals(2, full.details().get("capacity"));
        // 429 未写入任何占用
        assertEquals(2, activeCount("b-high"));

        // 容量按高度带隔离：b-low 容量不受 b-high 占满影响
        route("r4", 300, T0, T1);
        ReviewResultDto rev4 = review("r4", 1, 2L);
        occupy(rev4.reviewId(), "za", "b-low");
        assertEquals(1, activeCount("b-low"));
    }

    @Test
    void occupationCapacityCountsOnlyOverlappingWindows() {
        setupManagedZone();
        // b-high 容量 2：窗口相接（T1 端点）不算重叠
        route("r1", 900, T0, T1);
        route("r2", 900, T1, T2);
        ReviewResultDto rev1 = review("r1", 1, 2L);
        ReviewResultDto rev2 = review("r2", 1, 2L);
        occupy(rev1.reviewId(), "za", "b-high");
        // 端点相接：[T0,T1) 与 [T1,T2) 不重叠，不消耗同一时段容量
        occupy(rev2.reviewId(), "za", "b-high");
        assertEquals(2, activeCount("b-high"));

        // r3 的窗口只与 r1 重叠（不与 r2 重叠）：该时段计数为 1，仍可占用
        route("r3", 900, T0 + 1000, T1);
        ReviewResultDto rev3 = review("r3", 1, 2L);
        occupy(rev3.reviewId(), "za", "b-high");

        // r4 窗口落在 [T0,T1) 内：与 r1、r3 都重叠，该时段已有 2 个 ACTIVE，达到容量 429
        route("r4", 900, T0 + 500, T1 - 500);
        ReviewResultDto rev4 = review("r4", 1, 2L);
        ApiException full = assertThrows(ApiException.class,
                () -> occupy(rev4.reviewId(), "za", "b-high"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, full.status());
        assertEquals(2, full.details().get("activeCount"));

        // r2 所在的 [T1,T2) 时段仍只有 1 个占用：同窗口新航线可占用
        route("r5", 900, T1 + 500, T2 - 500);
        ReviewResultDto rev5 = review("r5", 1, 2L);
        occupy(rev5.reviewId(), "za", "b-high");
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM altitude_occupation WHERE status='ACTIVE'",
                Integer.class));
    }

    @Test
    void altitudeNotOverlappingBandDoesNotConsumeCapacity() {
        setupManagedZone();
        // 巡航 2000 不进入任何高度带：创建占用 422，且不消耗容量
        route("r-high", 2000, T0, T1);
        ReviewResultDto review = review("r-high", 1, 2L);
        assertEquals("CLEAR", review.conclusion());
        ApiException ex = assertThrows(ApiException.class,
                () -> occupy(review.reviewId(), "za", "b-high"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("ALTITUDE_NOT_OVERLAPPING", ex.code());
        assertEquals(0, activeCount("b-high"));
    }

    // ============================ 占用：取消与历史 ============================

    @Test
    void cancelReleasesCapacityImmediatelyAndKeepsHistory() {
        setupManagedZone();
        route("r1", 900, T0, T1);
        route("r2", 900, T0, T1);
        ReviewResultDto rev1 = review("r1", 1, 2L);
        ReviewResultDto rev2 = review("r2", 1, 2L);
        // 占满 b-high（容量 2）
        OccupationResultDto occ1 = occupy(rev1.reviewId(), "za", "b-high");
        occupy(rev2.reviewId(), "za", "b-high");

        route("r3", 900, T0, T1);
        ReviewResultDto rev3 = review("r3", 1, 2L);
        ApiException full = assertThrows(ApiException.class,
                () -> occupy(rev3.reviewId(), "za", "b-high"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, full.status());

        // 取消 occ1：立即释放容量，新占用成功；历史保留为 CANCELLED
        MutationResponse cancel = service.cancelOccupation(
                new OccupationCancelRequest(occ1.occupationId(), "req-" + rid("cancel")));
        OccupationResultDto cancelled = occData(cancel);
        assertEquals("CANCELLED", cancelled.status());
        assertNotNull(cancelled.cancelledAt());
        assertEquals(1, activeCount("b-high"));
        occupy(rev3.reviewId(), "za", "b-high");
        assertEquals(2, activeCount("b-high"));

        // 历史保留：按时段查询仍能看到已取消记录
        List<OccupationResultDto> all = service.listOccupations("za", T0, T1);
        assertEquals(3, all.size());
        assertEquals(1, all.stream().filter(o -> "CANCELLED".equals(o.status())).count());

        // 重复取消 → 409；取消不存在的占用 → 404
        ApiException twice = assertThrows(ApiException.class, () -> service.cancelOccupation(
                new OccupationCancelRequest(occ1.occupationId(), "req-" + rid("cancel"))));
        assertEquals(HttpStatus.CONFLICT, twice.status());
        ApiException missing = assertThrows(ApiException.class, () -> service.cancelOccupation(
                new OccupationCancelRequest("occ_ghost", "req-" + rid("cancel"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    // ============================ 占用：CLEAR/STALE 前置 ============================

    @Test
    void occupationRequiresClearAndCurrentReview() {
        // BLOCKED 审查（命中纯禁飞区）→ 422
        zone("nfz", 40, 5, 60, 15);
        route("rb", 900, T0, T1);
        ReviewResultDto blocked = review("rb", 1, 1L);
        assertEquals("BLOCKED", blocked.conclusion());
        ApiException notClear = assertThrows(ApiException.class,
                () -> occupy(blocked.reviewId(), "nfz", "any-band"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, notClear.status());
        assertEquals("REVIEW_NOT_CLEAR", notClear.code());

        // 空域版本变化使审查 STALE → 422（先撤销纯禁飞区，避免其继续拦截后续航线）
        service.revokeZone(new com.example.starter.api.dto.ZoneRevokeRequest(
                "nfz", "req-" + rid("revoke")));
        setupManagedZone();
        route("rs", 900, T0, T1);
        ReviewResultDto staleByAirspace = review("rs", 1, 4L);
        assertEquals("CLEAR", staleByAirspace.conclusion());
        zone("other", -50, -50, -40, -40);
        ApiException staleAirspace = assertThrows(ApiException.class,
                () -> occupy(staleByAirspace.reviewId(), "za", "b-high"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, staleAirspace.status());
        assertEquals("REVIEW_STALE", staleAirspace.code());

        // 航线替换使审查 STALE → 422
        route("rr", 900, T0, T1);
        ReviewResultDto staleByRoute = review("rr", 1, 5L);
        service.replaceRoute(new RouteReplaceRequest("rr", 1, pts(0, 20, 100, 20),
                900, T0, T1, "req-" + rid("replace")));
        ApiException staleRoute = assertThrows(ApiException.class,
                () -> occupy(staleByRoute.reviewId(), "za", "b-high"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, staleRoute.status());
        assertEquals("REVIEW_STALE", staleRoute.code());

        // 审查不存在 → 404
        ApiException missing = assertThrows(ApiException.class,
                () -> occupy("rv_ghost", "za", "b-high"));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    @Test
    void occupationValidatesZoneIntersectionAndBandOwnership() {
        setupManagedZone();
        zone("far", -50, -50, -40, -40);
        bands("far", 1, new BandConfigDto("b-far", 0, 1000, 5));
        route("ra", 900, T0, T1);
        ReviewResultDto review = review("ra", 1, 4L);
        assertEquals("CLEAR", review.conclusion());

        // 二维不相交的区域 → 422
        ApiException notIntersected = assertThrows(ApiException.class,
                () -> occupy(review.reviewId(), "far", "b-far"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, notIntersected.status());
        assertEquals("ZONE_NOT_INTERSECTED", notIntersected.code());

        // 高度带不属于该区域 → 404
        ApiException wrongBand = assertThrows(ApiException.class,
                () -> occupy(review.reviewId(), "za", "b-far"));
        assertEquals(HttpStatus.NOT_FOUND, wrongBand.status());
        assertEquals("BAND_NOT_FOUND", wrongBand.code());

        // 区域不存在 → 404
        ApiException noZone = assertThrows(ApiException.class,
                () -> occupy(review.reviewId(), "ghost", "b-high"));
        assertEquals(HttpStatus.NOT_FOUND, noZone.status());
    }

    // ============================ 占用：幂等 ============================

    @Test
    void occupationIdempotencyReplayAndFailureNotConsumingKey() {
        setupManagedZone();
        route("r1", 900, T0, T1);
        ReviewResultDto rev1 = review("r1", 1, 2L);

        // 同键同参重放：返回同一占用，不重复建行
        String requestId = "occ-idem-1";
        MutationResponse first = service.createOccupation(
                new OccupationCreateRequest(rev1.reviewId(), "za", "b-high", requestId));
        MutationResponse replay = service.createOccupation(
                new OccupationCreateRequest(rev1.reviewId(), "za", "b-high", requestId));
        assertTrue(replay.replayed());
        assertEquals(occData(first).occupationId(), occData(replay).occupationId());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM altitude_occupation", Integer.class));
        // 同键异参 → 409
        ApiException mismatch = assertThrows(ApiException.class, () -> service.createOccupation(
                new OccupationCreateRequest(rev1.reviewId(), "za", "b-low", requestId)));
        assertEquals(HttpStatus.CONFLICT, mismatch.status());

        // 429 失败不占键：占满 b-high 后同键失败，取消一个槽位后同键成功
        route("r2", 900, T0, T1);
        ReviewResultDto rev2 = review("r2", 1, 2L);
        occupy(rev2.reviewId(), "za", "b-high");
        route("r3", 900, T0, T1);
        ReviewResultDto rev3 = review("r3", 1, 2L);
        String failKey = "occ-fail-1";
        ApiException full = assertThrows(ApiException.class, () -> service.createOccupation(
                new OccupationCreateRequest(rev3.reviewId(), "za", "b-high", failKey)));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, full.status());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                Integer.class, failKey));
        service.cancelOccupation(
                new OccupationCancelRequest(occData(first).occupationId(), "req-" + rid("cancel")));
        MutationResponse afterRelease = service.createOccupation(
                new OccupationCreateRequest(rev3.reviewId(), "za", "b-high", failKey));
        assertFalse(afterRelease.replayed());
        assertEquals("ACTIVE", occData(afterRelease).status());
    }

    // ============================ 按时段占用查询 ============================

    @Test
    void listOccupationsFiltersByWindowAndZone() {
        setupManagedZone();
        zone("zb", 40, 5, 60, 15);
        bands("zb", 1, new BandConfigDto("b-zb", 0, 1000, 5));
        route("r1", 900, T0, T1);
        route("r2", 900, T1, T2);
        ReviewResultDto rev1 = review("r1", 1, 4L);
        ReviewResultDto rev2 = review("r2", 1, 4L);
        OccupationResultDto occ1 = occupy(rev1.reviewId(), "za", "b-high");
        OccupationResultDto occ2 = occupy(rev2.reviewId(), "zb", "b-zb");

        // 窗口 [T0,T1)：仅 occ1（occ2 起点相接不重叠）
        List<OccupationResultDto> firstWindow = service.listOccupations(null, T0, T1);
        assertEquals(1, firstWindow.size());
        assertEquals(occ1.occupationId(), firstWindow.get(0).occupationId());
        // 窗口 [T0,T2)：两条都在
        assertEquals(2, service.listOccupations(null, T0, T2).size());
        // 按区域过滤
        List<OccupationResultDto> zbOnly = service.listOccupations("zb", T0, T2);
        assertEquals(1, zbOnly.size());
        assertEquals(occ2.occupationId(), zbOnly.get(0).occupationId());
        // 非法时段 → 400
        ApiException badWindow = assertThrows(ApiException.class,
                () -> service.listOccupations(null, T1, T0));
        assertEquals(HttpStatus.BAD_REQUEST, badWindow.status());
    }

    // ============================ 并发 ============================

    @Test
    void concurrentOccupationsNeverOversellCapacity() throws Exception {
        setupManagedZone();
        // b-high 容量 2：8 条航线同时申请同一时段同一高度带
        int contenders = 8;
        List<ReviewResultDto> reviews = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String routeId = "cr" + i;
            route(routeId, 900, T0, T1);
            reviews.add(review(routeId, 1, 2L));
        }
        CyclicBarrier barrier = new CyclicBarrier(contenders);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                ReviewResultDto review = reviews.get(i);
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.createOccupation(new OccupationCreateRequest(
                                review.reviewId(), "za", "b-high",
                                "req-conc-occ-" + review.reviewId()));
                    } catch (ApiException ex) {
                        return ex;
                    }
                }));
            }
            int success = 0;
            int capacityExceeded = 0;
            for (Future<Object> future : futures) {
                Object result = future.get(20, TimeUnit.SECONDS);
                if (result instanceof MutationResponse) {
                    success++;
                } else {
                    ApiException ex = (ApiException) result;
                    assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.status());
                    capacityExceeded++;
                }
            }
            // 容量 2：恰好 2 个成功，其余 429，最终 ACTIVE 不超卖
            assertEquals(2, success, "成功占用数必须等于容量");
            assertEquals(contenders - 2, capacityExceeded);
            assertEquals(2, activeCount("b-high"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameRequestIdOccupationPlaysBackOneOutcome() throws Exception {
        setupManagedZone();
        route("r1", 900, T0, T1);
        ReviewResultDto review = review("r1", 1, 2L);
        String requestId = "occ-concurrent-key";
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.createOccupation(new OccupationCreateRequest(
                            review.reviewId(), "za", "b-high", requestId));
                }));
            }
            int firstCount = 0;
            int replayCount = 0;
            String occupationId = null;
            for (Future<MutationResponse> future : futures) {
                MutationResponse resp = future.get(20, TimeUnit.SECONDS);
                if (resp.replayed()) {
                    replayCount++;
                } else {
                    firstCount++;
                }
                String id = occData(resp).occupationId();
                if (occupationId == null) {
                    occupationId = id;
                } else {
                    assertEquals(occupationId, id, "重放必须返回同一占用记录");
                }
            }
            assertEquals(1, firstCount);
            assertEquals(n - 1, replayCount);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM altitude_occupation", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentBandConfigureSameExpectedVersionOneWins() throws Exception {
        zone("za", 40, 5, 60, 15);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return service.configureBands(new BandConfigureRequest("za", 1,
                            List.of(new BandConfigDto("b1", 0, 500, 3)), "req-cfg-1"));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            Future<Object> second = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return service.configureBands(new BandConfigureRequest("za", 1,
                            List.of(new BandConfigDto("b2", 600, 1000, 3)), "req-cfg-2"));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            Object r1 = first.get(20, TimeUnit.SECONDS);
            Object r2 = second.get(20, TimeUnit.SECONDS);
            int success = (r1 instanceof MutationResponse ? 1 : 0)
                    + (r2 instanceof MutationResponse ? 1 : 0);
            int conflict = (r1 instanceof ApiException ? 1 : 0)
                    + (r2 instanceof ApiException ? 1 : 0);
            // 同一 expectedVersion 并发配置：恰好一个成功，另一个 409
            assertEquals(1, success);
            assertEquals(1, conflict);
            assertEquals(2, service.getZoneBands("za").zoneVersion());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM altitude_band", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentCancelAndCreateKeepsCapacityConsistent() throws Exception {
        setupManagedZone();
        route("r1", 900, T0, T1);
        ReviewResultDto rev1 = review("r1", 1, 2L);
        // b-high 容量 2，先占满
        route("r2", 900, T0, T1);
        ReviewResultDto rev2 = review("r2", 1, 2L);
        OccupationResultDto occ1 = occupy(rev1.reviewId(), "za", "b-high");
        occupy(rev2.reviewId(), "za", "b-high");

        route("r3", 900, T0, T1);
        ReviewResultDto rev3 = review("r3", 1, 2L);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> cancelFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return service.cancelOccupation(
                            new OccupationCancelRequest(occ1.occupationId(), "req-cc-cancel"));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            Future<Object> createFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return service.createOccupation(new OccupationCreateRequest(
                            rev3.reviewId(), "za", "b-high", "req-cc-create"));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            Object cancelResult = cancelFuture.get(20, TimeUnit.SECONDS);
            Object createResult = createFuture.get(20, TimeUnit.SECONDS);
            assertTrue(cancelResult instanceof MutationResponse, "取消必须成功");
            // 创建可能成功（取消先提交）或 429（取消后提交）；无论哪种，最终 ACTIVE 不超容量
            int active = activeCount("b-high");
            assertTrue(active <= 2, "ACTIVE 占用数不得超过容量");
            if (createResult instanceof ApiException ex) {
                assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.status());
                assertEquals(1, active, "取消已生效时重试必须能成功");
                // 取消已释放槽位：重试同键（失败未占键）必须成功
                MutationResponse retry = service.createOccupation(new OccupationCreateRequest(
                        rev3.reviewId(), "za", "b-high", "req-cc-create"));
                assertFalse(retry.replayed());
                assertEquals(2, activeCount("b-high"));
            } else {
                assertEquals(2, active);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
