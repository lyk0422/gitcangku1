package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.BandConfigRequest;
import com.example.starter.api.dto.BandConfigResult;
import com.example.starter.api.dto.BandSpecDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyCancelRequest;
import com.example.starter.api.dto.OccupancyCreateRequest;
import com.example.starter.api.dto.OccupancyResult;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.VerticalDetailDto;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.service.AirspaceReviewService;
import com.example.starter.service.AltitudeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * 空域高度层容量与航线垂直分离审查的 H2 数据库测试（MODE=MySQL）。
 * 覆盖：高度层重叠判断（垂直分离）、容量校验（429）、版本失效（422 STALE）、
 * 高度带配置规则（409/400）、取消释放、幂等边界与真实并发不超卖。
 */
@SpringBootTest
@DisplayName("高度层容量与垂直分离 H2 业务测试")
class AltitudeServiceH2Test {

    @Autowired
    private AirspaceReviewService reviewService;
    @Autowired
    private AltitudeService altitudeService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    private final AtomicInteger seq = new AtomicInteger();

    // 固定几何：航线水平穿过 y=10，禁飞区矩形 (40,5)-(60,15)
    private static final List<RoutePointDto> LINE =
            List.of(new RoutePointDto(0, 10), new RoutePointDto(100, 10));
    private static final long T0 = 1_700_000_000_000L;
    private static final long H1 = 3_600_000L;

    @BeforeEach
    void cleanBefore() {
        cleanup();
    }

    @AfterEach
    void cleanAfter() {
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM altitude_occupancy");
        jdbc.update("DELETE FROM review_vertical_detail");
        jdbc.update("DELETE FROM altitude_band");
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

    private String createZone(String zoneId) {
        reviewService.createZone(new ZoneCreateRequest(
                zoneId, 40, 5, 60, 15, uniq("req-zone")));
        return zoneId;
    }

    /** 建区后配置一个高度带，返回配置后的当前空域版本。 */
    private long setupZoneWithBand(String zoneId, int lower, int upper, int capacity) {
        createZone(zoneId);
        MutationResponse resp = altitudeService.configureBands(new BandConfigRequest(
                zoneId, 1, List.of(new BandSpecDto(lower, upper, capacity)), uniq("req-band")));
        BandConfigResult result = objectMapper.convertValue(resp.data(), BandConfigResult.class);
        assertEquals(2, result.zoneVersion());
        return jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id=1", Long.class);
    }

    private String createAltRoute(String routeId, int altitude, long start, long end) {
        reviewService.createRoute(new RouteCreateRequest(
                routeId, LINE, uniq("req-route"), altitude, start, end));
        return routeId;
    }

    private ReviewResultDto review(String routeId, long airspaceVersion) {
        return review(routeId, 1, airspaceVersion);
    }

    private ReviewResultDto review(String routeId, int routeVersion, long airspaceVersion) {
        MutationResponse resp = reviewService.review(new ReviewRequest(
                routeId, routeVersion, airspaceVersion, uniq("req-review")));
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    private OccupancyResult occupy(String reviewId, String zoneId, int bandLower, String requestId) {
        MutationResponse resp = altitudeService.createOccupancy(new OccupancyCreateRequest(
                reviewId, zoneId, bandLower, requestId));
        assertFalse(resp.replayed());
        return objectMapper.convertValue(resp.data(), OccupancyResult.class);
    }

    private int activeCount(String zoneId, int bandLower) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM altitude_occupancy WHERE zone_id=? AND band_lower=? "
                        + "AND status='ACTIVE'", Integer.class, zoneId, bandLower);
    }

    // ============================ 垂直分离与高度审查 ============================

    @Test
    @DisplayName("高度不相交：CLEAR 且明细 verticalHit=false，不可占用、不消耗容量")
    void verticallySeparatedRouteClearsAndCannotOccupy() {
        long version = setupZoneWithBand("vz", 1000, 2000, 1);
        createAltRoute("sep", 500, T0, T0 + H1);
        ReviewResultDto dto = review("sep", version);
        assertEquals("CLEAR", dto.conclusion());

        List<VerticalDetailDto> details = altitudeService.getVerticalDetails(dto.reviewId());
        assertEquals(1, details.size());
        VerticalDetailDto detail = details.get(0);
        assertEquals("vz", detail.zoneId());
        assertFalse(detail.verticalHit());
        assertEquals(null, detail.bandLower());

        // 垂直分离的带不能占用 → 422
        ApiException ex = assertThrows(ApiException.class,
                () -> altitudeService.createOccupancy(new OccupancyCreateRequest(
                        dto.reviewId(), "vz", 1000, uniq("req-occ"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("VERTICAL_SEPARATION", ex.code());
        // 未产生任何占用，容量未被消耗
        assertEquals(0, activeCount("vz", 1000));
    }

    @Test
    @DisplayName("高度相交：CLEAR 且明细命中高度带；端点上界不相交")
    void altitudeInBandHitsAndUpperBoundIsOpen() {
        long version = setupZoneWithBand("hz", 1000, 2000, 5);
        createAltRoute("in", 1999, T0, T0 + H1);
        ReviewResultDto hit = review("in", version);
        assertEquals("CLEAR", hit.conclusion());
        VerticalDetailDto d1 = altitudeService.getVerticalDetails(hit.reviewId()).get(0);
        assertTrue(d1.verticalHit());
        assertEquals(1000, d1.bandLower());
        assertEquals(2000, d1.bandUpper());

        // 巡航高度恰为上界（左闭右开）→ 垂直分离
        createAltRoute("edge", 2000, T0, T0 + H1);
        ReviewResultDto edge = review("edge", version);
        assertFalse(altitudeService.getVerticalDetails(edge.reviewId()).get(0).verticalHit());
    }

    @Test
    @DisplayName("二维路径不相交：CLEAR 且无垂直分离明细，区域不消耗容量")
    void noTwoDimensionalIntersectionHasNoDetails() {
        long version = setupZoneWithBand("far", 1000, 2000, 1);
        // 航线在 y=90，区域矩形 y∈[5,15]，二维不相交
        reviewService.createRoute(new RouteCreateRequest(
                "away", List.of(new RoutePointDto(0, 90), new RoutePointDto(100, 90)),
                uniq("req-route"), 1500, T0, T0 + H1));
        ReviewResultDto dto = review("away", version);
        assertEquals("CLEAR", dto.conclusion());
        List<VerticalDetailDto> details = altitudeService.getVerticalDetails(dto.reviewId());
        assertTrue(details.isEmpty());
        // 不存在该二维相交区域，占用被拒（明细缺失）
        ApiException ex = assertThrows(ApiException.class,
                () -> altitudeService.createOccupancy(new OccupancyCreateRequest(
                        dto.reviewId(), "far", 1000, uniq("req-occ"))));
        assertEquals("ZONE_NOT_REVIEWED", ex.code());
        assertNull(altitudeService.findOccupancies(T0, T0 + H1, "far", 1000)
                .stream().findFirst().orElse(null));
    }

    @Test
    @DisplayName("未配置高度带的区域保持全高度禁飞：高度航线二维相交仍 BLOCKED")
    void zoneWithoutBandsBlocksAltitudeRouteIn2D() {        createZone("bz");
        long version = jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id=1", Long.class);
        createAltRoute("ar", 9999, T0, T0 + H1);
        ReviewResultDto dto = review("ar", version);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(List.of("bz"), dto.hitZoneIds());
        // BLOCKED 审查不可占用
        ApiException ex = assertThrows(ApiException.class,
                () -> altitudeService.createOccupancy(new OccupancyCreateRequest(
                        dto.reviewId(), "bz", 0, uniq("req-occ"))));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("REVIEW_NOT_CLEAR", ex.code());
    }

    // ============================ 容量裁决 429 ============================

    @Test
    @DisplayName("容量 1：首条占用成功，时间重叠第二条 429 并给出区域/高度带/占用数")
    void capacityFullReturns429WithZoneBandAndCount() {
        long version = setupZoneWithBand("cap", 1000, 2000, 1);
        createAltRoute("c1", 1500, T0, T0 + H1);
        ReviewResultDto r1 = review("c1", version);
        OccupancyResult o1 = occupy(r1.reviewId(), "cap", 1000, uniq("req-occ"));
        assertEquals("ACTIVE", o1.status());
        assertEquals(1, o1.activeCount());
        assertEquals(1, o1.capacity());

        createAltRoute("c2", 1600, T0 + 1000, T0 + H1);
        ReviewResultDto r2 = review("c2", version);
        ApiException ex = assertThrows(ApiException.class,
                () -> altitudeService.createOccupancy(new OccupancyCreateRequest(
                        r2.reviewId(), "cap", 1000, uniq("req-occ"))));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.status());
        assertEquals("BAND_CAPACITY_EXCEEDED", ex.code());
        assertTrue(ex.getMessage().contains("zone=cap"));
        assertTrue(ex.getMessage().contains("bandLower=1000"));
        assertTrue(ex.getMessage().contains("activeOccupancies=1"));
        // 429 失败不产生占用
        assertEquals(1, activeCount("cap", 1000));
    }

    @Test
    @DisplayName("时间不重叠（端点相接）不占用同一容量")
    void disjointAndTouchingWindowsDoNotConsumeCapacity() {
        long version = setupZoneWithBand("win", 1000, 2000, 1);
        // [T0, T0+H1) 与 [T0+H1, T0+2H1) 端点相接
        createAltRoute("w1", 1500, T0, T0 + H1);
        createAltRoute("w2", 1500, T0 + H1, T0 + 2 * H1);
        ReviewResultDto r1 = review("w1", version);
        ReviewResultDto r2 = review("w2", version);
        OccupancyResult o1 = occupy(r1.reviewId(), "win", 1000, uniq("req-occ"));
        OccupancyResult o2 = occupy(r2.reviewId(), "win", 1000, uniq("req-occ"));
        assertNotNull(o1.occupancyId());
        assertNotNull(o2.occupancyId());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM altitude_occupancy WHERE status='ACTIVE'", Integer.class));

        // 时间窗查询：[T0, T0+H1) 只命中第一条；相接窗 [T0+H1, ...) 不含第一条
        List<OccupancyResult> first = altitudeService.findOccupancies(
                T0, T0 + H1, "win", 1000);
        assertEquals(1, first.size());
        List<OccupancyResult> touching = altitudeService.findOccupancies(
                T0 + H1, T0 + 2 * H1, "win", 1000);
        assertEquals(1, touching.size());
        // 大窗口返回两条
        assertEquals(2, altitudeService.findOccupancies(
                T0, T0 + 2 * H1, "win", 1000).size());
    }

    // ============================ 取消释放与历史保留 ============================

    @Test
    @DisplayName("取消立即释放容量，历史保留，重复取消 409")
    void cancelReleasesCapacityAndKeepsHistory() {
        long version = setupZoneWithBand("can", 1000, 2000, 1);
        createAltRoute("k1", 1500, T0, T0 + H1);
        createAltRoute("k2", 1500, T0, T0 + H1);
        ReviewResultDto r1 = review("k1", version);
        ReviewResultDto r2 = review("k2", version);
        OccupancyResult o1 = occupy(r1.reviewId(), "can", 1000, uniq("req-occ"));

        ApiException full = assertThrows(ApiException.class,
                () -> altitudeService.createOccupancy(new OccupancyCreateRequest(
                        r2.reviewId(), "can", 1000, uniq("req-occ"))));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, full.status());

        MutationResponse cancel = altitudeService.cancelOccupancy(
                new OccupancyCancelRequest(o1.occupancyId(), uniq("req-cancel")));
        assertEquals("CANCELLED",
                objectMapper.convertValue(cancel.data(), OccupancyResult.class).status());
        assertEquals(0, activeCount("can", 1000));

        // 容量释放：第二条可以占用
        OccupancyResult o2 = occupy(r2.reviewId(), "can", 1000, uniq("req-occ"));
        assertEquals("ACTIVE", o2.status());

        // 历史保留：被取消记录仍可查询
        OccupancyResult history = altitudeService.getOccupancy(o1.occupancyId());
        assertEquals("CANCELLED", history.status());
        assertNotNull(history);

        // 重复取消 409；不存在 404
        ApiException twice = assertThrows(ApiException.class,
                () -> altitudeService.cancelOccupancy(
                        new OccupancyCancelRequest(o1.occupancyId(), uniq("req-cancel2"))));
        assertEquals(HttpStatus.CONFLICT, twice.status());
        ApiException missing = assertThrows(ApiException.class,
                () -> altitudeService.cancelOccupancy(
                        new OccupancyCancelRequest("oc_ghost", uniq("req-cancel3"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    // ============================ 版本失效 422 ============================

    @Test
    @DisplayName("空域版本（高度带配置）变化后旧审查占用 → 422 STALE")
    void bandConfigChangeMakesReviewStale() {
        createZone("st");
        altitudeService.configureBands(new BandConfigRequest("st", 1,
                List.of(new BandSpecDto(1000, 2000, 1)), uniq("req-band")));
        long version = jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id=1", Long.class);
        createAltRoute("sr", 1500, T0, T0 + H1);
        ReviewResultDto r = review("sr", version);

        // 上调容量：合法，推进空域版本，旧审查 STALE
        MutationResponse upd = altitudeService.configureBands(new BandConfigRequest("st", 2,
                List.of(new BandSpecDto(1000, 2000, 5)), uniq("req-band2")));
        assertEquals(3, objectMapper.convertValue(upd.data(), BandConfigResult.class).zoneVersion());

        ApiException ex = assertThrows(ApiException.class,
                () -> altitudeService.createOccupancy(new OccupancyCreateRequest(
                        r.reviewId(), "st", 1000, uniq("req-occ"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("REVIEW_STALE", ex.code());
    }

    @Test
    @DisplayName("航线替换后旧审查占用 → 422 STALE；重新审查后可占用")
    void routeReplaceMakesReviewStale() {
        long version = setupZoneWithBand("rs", 1000, 2000, 2);
        createAltRoute("rr", 1500, T0, T0 + H1);
        ReviewResultDto r = review("rr", version);

        reviewService.replaceRoute(new RouteReplaceRequest("rr", 1, LINE,
                uniq("req-replace"), 1500, T0, T0 + H1));
        ApiException ex = assertThrows(ApiException.class,
                () -> altitudeService.createOccupancy(new OccupancyCreateRequest(
                        r.reviewId(), "rs", 1000, uniq("req-occ"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());

        ReviewResultDto r2 = review("rr", 2, version);
        OccupancyResult o = occupy(r2.reviewId(), "rs", 1000, uniq("req-occ"));
        assertEquals("ACTIVE", o.status());
    }

    // ============================ 高度带配置规则 ============================

    @Test
    @DisplayName("高度带配置：上调/新增相接带合法，下调/重叠/删除/改界/版本冲突被拒")
    void bandConfigurationRules() {
        createZone("cfg");
        altitudeService.configureBands(new BandConfigRequest("cfg", 1,
                List.of(new BandSpecDto(1000, 2000, 5)), uniq("req-band")));

        // 下调容量 → 400
        ApiException decrease = assertThrows(ApiException.class,
                () -> altitudeService.configureBands(new BandConfigRequest("cfg", 2,
                        List.of(new BandSpecDto(1000, 2000, 4)), uniq("req-bad"))));
        assertEquals(HttpStatus.BAD_REQUEST, decrease.status());
        assertEquals("CAPACITY_ONLY_INCREASE", decrease.code());

        // 新增重叠带 → 400
        ApiException overlap = assertThrows(ApiException.class,
                () -> altitudeService.configureBands(new BandConfigRequest("cfg", 2,
                        List.of(new BandSpecDto(1000, 2000, 5),
                                new BandSpecDto(1500, 2500, 5)), uniq("req-bad"))));
        assertEquals(HttpStatus.BAD_REQUEST, overlap.status());
        assertEquals("BANDS_OVERLAP", overlap.code());

        // 删除既有带 → 400
        ApiException removal = assertThrows(ApiException.class,
                () -> altitudeService.configureBands(new BandConfigRequest("cfg", 2,
                        List.of(), uniq("req-bad"))));
        // 空列表也可能被 @Size 拦截；这里显式构造非空但缺失的用例
        assertEquals(HttpStatus.BAD_REQUEST, removal.status());
        ApiException removal2 = assertThrows(ApiException.class,
                () -> altitudeService.configureBands(new BandConfigRequest("cfg", 2,
                        List.of(new BandSpecDto(3000, 4000, 5)), uniq("req-bad"))));
        assertEquals("BAND_REMOVAL_FORBIDDEN", removal2.code());

        // 修改既有带边界 → 400
        ApiException boundary = assertThrows(ApiException.class,
                () -> altitudeService.configureBands(new BandConfigRequest("cfg", 2,
                        List.of(new BandSpecDto(1000, 2100, 5)), uniq("req-bad"))));
        assertEquals("BAND_BOUNDARY_IMMUTABLE", boundary.code());

        // expectedVersion 不匹配 → 409
        ApiException versionConflict = assertThrows(ApiException.class,
                () -> altitudeService.configureBands(new BandConfigRequest("cfg", 99,
                        List.of(new BandSpecDto(1000, 2000, 6)), uniq("req-bad"))));
        assertEquals(HttpStatus.CONFLICT, versionConflict.status());
        assertEquals("ZONE_VERSION_CONFLICT", versionConflict.code());

        // 失败均不推进区域版本：仍可用 expectedVersion=2 成功上调容量并新增端点相接带
        MutationResponse ok = altitudeService.configureBands(new BandConfigRequest("cfg", 2,
                List.of(new BandSpecDto(1000, 2000, 8),
                        new BandSpecDto(2000, 3000, 3)), uniq("req-band-ok")));
        BandConfigResult result = objectMapper.convertValue(ok.data(), BandConfigResult.class);
        assertEquals(3, result.zoneVersion());
        assertEquals(2, result.bands().size());
        assertEquals(8, result.bands().get(0).capacity());
    }

    @Test
    @DisplayName("新增带自身重叠或退化 → 400；撤销区域不可配置 → 409；缺区域 404")
    void bandConfigValidationAndZoneState() {
        createZone("cv");
        ApiException degenerate = assertThrows(ApiException.class,
                () -> altitudeService.configureBands(new BandConfigRequest("cv", 1,
                        List.of(new BandSpecDto(2000, 2000, 5)), uniq("req-bad"))));
        assertEquals("INVALID_ALTITUDE_BAND", degenerate.code());
        ApiException selfOverlap = assertThrows(ApiException.class,
                () -> altitudeService.configureBands(new BandConfigRequest("cv", 1,
                        List.of(new BandSpecDto(1000, 3000, 5),
                                new BandSpecDto(2000, 4000, 5)), uniq("req-bad"))));
        assertEquals("BANDS_OVERLAP", selfOverlap.code());

        ApiException noZone = assertThrows(ApiException.class,
                () -> altitudeService.configureBands(new BandConfigRequest("ghost", 1,
                        List.of(new BandSpecDto(1000, 2000, 5)), uniq("req-bad"))));
        assertEquals(HttpStatus.NOT_FOUND, noZone.status());
    }

    @Test
    @DisplayName("高度带修改不追溯改写已有占用记录（带上限快照保持）")
    void bandChangesDoNotRewriteExistingOccupancies() {
        long version = setupZoneWithBand("keep", 1000, 2000, 1);
        createAltRoute("kp", 1500, T0, T0 + H1);
        ReviewResultDto r = review("kp", version);
        OccupancyResult before = occupy(r.reviewId(), "keep", 1000, uniq("req-occ"));
        assertEquals(2000, before.bandUpper());

        // 新增相邻带不影响旧占用；旧占用记录的 band_upper 仍为创建时快照
        altitudeService.configureBands(new BandConfigRequest("keep", 2,
                List.of(new BandSpecDto(1000, 2000, 1),
                        new BandSpecDto(2000, 3000, 9)), uniq("req-band")));
        OccupancyResult after = altitudeService.getOccupancy(before.occupancyId());
        assertEquals(2000, after.bandUpper());
        assertEquals("ACTIVE", after.status());
    }

    // ============================ 幂等 ============================

    @Test
    @DisplayName("占用同键同参重放首次结果，异参 409")
    void occupancyIdempotentReplay() {
        long version = setupZoneWithBand("idem", 1000, 2000, 5);
        createAltRoute("ir", 1500, T0, T0 + H1);
        ReviewResultDto r = review("ir", version);
        String requestId = "occ-fixed-1";
        MutationResponse first = altitudeService.createOccupancy(new OccupancyCreateRequest(
                r.reviewId(), "idem", 1000, requestId));
        MutationResponse replay = altitudeService.createOccupancy(new OccupancyCreateRequest(
                r.reviewId(), "idem", 1000, requestId));
        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(
                objectMapper.convertValue(first.data(), OccupancyResult.class).occupancyId(),
                objectMapper.convertValue(replay.data(), OccupancyResult.class).occupancyId());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM altitude_occupancy WHERE status='ACTIVE'", Integer.class));

        ApiException diff = assertThrows(ApiException.class,
                () -> altitudeService.createOccupancy(new OccupancyCreateRequest(
                        r.reviewId(), "idem", 1000, "occ-fixed-other")));
        assertEquals(HttpStatus.CONFLICT, diff.status());
    }

    @Test
    @DisplayName("429 失败不占用 requestId：释放容量后同键可成功")
    void failed429DoesNotConsumeRequestId() {
        long version = setupZoneWithBand("fk", 1000, 2000, 1);
        createAltRoute("f1", 1500, T0, T0 + H1);
        createAltRoute("f2", 1500, T0, T0 + H1);
        ReviewResultDto r1 = review("f1", version);
        ReviewResultDto r2 = review("f2", version);
        occupy(r1.reviewId(), "fk", 1000, uniq("req-occ"));

        String requestId = "occ-fail-key-1";
        ApiException failed = assertThrows(ApiException.class,
                () -> altitudeService.createOccupancy(new OccupancyCreateRequest(
                        r2.reviewId(), "fk", 1000, requestId)));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, failed.status());
        // 失败回滚不占键
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id=?", Integer.class, requestId));

        // 取消释放后，同键同参成功（非重放）
        String firstOccupancy = jdbc.queryForObject(
                "SELECT occupancy_id FROM altitude_occupancy WHERE review_id=?",
                String.class, r1.reviewId());
        altitudeService.cancelOccupancy(
                new OccupancyCancelRequest(firstOccupancy, uniq("req-cancel")));
        MutationResponse ok = altitudeService.createOccupancy(new OccupancyCreateRequest(
                r2.reviewId(), "fk", 1000, requestId));
        assertFalse(ok.replayed());
    }

    // ============================ 并发不超卖 ============================

    @Test
    @DisplayName("容量 2 下 8 个真实线程并发占用：恰好 2 条 ACTIVE，其余 429")
    void concurrentOccupancyNeverOversellsCapacity() throws Exception {
        int capacity = 2;
        int threads = 8;
        long version = setupZoneWithBand("race", 1000, 2000, capacity);
        List<String> reviewIds = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String routeId = "race-r" + i;
            // 同时段、同高度带：全部时间重叠
            createAltRoute(routeId, 1500, T0, T0 + H1);
            reviewIds.add(review(routeId, version).reviewId());
        }

        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String reviewId = reviewIds.get(i);
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return altitudeService.createOccupancy(new OccupancyCreateRequest(
                                reviewId, "race", 1000, uniq("req-race")));
                    } catch (ApiException ex) {
                        return ex;
                    }
                }));
            }
            int success = 0;
            int rejected = 0;
            for (Future<Object> f : futures) {
                Object result = f.get(30, TimeUnit.SECONDS);
                if (result instanceof MutationResponse mr && !mr.replayed()) {
                    success++;
                } else if (result instanceof ApiException ex
                        && ex.status() == HttpStatus.TOO_MANY_REQUESTS) {
                    rejected++;
                } else {
                    throw new AssertionError("非预期并发结果: " + result);
                }
            }
            assertEquals(capacity, success, "成功占用数必须恰好等于容量，绝不超卖");
            assertEquals(threads - capacity, rejected);
            assertEquals(capacity, activeCount("race", 1000));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发取消与占用：容量绝不超卖，取消提交后容量立即可用")
    void concurrentCancelAndOccupyIsSerialized() throws Exception {
        long version = setupZoneWithBand("xc", 1000, 2000, 1);
        createAltRoute("xa", 1500, T0, T0 + H1);
        createAltRoute("xb", 1500, T0, T0 + H1);
        ReviewResultDto ra = review("xa", version);
        ReviewResultDto rb = review("xb", version);
        OccupancyResult oa = occupy(ra.reviewId(), "xc", 1000, uniq("req-occ"));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> cancelFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return altitudeService.cancelOccupancy(
                            new OccupancyCancelRequest(oa.occupancyId(), uniq("req-cancel")));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            Future<Object> occupyFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return altitudeService.createOccupancy(new OccupancyCreateRequest(
                            rb.reviewId(), "xc", 1000, uniq("req-occ")));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            Object cancelResult = cancelFuture.get(15, TimeUnit.SECONDS);
            Object occupyResult = occupyFuture.get(15, TimeUnit.SECONDS);
            assertTrue(cancelResult instanceof MutationResponse, "取消必须成功");

            // 任一提交顺序都合法，但绝不允许超卖
            if (occupyResult instanceof MutationResponse mr) {
                // 取消先提交：新占用成功，最终恰好 1 条 ACTIVE（新占用）
                assertFalse(mr.replayed());
            } else {
                // 占用先裁决：容量满 429；取消提交后容量释放，重试必成功
                assertEquals(HttpStatus.TOO_MANY_REQUESTS, ((ApiException) occupyResult).status());
                MutationResponse retry = altitudeService.createOccupancy(new OccupancyCreateRequest(
                        rb.reviewId(), "xc", 1000, uniq("req-occ-after")));
                assertFalse(retry.replayed());
            }
            assertEquals(1, activeCount("xc", 1000));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM altitude_occupancy WHERE status='CANCELLED'",
                    Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发高度带修改与占用：占用先提交成功或修改先提交致 422，绝不超卖")
    void concurrentBandConfigAndOccupyIsAdjudicatedByCommitOrder() throws Exception {
        createZone("bc");
        altitudeService.configureBands(new BandConfigRequest("bc", 1,
                List.of(new BandSpecDto(1000, 2000, 1)), uniq("req-band")));
        long version = jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id=1", Long.class);
        createAltRoute("bcr", 1500, T0, T0 + H1);
        ReviewResultDto r = review("bcr", version);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> configFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return altitudeService.configureBands(new BandConfigRequest("bc", 2,
                            List.of(new BandSpecDto(1000, 2000, 5)), uniq("req-band2")));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            Future<Object> occupyFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return altitudeService.createOccupancy(new OccupancyCreateRequest(
                            r.reviewId(), "bc", 1000, uniq("req-occ")));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            Object configResult = configFuture.get(15, TimeUnit.SECONDS);
            Object occupyResult = occupyFuture.get(15, TimeUnit.SECONDS);
            assertTrue(configResult instanceof MutationResponse, "容量上调必须成功一次");

            if (occupyResult instanceof MutationResponse) {
                // 占用先于配置提交：成功且不超卖；配置随后提交，旧审查之后变为 STALE
                assertEquals(1, activeCount("bc", 1000));
            } else {
                // 配置先提交：空域版本推进，旧审查占用必须 422
                ApiException ex = (ApiException) occupyResult;
                assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
                assertEquals("REVIEW_STALE", ex.code());
            }
            // 配置最终生效：容量 5、区域版本 3
            BandConfigResult bands = altitudeService.getBands("bc");
            assertEquals(3, bands.zoneVersion());
            assertEquals(5, bands.bands().get(0).capacity());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("同键同参并发占用：恰好一次业务生效，其余重放同一占用")
    void concurrentSameRequestIdOccupancyPlaysBackOneOutcome() throws Exception {
        long version = setupZoneWithBand("ck", 1000, 2000, 50);
        createAltRoute("ckr", 1500, T0, T0 + H1);
        ReviewResultDto r = review("ckr", version);

        int n = 6;
        String requestId = "occ-concurrent-key";
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return altitudeService.createOccupancy(new OccupancyCreateRequest(
                            r.reviewId(), "ck", 1000, requestId));
                }));
            }
            int firstCount = 0;
            int replayCount = 0;
            String occupancyId = null;
            for (Future<MutationResponse> f : futures) {
                MutationResponse resp = f.get(20, TimeUnit.SECONDS);
                OccupancyResult data = objectMapper.convertValue(resp.data(), OccupancyResult.class);
                if (resp.replayed()) {
                    replayCount++;
                } else {
                    firstCount++;
                }
                if (occupancyId == null) {
                    occupancyId = data.occupancyId();
                } else {
                    assertEquals(occupancyId, data.occupancyId());
                }
            }
            assertEquals(1, firstCount, "仅一次占用真正执行业务");
            assertEquals(n - 1, replayCount);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM altitude_occupancy WHERE status='ACTIVE'",
                    Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_dedup WHERE request_id=?",
                    Integer.class, requestId));
        } finally {
            pool.shutdownNow();
        }
    }
}
