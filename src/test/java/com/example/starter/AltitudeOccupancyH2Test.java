package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.AltitudeBandDto;
import com.example.starter.api.dto.BandCapacityUpdateDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyCancelRequest;
import com.example.starter.api.dto.OccupancyCreateRequest;
import com.example.starter.api.dto.OccupancyListResult;
import com.example.starter.api.dto.OccupancyResult;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.VerticalSeparationResult;
import com.example.starter.api.dto.ZoneBandsModifyRequest;
import com.example.starter.api.dto.ZoneBandsResult;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.api.dto.ZoneRevokeRequest;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 高度层容量与垂直分离审查的 H2 数据库测试（MODE=MySQL）。
 * 覆盖高度重叠判断、容量校验与 429、STALE 422、高度带配置版本冲突、
 * 取消释放容量、幂等重放与真实并发不超卖。
 */
@SpringBootTest
@DisplayName("高度层容量与垂直分离审查 H2 测试")
class AltitudeOccupancyH2Test {

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
        jdbc.update("DELETE FROM altitude_occupancy");
        jdbc.update("DELETE FROM zone_altitude_band");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }

    private String rid(String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    private static List<RoutePointDto> pts(int... xy) {
        return java.util.stream.IntStream.range(0, xy.length / 2)
                .mapToObj(i -> new RoutePointDto(xy[2 * i], xy[2 * i + 1]))
                .toList();
    }

    private void createZone(String zoneId, int xMin, int yMin, int xMax, int yMax,
                            AltitudeBandDto... bands) {
        service.createZone(new ZoneCreateRequest(zoneId, xMin, yMin, xMax, yMax,
                "req-" + rid("zone"), bands.length == 0 ? null : List.of(bands)));
    }

    private void createRoute(String routeId, int altitudeM, long startAt, long endAt,
                             int... xy) {
        service.createRoute(new RouteCreateRequest(routeId, pts(xy), altitudeM,
                startAt, endAt, "req-" + rid("route")));
    }

    private ReviewResultDto review(String routeId, int routeVersion, long airspaceVersion) {
        MutationResponse resp = service.review(new ReviewRequest(
                routeId, routeVersion, airspaceVersion, "req-" + rid("review")));
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    private MutationResponse occupy(String reviewId, String zoneId, String bandId) {
        return service.createOccupancy(new OccupancyCreateRequest(
                reviewId, zoneId, bandId, "req-" + rid("occ")));
    }

    private OccupancyResult occData(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), OccupancyResult.class);
    }

    private long airspaceVersion() {
        return jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id = 1", Long.class);
    }

    // ============================ 审查：高度带相交判断 ============================

    @Test
    @DisplayName("审查：仅二维相交且高度带相交才拦截，左闭右开")
    void reviewBlocksOnlyWhenAltitudeIntersectsBand() {
        createZone("zb", 40, 5, 60, 15, new AltitudeBandDto("b1", 100, 200, 3));
        createZone("zl", 40, 105, 60, 115); // 无高度带：保持二维相交即拦截

        // 高度落入带内 → BLOCKED
        createRoute("ra", 150, 1000, 2000, 0, 10, 100, 10);
        ReviewResultDto blocked = review("ra", 1, 2);
        assertEquals("BLOCKED", blocked.conclusion());
        assertEquals(List.of("zb"), blocked.hitZoneIds());

        // 高度等于下限（左闭）→ BLOCKED
        createRoute("rb", 100, 1000, 2000, 0, 10, 100, 10);
        assertEquals("BLOCKED", review("rb", 1, 2).conclusion());

        // 高度等于上限（右开）→ CLEAR
        createRoute("rc", 200, 1000, 2000, 0, 10, 100, 10);
        assertEquals("CLEAR", review("rc", 1, 2).conclusion());

        // 高度低于下限 → CLEAR
        createRoute("rd", 99, 1000, 2000, 0, 10, 100, 10);
        assertEquals("CLEAR", review("rd", 1, 2).conclusion());

        // 高度高于上限 → CLEAR
        createRoute("re", 300, 1000, 2000, 0, 10, 100, 10);
        assertEquals("CLEAR", review("re", 1, 2).conclusion());

        // 无高度带区域：二维相交即拦截（既有语义不变）
        createRoute("rf", 150, 1000, 2000, 0, 110, 100, 110);
        ReviewResultDto legacy = review("rf", 1, 2);
        assertEquals("BLOCKED", legacy.conclusion());
        assertEquals(List.of("zl"), legacy.hitZoneIds());

        // 二维不相交，即使高度落入带内也 CLEAR
        createRoute("rg", 150, 1000, 2000, 0, 1000, 100, 1000);
        assertEquals("CLEAR", review("rg", 1, 2).conclusion());
    }

    @Test
    @DisplayName("建区高度带校验：重叠 400，端点相接合法，下限必须小于上限")
    void zoneCreateValidatesBands() {
        // 请求内重叠 → 400
        ApiException overlap = assertThrows(ApiException.class, () -> createZone("zo",
                0, 0, 10, 10,
                new AltitudeBandDto("b1", 0, 150, 1),
                new AltitudeBandDto("b2", 100, 200, 1)));
        assertEquals(HttpStatus.BAD_REQUEST, overlap.status());
        assertEquals("BAND_OVERLAP", overlap.code());

        // 下限 >= 上限 → 400
        ApiException badRange = assertThrows(ApiException.class, () -> createZone("zr",
                0, 0, 10, 10, new AltitudeBandDto("b1", 200, 200, 1)));
        assertEquals(HttpStatus.BAD_REQUEST, badRange.status());

        // 标识重复 → 400
        ApiException dup = assertThrows(ApiException.class, () -> createZone("zd",
                0, 0, 10, 10,
                new AltitudeBandDto("b1", 0, 100, 1),
                new AltitudeBandDto("b1", 100, 200, 1)));
        assertEquals(HttpStatus.BAD_REQUEST, dup.status());

        // 端点相接合法
        createZone("zt", 0, 0, 10, 10,
                new AltitudeBandDto("b1", 0, 100, 1),
                new AltitudeBandDto("b2", 100, 200, 2));
        ZoneBandsResult config = service.getZoneBands("zt");
        assertEquals(1, config.configVersion());
        assertEquals(2, config.bands().size());
        assertEquals("b1", config.bands().get(0).bandId());
    }

    // ============================ 占用主流程与容量 ============================

    @Test
    @DisplayName("占用：CLEAR 可创建，容量满 429 带明细，端点相接时段不重叠，高度不相交不消耗")
    void occupancyCapacityAnd429() {
        createZone("z1", 40, 5, 60, 15, new AltitudeBandDto("b1", 100, 200, 1));

        // R4：二维相交但高度在带外 → CLEAR；先创建占用，不消耗容量
        createRoute("r4", 500, 1000, 2000, 0, 10, 100, 10);
        ReviewResultDto rev4 = review("r4", 1, 1);
        assertEquals("CLEAR", rev4.conclusion());
        OccupancyResult occ4 = occData(occupy(rev4.reviewId(), "z1", "b1"));
        assertFalse(occ4.consumes());

        // R1：二维不相交、高度落入带内、时段 [1000,2000) → CLEAR；
        // R4 的占用不消耗容量，容量计数仍为 0，R1 占用成功
        createRoute("r1", 150, 1000, 2000, 0, 1000, 100, 1000);
        ReviewResultDto rev1 = review("r1", 1, 1);
        assertEquals("CLEAR", rev1.conclusion());
        OccupancyResult occ1 = occData(occupy(rev1.reviewId(), "z1", "b1"));
        assertTrue(occ1.consumes());
        assertEquals("ACTIVE", occ1.status());
        assertEquals(150, occ1.cruiseAltitudeM());
        assertEquals(1000, occ1.startAt());
        assertEquals(2000, occ1.endAt());

        // R2：时段重叠且高度落入带内 → 429，明细含区域、高度带与占用数
        createRoute("r2", 150, 1500, 2500, 0, 2000, 100, 2000);
        ReviewResultDto rev2 = review("r2", 1, 1);
        ApiException full = assertThrows(ApiException.class,
                () -> occupy(rev2.reviewId(), "z1", "b1"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, full.status());
        assertEquals("CAPACITY_EXCEEDED", full.code());
        assertNotNull(full.details());
        assertEquals("z1", full.details().get("zoneId"));
        assertEquals("b1", full.details().get("bandId"));
        assertEquals(1, full.details().get("activeCount"));
        assertEquals(1, full.details().get("capacity"));

        // R3：时段端点相接（[2000,3000) 与 [1000,2000) 不重叠）→ 可创建
        createRoute("r3", 150, 2000, 3000, 0, 3000, 100, 3000);
        ReviewResultDto rev3 = review("r3", 1, 1);
        assertTrue(occData(occupy(rev3.reviewId(), "z1", "b1")).consumes());

        // 容量计数只算 ACTIVE 且高度落入带内且时段重叠的占用：R1 一个
        Integer consuming = jdbc.queryForObject(
                "SELECT COUNT(*) FROM altitude_occupancy o "
                        + "JOIN zone_altitude_band b ON b.zone_id = o.zone_id AND b.band_id = o.band_id "
                        + "WHERE o.status = 'ACTIVE' AND o.start_at < 2000 AND o.end_at > 1000 "
                        + "AND o.cruise_altitude_m >= b.lower_m AND o.cruise_altitude_m < b.upper_m",
                Integer.class);
        assertEquals(1, consuming);
    }

    @Test
    @DisplayName("取消：立即释放容量、历史保留，重复取消 409，取消不存在 404")
    void cancelReleasesCapacityAndKeepsHistory() {
        createZone("z1", 40, 5, 60, 15, new AltitudeBandDto("b1", 100, 200, 1));
        createRoute("r1", 150, 1000, 2000, 0, 1000, 100, 1000);
        ReviewResultDto rev1 = review("r1", 1, 1);
        OccupancyResult occ1 = occData(occupy(rev1.reviewId(), "z1", "b1"));

        // 容量被占满：R2 429
        createRoute("r2", 150, 1000, 2000, 0, 2000, 100, 2000);
        ReviewResultDto rev2 = review("r2", 1, 1);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, assertThrows(ApiException.class,
                () -> occupy(rev2.reviewId(), "z1", "b1")).status());

        // 取消 R1 占用 → 容量立即释放，R2 可创建
        MutationResponse cancelled = service.cancelOccupancy(
                new OccupancyCancelRequest(occ1.occupancyId(), "req-" + rid("cancel")));
        assertEquals("CANCELLED", occData(cancelled).status());
        assertTrue(occData(occupy(rev2.reviewId(), "z1", "b1")).consumes());

        // 历史保留：按时段查询仍能看到已取消记录
        OccupancyListResult history = service.getOccupancies("z1", "b1", 0L, 3000L);
        assertEquals(2, history.occupancies().size());
        assertEquals("CANCELLED", history.occupancies().get(0).status());
        assertEquals("ACTIVE", history.occupancies().get(1).status());

        // 重复取消 → 409；取消不存在 → 404
        ApiException twice = assertThrows(ApiException.class, () -> service.cancelOccupancy(
                new OccupancyCancelRequest(occ1.occupancyId(), "req-" + rid("cancel"))));
        assertEquals(HttpStatus.CONFLICT, twice.status());
        ApiException missing = assertThrows(ApiException.class, () -> service.cancelOccupancy(
                new OccupancyCancelRequest("oc_ghost", "req-" + rid("cancel"))));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    // ============================ 占用失败分支 ============================

    @Test
    @DisplayName("占用：STALE 422（空域或航线版本变化），BLOCKED 409，资源缺失 404")
    void occupancyRejectsStaleBlockedAndMissing() {
        createZone("z1", 40, 5, 60, 15, new AltitudeBandDto("b1", 100, 200, 1));
        createRoute("r1", 150, 1000, 2000, 0, 1000, 100, 1000);
        ReviewResultDto rev1 = review("r1", 1, 1);
        assertEquals("CLEAR", rev1.conclusion());

        // 空域版本变化（新建区域）→ 关联审查 STALE → 422
        createZone("z2", 400, 5, 600, 15);
        ApiException staleByAirspace = assertThrows(ApiException.class,
                () -> occupy(rev1.reviewId(), "z1", "b1"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, staleByAirspace.status());
        assertEquals("REVIEW_STALE", staleByAirspace.code());

        // 用当前版本重新审查 → CLEAR；随后航线替换 → STALE → 422
        ReviewResultDto rev2 = review("r1", 1, 2);
        assertEquals("CLEAR", rev2.conclusion());
        service.replaceRoute(new RouteReplaceRequest("r1", 1, pts(0, 1000, 100, 1000),
                150, 1000L, 2000L, "req-" + rid("replace")));
        ApiException staleByRoute = assertThrows(ApiException.class,
                () -> occupy(rev2.reviewId(), "z1", "b1"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, staleByRoute.status());

        // BLOCKED 审查 → 409
        createRoute("rb", 150, 1000, 2000, 0, 10, 100, 10);
        ReviewResultDto blocked = review("rb", 1, 2);
        assertEquals("BLOCKED", blocked.conclusion());
        ApiException notClear = assertThrows(ApiException.class,
                () -> occupy(blocked.reviewId(), "z1", "b1"));
        assertEquals(HttpStatus.CONFLICT, notClear.status());
        assertEquals("REVIEW_NOT_CLEAR", notClear.code());

        // 审核不存在 → 404；区域不存在 → 404；高度带不存在 → 404
        ReviewResultDto rev3 = review("r1", 2, 2);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> occupy("rv_ghost", "z1", "b1")).status());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> occupy(rev3.reviewId(), "ghost", "b1")).status());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> occupy(rev3.reviewId(), "z1", "ghost")).status());
    }

    @Test
    @DisplayName("占用：已撤销区域 409")
    void occupancyOnRevokedZoneRejected() {
        createZone("z1", 40, 5, 60, 15, new AltitudeBandDto("b1", 100, 200, 1));
        createRoute("r1", 150, 1000, 2000, 0, 1000, 100, 1000);
        service.revokeZone(new ZoneRevokeRequest("z1", "req-" + rid("revoke")));
        // 撤销后重新审查（空域版本 2）：区域已不参与审查 → CLEAR
        ReviewResultDto rev = review("r1", 1, 2);
        assertEquals("CLEAR", rev.conclusion());
        ApiException ex = assertThrows(ApiException.class,
                () -> occupy(rev.reviewId(), "z1", "b1"));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("ZONE_ALREADY_REVOKED", ex.code());
    }

    // ============================ 高度带配置修改 ============================

    @Test
    @DisplayName("高度带修改：只允许上调容量或新增不重叠带，版本冲突 409，不推进空域版本")
    void bandModifyRules() {
        createZone("z1", 40, 5, 60, 15, new AltitudeBandDto("b1", 100, 200, 1));
        long versionBefore = airspaceVersion();

        // 上调容量 → 配置版本 1 → 2，全局空域版本不变
        MutationResponse resp = service.modifyZoneBands(new ZoneBandsModifyRequest(
                "z1", 1, null, List.of(new BandCapacityUpdateDto("b1", 3)),
                "req-" + rid("mod")));
        ZoneBandsResult result = objectMapper.convertValue(resp.data(), ZoneBandsResult.class);
        assertEquals(2, result.configVersion());
        assertEquals(3, result.bands().get(0).capacity());
        assertEquals(versionBefore, airspaceVersion());

        // 下调或持平 → 400
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> service.modifyZoneBands(new ZoneBandsModifyRequest("z1", 2, null,
                        List.of(new BandCapacityUpdateDto("b1", 2)), "req-" + rid("mod"))))
                .status());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> service.modifyZoneBands(new ZoneBandsModifyRequest("z1", 2, null,
                        List.of(new BandCapacityUpdateDto("b1", 3)), "req-" + rid("mod"))))
                .status());

        // 新增不重叠带（端点相接）→ 配置版本 3
        MutationResponse added = service.modifyZoneBands(new ZoneBandsModifyRequest(
                "z1", 2, List.of(new AltitudeBandDto("b2", 200, 300, 2)), null,
                "req-" + rid("mod")));
        assertEquals(3, objectMapper.convertValue(added.data(), ZoneBandsResult.class)
                .configVersion());

        // 新增重叠带 → 409；标识重复 → 409
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> service.modifyZoneBands(new ZoneBandsModifyRequest("z1", 3,
                        List.of(new AltitudeBandDto("b3", 150, 250, 1)), null,
                        "req-" + rid("mod")))).status());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> service.modifyZoneBands(new ZoneBandsModifyRequest("z1", 3,
                        List.of(new AltitudeBandDto("b1", 400, 500, 1)), null,
                        "req-" + rid("mod")))).status());

        // 配置版本不匹配 → 409；区域不存在 → 404；空调修改 → 400；上调目标不存在 → 404
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> service.modifyZoneBands(new ZoneBandsModifyRequest("z1", 1,
                        List.of(new AltitudeBandDto("b9", 400, 500, 1)), null,
                        "req-" + rid("mod")))).status());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> service.modifyZoneBands(new ZoneBandsModifyRequest("ghost", 1,
                        List.of(new AltitudeBandDto("b9", 400, 500, 1)), null,
                        "req-" + rid("mod")))).status());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> service.modifyZoneBands(new ZoneBandsModifyRequest("z1", 3,
                        null, null, "req-" + rid("mod")))).status());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> service.modifyZoneBands(new ZoneBandsModifyRequest("z1", 3, null,
                        List.of(new BandCapacityUpdateDto("ghost", 5)), "req-" + rid("mod"))))
                .status());

        // 配置查询与修改结果一致
        ZoneBandsResult config = service.getZoneBands("z1");
        assertEquals(3, config.configVersion());
        assertEquals(2, config.bands().size());
        assertEquals(versionBefore, airspaceVersion());
    }

    @Test
    @DisplayName("高度带修改不使审查 STALE，也不追溯改写已有占用；上调后容量放行")
    void bandModifyKeepsReviewValidAndOccupancyUntouched() {
        createZone("z1", 40, 5, 60, 15, new AltitudeBandDto("b1", 100, 200, 1));
        // 两条航线均在加带前完成 CLEAR 审查（此时区域只有 b1）
        createRoute("r1", 150, 1000, 2000, 0, 1000, 100, 1000);
        createRoute("r2", 150, 1000, 2000, 0, 2000, 100, 2000);
        ReviewResultDto rev1 = review("r1", 1, 1);
        ReviewResultDto rev2 = review("r2", 1, 1);

        // r1 占用成功（消耗容量 1/1）；r2 容量满 429
        OccupancyResult occ1 = occData(occupy(rev1.reviewId(), "z1", "b1"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, assertThrows(ApiException.class,
                () -> occupy(rev2.reviewId(), "z1", "b1")).status());

        // 上调容量 1→2 并新增高度带：审查不失效（仍可创建占用），已有占用不改写
        service.modifyZoneBands(new ZoneBandsModifyRequest("z1", 1,
                List.of(new AltitudeBandDto("b2", 200, 300, 2)),
                List.of(new BandCapacityUpdateDto("b1", 2)), "req-" + rid("mod")));
        assertEquals(1, airspaceVersion());

        OccupancyResult occ2 = occData(occupy(rev2.reviewId(), "z1", "b1"));
        assertTrue(occ2.consumes());

        // 已有占用保持 ACTIVE 且快照不变
        OccupancyListResult list = service.getOccupancies("z1", "b1", 0L, 3000L);
        assertEquals(2, list.occupancies().size());
        assertEquals("ACTIVE", list.occupancies().get(0).status());
        assertEquals(occ1.occupancyId(), list.occupancies().get(0).occupancyId());
    }

    // ============================ 幂等 ============================

    @Test
    @DisplayName("幂等：占用同键同参重放，异参 409，429 失败不占键")
    void occupancyIdempotency() {
        createZone("z1", 40, 5, 60, 15, new AltitudeBandDto("b1", 100, 200, 1));
        createRoute("r1", 150, 1000, 2000, 0, 1000, 100, 1000);
        createRoute("r2", 150, 1000, 2000, 0, 2000, 100, 2000);
        ReviewResultDto rev1 = review("r1", 1, 1);
        ReviewResultDto rev2 = review("r2", 1, 1);

        // 同键同参重放：返回首次结果，不重复创建
        String key = "occ-key-1";
        MutationResponse first = service.createOccupancy(
                new OccupancyCreateRequest(rev1.reviewId(), "z1", "b1", key));
        MutationResponse replay = service.createOccupancy(
                new OccupancyCreateRequest(rev1.reviewId(), "z1", "b1", key));
        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(occData(first).occupancyId(), occData(replay).occupancyId());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM altitude_occupancy", Integer.class));

        // 同键异参 → 409
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> service.createOccupancy(
                        new OccupancyCreateRequest(rev2.reviewId(), "z1", "b1", key))).status());

        // 429 失败不占键：取消占用后同键重试成功
        String failKey = "occ-key-2";
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, assertThrows(ApiException.class,
                () -> service.createOccupancy(
                        new OccupancyCreateRequest(rev2.reviewId(), "z1", "b1", failKey)))
                .status());
        assertNull(jdbc.queryForList(
                "SELECT request_id FROM request_dedup WHERE request_id = ?", failKey)
                .stream().findFirst().orElse(null));
        service.cancelOccupancy(new OccupancyCancelRequest(
                occData(first).occupancyId(), "req-" + rid("cancel")));
        MutationResponse retried = service.createOccupancy(
                new OccupancyCreateRequest(rev2.reviewId(), "z1", "b1", failKey));
        assertFalse(retried.replayed());
        assertEquals("ACTIVE", occData(retried).status());
    }

    @Test
    @DisplayName("幂等：高度带修改同键重放不重复推进配置版本")
    void bandModifyIdempotentReplay() {
        createZone("z1", 40, 5, 60, 15, new AltitudeBandDto("b1", 100, 200, 1));
        String key = "mod-key-1";
        ZoneBandsModifyRequest req = new ZoneBandsModifyRequest("z1", 1, null,
                List.of(new BandCapacityUpdateDto("b1", 5)), key);
        MutationResponse first = service.modifyZoneBands(req);
        MutationResponse replay = service.modifyZoneBands(req);
        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(2, service.getZoneBands("z1").configVersion());
        assertEquals(5, service.getZoneBands("z1").bands().get(0).capacity());
    }

    // ============================ 查询 ============================

    @Test
    @DisplayName("按时段占用查询：时段与高度带过滤，含取消历史，非法时段 400")
    void occupancyQueryFiltersByTimeAndBand() {
        createZone("z1", 40, 5, 60, 15,
                new AltitudeBandDto("b1", 100, 200, 5),
                new AltitudeBandDto("b2", 200, 300, 5));
        createRoute("r1", 150, 1000, 2000, 0, 1000, 100, 1000);
        createRoute("r2", 250, 3000, 4000, 0, 2000, 100, 2000);
        ReviewResultDto rev1 = review("r1", 1, 1);
        ReviewResultDto rev2 = review("r2", 1, 1);
        OccupancyResult occ1 = occData(occupy(rev1.reviewId(), "z1", "b1"));
        occData(occupy(rev2.reviewId(), "z1", "b2"));
        service.cancelOccupancy(new OccupancyCancelRequest(
                occ1.occupancyId(), "req-" + rid("cancel")));

        // 全时段全高度带：两条（含已取消）
        assertEquals(2, service.getOccupancies("z1", null, 0L, 10000L).occupancies().size());
        // 时段过滤：[0,1500) 只命中 r1
        OccupancyListResult early = service.getOccupancies("z1", null, 0L, 1500L);
        assertEquals(1, early.occupancies().size());
        assertEquals("CANCELLED", early.occupancies().get(0).status());
        // 时段过滤：[2500,5000) 只命中 r2
        assertEquals(1, service.getOccupancies("z1", null, 2500L, 5000L).occupancies().size());
        // 高度带过滤
        assertEquals(1, service.getOccupancies("z1", "b2", 0L, 10000L).occupancies().size());
        assertEquals(0, service.getOccupancies("z1", "b1", 2500L, 5000L).occupancies().size());

        // 非法时段 → 400；区域不存在 → 404；高度带不存在 → 404
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> service.getOccupancies("z1", null, 2000L, 1000L)).status());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> service.getOccupancies("ghost", null, 0L, 1000L)).status());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> service.getOccupancies("z1", "ghost", 0L, 1000L)).status());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> service.getZoneBands("ghost")).status());
    }

    @Test
    @DisplayName("垂直分离审查明细：逐高度带相交标记与垂直间隔")
    void verticalSeparationDetail() {
        createZone("z1", 40, 5, 60, 15,
                new AltitudeBandDto("b1", 100, 200, 3),
                new AltitudeBandDto("b2", 300, 400, 2));
        createZone("z2", 40, 105, 60, 115); // 无高度带

        // 巡航高度 250：二维相交 z1 但高度在带外 → CLEAR；明细给出垂直间隔
        createRoute("r1", 250, 1000, 2000, 0, 10, 100, 10);
        ReviewResultDto clear = review("r1", 1, 2);
        assertEquals("CLEAR", clear.conclusion());
        VerticalSeparationResult detail = service.getVerticalSeparation(clear.reviewId());
        assertEquals(250, detail.cruiseAltitudeM());
        assertEquals(1, detail.zones().size());
        VerticalSeparationResult.ZoneVerticalDetail zone = detail.zones().get(0);
        assertEquals("z1", zone.zoneId());
        assertFalse(zone.blocksRoute());
        assertEquals(2, zone.bands().size());
        assertFalse(zone.bands().get(0).altitudeIntersects());
        assertEquals(50, zone.bands().get(0).verticalSeparationM()); // 250 - 200
        assertFalse(zone.bands().get(1).altitudeIntersects());
        assertEquals(50, zone.bands().get(1).verticalSeparationM()); // 300 - 250

        // 巡航高度 150：落入 b1 → BLOCKED；明细标记相交、间隔 0
        createRoute("r2", 150, 1000, 2000, 0, 10, 100, 10);
        ReviewResultDto blocked = review("r2", 1, 2);
        assertEquals("BLOCKED", blocked.conclusion());
        VerticalSeparationResult blockedDetail =
                service.getVerticalSeparation(blocked.reviewId());
        assertTrue(blockedDetail.zones().get(0).blocksRoute());
        assertTrue(blockedDetail.zones().get(0).bands().get(0).altitudeIntersects());
        assertEquals(0, blockedDetail.zones().get(0).bands().get(0).verticalSeparationM());

        // 无高度带区域：二维相交即拦截，明细 bands 为空
        createRoute("r3", 150, 1000, 2000, 0, 110, 100, 110);
        ReviewResultDto legacy = review("r3", 1, 2);
        VerticalSeparationResult legacyDetail =
                service.getVerticalSeparation(legacy.reviewId());
        assertTrue(legacyDetail.zones().get(0).blocksRoute());
        assertTrue(legacyDetail.zones().get(0).bands().isEmpty());

        // 审核不存在 → 404
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> service.getVerticalSeparation("rv_ghost")).status());
    }

    // ============================ 并发 ============================

    @Test
    @DisplayName("并发占用：容量 1 时 6 线程仅 1 成功，其余 429，不超卖")
    void concurrentOccupancyNeverOversells() throws Exception {
        createZone("z1", 40, 5, 60, 15, new AltitudeBandDto("b1", 100, 200, 1));
        int n = 6;
        List<String> reviewIds = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            String routeId = "cr" + i;
            createRoute(routeId, 150, 1000, 2000, 0, 1000 + i, 100, 1000 + i);
            reviewIds.add(review(routeId, 1, 1).reviewId());
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier barrier = new CyclicBarrier(n);
        try {
            List<Future<Object>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                String reviewId = reviewIds.get(i);
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.createOccupancy(new OccupancyCreateRequest(
                                reviewId, "z1", "b1", "req-conc-" + reviewId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                }));
            }
            int success = 0;
            int capacityExceeded = 0;
            for (Future<Object> f : futures) {
                Object result = f.get(20, TimeUnit.SECONDS);
                if (result instanceof MutationResponse) {
                    success++;
                } else {
                    ApiException ex = (ApiException) result;
                    assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.status());
                    capacityExceeded++;
                }
            }
            assertEquals(1, success, "容量 1 时只能有一个占用成功");
            assertEquals(n - 1, capacityExceeded);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM altitude_occupancy WHERE status = 'ACTIVE'",
                    Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发高度带修改：相同 expectedVersion 仅一个成功，其余 409")
    void concurrentBandModifySameVersionConflicts() throws Exception {
        createZone("z1", 40, 5, 60, 15, new AltitudeBandDto("b1", 100, 200, 1));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Future<Object>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int cap = 10 + i;
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.modifyZoneBands(new ZoneBandsModifyRequest("z1", 1,
                                null, List.of(new BandCapacityUpdateDto("b1", cap)),
                                "req-mod-" + cap));
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
            assertEquals(1, success);
            assertEquals(1, conflict);
            assertEquals(2, service.getZoneBands("z1").configVersion());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发取消与创建：按提交顺序裁决，容量不超卖")
    void concurrentCancelAndCreateSerialized() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 6; i++) {
                final int iter = i;
                cleanup();
                createZone("z1", 40, 5, 60, 15, new AltitudeBandDto("b1", 100, 200, 1));
                createRoute("ra", 150, 1000, 2000, 0, 1000, 100, 1000);
                createRoute("rb", 150, 1000, 2000, 0, 2000, 100, 2000);
                ReviewResultDto revA = review("ra", 1, 1);
                ReviewResultDto revB = review("rb", 1, 1);
                OccupancyResult occA = occData(occupy(revA.reviewId(), "z1", "b1"));

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Object> cancelFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.cancelOccupancy(new OccupancyCancelRequest(
                                occA.occupancyId(), "req-cancel-" + iter));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> createFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.createOccupancy(new OccupancyCreateRequest(
                                revB.reviewId(), "z1", "b1", "req-create-" + iter));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object cancelResult = cancelFuture.get(20, TimeUnit.SECONDS);
                Object createResult = createFuture.get(20, TimeUnit.SECONDS);
                // 取消必须成功
                assertTrue(cancelResult instanceof MutationResponse);
                if (createResult instanceof MutationResponse) {
                    // 取消先提交：新占用成功，最终恰好 1 个 ACTIVE
                    assertEquals(1, jdbc.queryForObject(
                            "SELECT COUNT(*) FROM altitude_occupancy WHERE status = 'ACTIVE'",
                            Integer.class));
                } else {
                    // 创建先提交：观察到容量仍满 → 429；取消后无 ACTIVE
                    assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                            ((ApiException) createResult).status());
                    assertEquals(0, jdbc.queryForObject(
                            "SELECT COUNT(*) FROM altitude_occupancy WHERE status = 'ACTIVE'",
                            Integer.class));
                }
                // 历史保留：两轮操作共 1（A）或 2（A、B）条记录
                assertEquals("CANCELLED", jdbc.queryForObject(
                        "SELECT status FROM altitude_occupancy WHERE occupancy_id = ?",
                        String.class, occA.occupancyId()));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
