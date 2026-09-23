package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.FlightReviewRequest;
import com.example.starter.api.dto.FlightReviewResultDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.PermitIssueRequest;
import com.example.starter.api.dto.PermitItemRequest;
import com.example.starter.api.dto.PermitItemResult;
import com.example.starter.api.dto.PermitRedeemDto;
import com.example.starter.api.dto.PermitResult;
import com.example.starter.api.dto.PermitRevokeRequest;
import com.example.starter.api.dto.RedeemResultDto;
import com.example.starter.api.dto.RegionDefectDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.ZoneCreateRequest;
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
 * 多区域豁免包签发/撤销/核销与飞行审核的 H2 数据库测试（MODE=MySQL）。
 * 覆盖主流程、缺陷分支（缺失/版本不匹配/撤销/过期/耗尽）、整事务回滚不扣额度、
 * requestId/flightKey 幂等边界、撤销语义、只读历史与真实并发互斥，不使用 mock。
 * 时间相关断言全部使用请求显式传入的 reviewAt 与签发的绝对时间窗，不依赖系统时钟。
 */
@SpringBootTest
@DisplayName("豁免包配额原子核销 H2 业务测试")
class ExemptionServiceH2Test {

    static final long NOW = 1_700_000_000_000L;
    static final long FROM = NOW - 3_600_000L;
    static final long TO = NOW + 3_600_000L;

    @Autowired
    private com.example.starter.service.ExemptionService service;
    @Autowired
    private com.example.starter.service.AirspaceReviewService airspaceService;
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
        jdbc.update("DELETE FROM flight_review");
        jdbc.update("DELETE FROM permit_redeem");
        jdbc.update("DELETE FROM permit_item");
        jdbc.update("DELETE FROM permit");
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

    /** 创建水平穿过指定 y 的航线，返回 routeId。 */
    private String createRoute(String routeId, int y) {
        airspaceService.createRoute(new RouteCreateRequest(
                routeId, pts(0, y, 100, y), "req-" + uniq("route")));
        return routeId;
    }

    /** 在指定 y 带（y-5..y+5）与 x 区间创建与该 y 航线相交的禁飞区，返回其创建生效空域版本。 */
    private long createZoneAt(String zoneId, int y, int xMin, int xMax) {
        airspaceService.createZone(new ZoneCreateRequest(
                zoneId, xMin, y - 5, xMax, y + 5, "req-" + uniq("zone")));
        Long createdVersion = jdbc.queryForObject(
                "SELECT created_version FROM no_fly_zone WHERE zone_id = ?",
                Long.class, zoneId);
        return createdVersion == null ? -1L : createdVersion;
    }

    /** 在指定 y 带创建标准 x=40..60 的相交禁飞区。 */
    private long createHitZone(String zoneId, int y) {
        return createZoneAt(zoneId, y, 40, 60);
    }

    private PermitItemRequest item(String regionKey, long regionVersion, int quota) {
        return new PermitItemRequest(regionKey, regionVersion, FROM, TO, quota);
    }

    private MutationResponse issue(String permitKey, String routeId, int routeVersion,
                                   PermitItemRequest... items) {
        return service.issuePermit(new PermitIssueRequest(permitKey, routeId, routeVersion,
                List.of(items), "req-" + uniq("issue")));
    }

    private FlightReviewResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), FlightReviewResultDto.class);
    }

    private MutationResponse review(String flightKey, String routeId, long reviewAt) {
        return service.flightReview(new FlightReviewRequest(
                flightKey, routeId, reviewAt, "req-" + uniq("flight")));
    }

    private int remaining(String permitKey, String regionKey) {
        Integer value = jdbc.queryForObject(
                "SELECT remaining FROM permit_item WHERE permit_id = ? AND region_key = ?",
                Integer.class, permitKey, regionKey);
        return value == null ? -1 : value;
    }

    private int count(String table) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private RegionDefectDto defectOf(FlightReviewResultDto dto, String regionKey) {
        return dto.defects().stream()
                .filter(d -> d.regionKey().equals(regionKey))
                .findFirst().orElseThrow();
    }

    // ============================ 主流程 ============================

    @Test
    @DisplayName("命中区域被同一豁免包覆盖：CLEAR 且每项扣 1，快照冻结核销前后余额")
    void clearRedeemsOnePerHitRegionWithSnapshot() {
        String routeId = createRoute("r-main", 100);
        long v1 = createHitZone("z-one", 100);
        issue("p-main", routeId, 1, item("z-one", v1, 2));

        MutationResponse resp = review("f-1", routeId, NOW);
        assertFalse(resp.replayed());
        FlightReviewResultDto dto = dataOf(resp);
        assertEquals("CLEAR", dto.conclusion());
        assertEquals(List.of("z-one"), dto.hitRegionKeys());
        assertEquals("p-main", dto.permitKey());
        assertEquals(1, dto.permitVersion());
        assertEquals(1, dto.redeems().size());
        RedeemResultDto redeem = dto.redeems().get(0);
        assertEquals("z-one", redeem.regionKey());
        assertEquals(2, redeem.balanceBefore());
        assertEquals(1, redeem.balanceAfter());
        // 冻结空域/航线/permit 版本与航点快照
        assertEquals(1, dto.routeVersion());
        assertEquals(v1, dto.airspaceVersion());
        assertEquals(2, dto.pointsSnapshot().size());
        // 余额与流水落库
        assertEquals(1, remaining("p-main", "z-one"));
        List<PermitRedeemDto> redeems = service.getRedeems("p-main");
        assertEquals(1, redeems.size());
        assertEquals("f-1", redeems.get(0).flightKey());
        assertEquals(2, redeems.get(0).balanceBefore());
        assertEquals(1, redeems.get(0).balanceAfter());
    }

    @Test
    @DisplayName("多命中区域由同一豁免包全部覆盖时一次事务逐项扣 1")
    void multipleHitsCoveredBySamePermitRedeemAllAtomically() {
        String routeId = createRoute("r-multi", 200);
        long za = createZoneAt("z-a", 200, 40, 50);
        long zb = createZoneAt("z-b", 200, 70, 80);
        issue("p-multi", routeId, 1, item("z-a", za, 3), item("z-b", zb, 1));

        FlightReviewResultDto dto = dataOf(review("f-multi", routeId, NOW));
        assertEquals("CLEAR", dto.conclusion());
        assertEquals(List.of("z-a", "z-b"), dto.hitRegionKeys());
        assertEquals(2, dto.redeems().size());
        assertEquals(2, remaining("p-multi", "z-a"));
        assertEquals(0, remaining("p-multi", "z-b"));
        assertEquals(2, count("permit_redeem"));
    }

    @Test
    @DisplayName("未命中任何区域：CLEAR 但不消费任何豁免额度")
    void noHitDoesNotConsumeQuota() {
        String routeId = createRoute("r-empty", 300);
        // 航线 y=300，禁飞区在远处不相交
        createZoneAt("z-far", 800, -90, -80);
        long farVersion = jdbc.queryForObject(
                "SELECT created_version FROM no_fly_zone WHERE zone_id = 'z-far'", Long.class);
        issue("p-empty", routeId, 1, item("z-far", farVersion, 5));

        FlightReviewResultDto dto = dataOf(review("f-empty", routeId, NOW));
        assertEquals("CLEAR", dto.conclusion());
        assertTrue(dto.hitRegionKeys().isEmpty());
        assertNull(dto.permitKey());
        assertTrue(dto.redeems().isEmpty());
        // 未命中区域项不消费
        assertEquals(5, remaining("p-empty", "z-far"));
        assertEquals(0, count("permit_redeem"));
    }

    // ============================ BLOCKED 缺陷分支与整体回滚 ============================

    @Test
    @DisplayName("缺项/版本不匹配/过期/耗尽/撤销均 BLOCKED 且不扣任何额度")
    void allDefectKindsBlockWithoutDeducting() {
        // 1. 缺失项：豁免包没有该区域
        String r1 = createRoute("r-missing", 400);
        long zm = createHitZone("z-missing", 400);
        issue("p-missing", r1, 1, item("z-other", zm, 5));
        FlightReviewResultDto missing = dataOf(review("f-missing", r1, NOW));
        assertEquals("BLOCKED", missing.conclusion());
        assertEquals("MISSING", defectOf(missing, "z-missing").reason());
        assertEquals(0, count("permit_redeem"));
        assertEquals(5, remaining("p-missing", "z-other"));

        // 2. 版本不匹配
        String r2 = createRoute("r-version", 500);
        long zv = createHitZone("z-ver", 500);
        issue("p-ver", r2, 1, item("z-ver", zv + 100, 5));
        FlightReviewResultDto mismatch = dataOf(review("f-ver", r2, NOW));
        assertEquals("BLOCKED", mismatch.conclusion());
        assertEquals("VERSION_MISMATCH", defectOf(mismatch, "z-ver").reason());
        assertEquals(5, remaining("p-ver", "z-ver"));
        assertEquals(0, count("permit_redeem"));

        // 3. 过期：reviewAt 早于有效起点
        String r3 = createRoute("r-expired", 600);
        long ze = createHitZone("z-exp", 600);
        issue("p-exp", r3, 1, item("z-exp", ze, 5));
        FlightReviewResultDto expired = dataOf(review("f-exp", r3, FROM - 1));
        assertEquals("EXPIRED", defectOf(expired, "z-exp").reason());
        assertEquals(5, remaining("p-exp", "z-exp"));
        // 晚于有效终点同样过期
        FlightReviewResultDto expiredAfter = dataOf(review("f-exp2", r3, TO + 1));
        assertEquals("EXPIRED", defectOf(expiredAfter, "z-exp").reason());
        assertEquals(0, count("permit_redeem"));

        // 4. 耗尽：先核销一次用尽额度
        String r4 = createRoute("r-exhaust", 700);
        long zx = createHitZone("z-exh", 700);
        issue("p-exh", r4, 1, item("z-exh", zx, 1));
        assertEquals("CLEAR", dataOf(review("f-exh-1", r4, NOW)).conclusion());
        assertEquals(0, remaining("p-exh", "z-exh"));
        FlightReviewResultDto exhausted = dataOf(review("f-exh-2", r4, NOW));
        assertEquals("BLOCKED", exhausted.conclusion());
        assertEquals("EXHAUSTED", defectOf(exhausted, "z-exh").reason());
        // 耗尽后不再产生流水
        assertEquals(1, count("permit_redeem"));

        // 5. 撤销：撤销未使用余额后审核 BLOCKED
        String r5 = createRoute("r-revoked", 900);
        long zr = createHitZone("z-rev", 900);
        issue("p-rev", r5, 1, item("z-rev", zr, 5));
        service.revokePermit(new PermitRevokeRequest("p-rev", "req-" + uniq("revoke")));
        FlightReviewResultDto revoked = dataOf(review("f-rev", r5, NOW));
        assertEquals("BLOCKED", revoked.conclusion());
        assertEquals("REVOKED", defectOf(revoked, "z-rev").reason());
        assertEquals(5, remaining("p-rev", "z-rev"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM permit_redeem WHERE permit_id = 'p-rev'", Integer.class).intValue());

        // BLOCKED 是正常落库的评估快照（finalized=false），且占用其 requestId：
        // 缺失 1 + 版本不匹配 1 + 过期早/晚 2 + 耗尽 1 + 撤销 1 = 6
        assertEquals(6, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flight_review WHERE conclusion = 'BLOCKED'", Integer.class).intValue());
    }

    @Test
    @DisplayName("两个豁免包各覆盖一个命中区域时 BLOCKED，且两边额度整体回滚都不扣")
    void noSinglePermitCoversAllHitsRollsBackEverything() {
        String routeId = createRoute("r-split", 1000);
        long za = createZoneAt("z-s1", 1000, 40, 50);
        long zb = createZoneAt("z-s2", 1000, 70, 80);
        issue("p-a", routeId, 1, item("z-s1", za, 5));
        issue("p-b", routeId, 1, item("z-s2", zb, 5));

        FlightReviewResultDto dto = dataOf(review("f-split", routeId, NOW));
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(List.of("z-s1", "z-s2"), dto.hitRegionKeys());
        // 整事务回滚：任何额度都不扣、无流水
        assertEquals(5, remaining("p-a", "z-s1"));
        assertEquals(5, remaining("p-b", "z-s2"));
        assertEquals(0, count("permit_redeem"));
        assertNull(dto.permitKey());
    }

    @Test
    @DisplayName("多命中中一项过期导致 BLOCKED 时，可核销项也不扣额度")
    void oneDefectAmongHitsRollsBackAllDeductions() {
        String routeId = createRoute("r-partial", 1100);
        long za = createZoneAt("z-p1", 1100, 40, 50);
        long zb = createZoneAt("z-p2", 1100, 70, 80);
        // z-p1 有效期覆盖 NOW，z-p2 已过期
        PermitItemRequest good = item("z-p1", za, 5);
        PermitItemRequest bad = new PermitItemRequest("z-p2", zb, FROM - 10, FROM - 1, 5);
        issue("p-partial", routeId, 1, good, bad);

        FlightReviewResultDto dto = dataOf(review("f-partial", routeId, NOW));
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals("EXPIRED", defectOf(dto, "z-p2").reason());
        assertEquals(5, remaining("p-partial", "z-p1"));
        assertEquals(5, remaining("p-partial", "z-p2"));
        assertEquals(0, count("permit_redeem"));
    }

    // ============================ 幂等与 flightKey ============================

    @Test
    @DisplayName("requestId 同参重放返回原审核且不重复扣额，异参/异种操作 409")
    void sameRequestIdReplaysWithoutDoubleRedeem() {
        String routeId = createRoute("r-idem", 1200);
        long zv = createHitZone("z-idem", 1200);
        issue("p-idem", routeId, 1, item("z-idem", zv, 5));

        String requestId = "req-fixed-flight-1";
        MutationResponse first = service.flightReview(new FlightReviewRequest(
                "fk-idem", routeId, NOW, requestId));
        String reviewId = dataOf(first).reviewId();
        assertEquals(4, remaining("p-idem", "z-idem"));

        MutationResponse replay = service.flightReview(new FlightReviewRequest(
                "fk-idem", routeId, NOW, requestId));
        assertTrue(replay.replayed());
        assertEquals(reviewId, dataOf(replay).reviewId());
        assertEquals(4, remaining("p-idem", "z-idem"));
        assertEquals(1, count("permit_redeem"));

        // 同键异参（reviewAt 不同）→ 409
        ApiException diffParams = assertThrows(ApiException.class, () -> service.flightReview(
                new FlightReviewRequest("fk-idem", routeId, NOW + 1, requestId)));
        assertEquals(HttpStatus.CONFLICT, diffParams.status());
        // 同 requestId 用于异种操作 → 409
        ApiException diffKind = assertThrows(ApiException.class, () -> service.revokePermit(
                new PermitRevokeRequest("p-idem", requestId)));
        assertEquals(HttpStatus.CONFLICT, diffKind.status());
        assertEquals(4, remaining("p-idem", "z-idem"));
    }

    @Test
    @DisplayName("同 flightKey 的 CLEAR 只形成一次：新 requestId 同参仍重放不扣，异参 409")
    void finalizedFlightKeyReplaysAcrossRequestIds() {
        String routeId = createRoute("r-fk", 1300);
        long zv = createHitZone("z-fk", 1300);
        issue("p-fk", routeId, 1, item("z-fk", zv, 5));

        FlightReviewResultDto first = dataOf(service.flightReview(new FlightReviewRequest(
                "fk-only", routeId, NOW, "req-fk-1")));
        assertEquals("CLEAR", first.conclusion());
        assertEquals(4, remaining("p-fk", "z-fk"));

        // 新 requestId、同 flightKey 同参：重放首次快照，不新增核销
        MutationResponse again = service.flightReview(new FlightReviewRequest(
                "fk-only", routeId, NOW, "req-fk-2"));
        assertTrue(again.replayed());
        assertEquals(first.reviewId(), dataOf(again).reviewId());
        assertEquals(4, remaining("p-fk", "z-fk"));
        assertEquals(1, count("permit_redeem"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flight_review WHERE final_key = 'fk-only'", Integer.class).intValue());

        // 同 flightKey 异参（不同 reviewAt）→ 409
        ApiException diff = assertThrows(ApiException.class, () -> service.flightReview(
                new FlightReviewRequest("fk-only", routeId, TO, "req-fk-3")));
        assertEquals(HttpStatus.CONFLICT, diff.status());
        assertEquals("FLIGHT_ALREADY_REVIEWED", diff.code());
    }

    @Test
    @DisplayName("BLOCKED 后修正条件可用同 flightKey 复用，最终形成一次 CLEAR")
    void blockedCanBeFixedAndReusedWithSameFlightKey() {
        String routeId = createRoute("r-fix", 1400);
        long zv = createHitZone("z-fix", 1400);

        // 尚无豁免包 → BLOCKED 快照落库但不锁 flightKey
        FlightReviewResultDto blocked = dataOf(review("fk-fix", routeId, NOW));
        assertEquals("BLOCKED", blocked.conclusion());
        assertEquals(0, count("permit_redeem"));

        // 审批人补发覆盖该区域的有效豁免包后，同 flightKey 新 requestId 再审核 → CLEAR
        issue("p-fix", routeId, 1, item("z-fix", zv, 3));
        MutationResponse cleared = service.flightReview(new FlightReviewRequest(
                "fk-fix", routeId, NOW, "req-fix-after"));
        assertFalse(cleared.replayed());
        FlightReviewResultDto clearDto = dataOf(cleared);
        assertEquals("CLEAR", clearDto.conclusion());
        assertEquals("p-fix", clearDto.permitKey());
        assertEquals(2, remaining("p-fix", "z-fix"));

        // 历史包含 BLOCKED 与 CLEAR 两条快照，时间正序，最终审核唯一
        List<FlightReviewResultDto> history = service.getFlightHistory("fk-fix");
        assertEquals(2, history.size());
        assertEquals("BLOCKED", history.get(0).conclusion());
        assertEquals("CLEAR", history.get(1).conclusion());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flight_review WHERE final_key = 'fk-fix'", Integer.class).intValue());
        // CLEAR 之后同 flightKey 已锁定：同参（新 requestId）只重放，不重复扣额
        MutationResponse replayedAgain = service.flightReview(new FlightReviewRequest(
                "fk-fix", routeId, NOW, "req-fix-replay"));
        assertTrue(replayedAgain.replayed());
        assertEquals(clearDto.reviewId(), dataOf(replayedAgain).reviewId());
        assertEquals(2, remaining("p-fix", "z-fix"));
        // 异参（reviewAt 不同）→ 409
        ApiException again = assertThrows(ApiException.class, () -> service.flightReview(
                new FlightReviewRequest("fk-fix", routeId, TO, "req-fix-diff")));
        assertEquals(HttpStatus.CONFLICT, again.status());
        assertEquals("FLIGHT_ALREADY_REVIEWED", again.code());
        assertEquals(2, remaining("p-fix", "z-fix"));
    }

    @Test
    @DisplayName("签发失败不占用 requestId；成功后同键异参 409")
    void failedIssueDoesNotConsumeRequestId() {
        String routeId = createRoute("r-fail", 1500);
        String requestId = "req-fail-issue";
        // 区域项重复 → 400
        ApiException bad = assertThrows(ApiException.class, () -> service.issuePermit(
                new PermitIssueRequest("p-fail", routeId, 1,
                        List.of(item("z1", 1, 5), item("z1", 1, 5)), requestId)));
        assertEquals(HttpStatus.BAD_REQUEST, bad.status());
        assertTrue(jdbc.queryForList(
                "SELECT request_id FROM request_dedup WHERE request_id = ?", requestId).isEmpty(),
                "失败回滚不得占用 requestId");
        // 有效区间非法 → 400
        ApiException badWindow = assertThrows(ApiException.class, () -> service.issuePermit(
                new PermitIssueRequest("p-fail2", routeId, 1,
                        List.of(new PermitItemRequest("z1", 1L, TO, FROM, 5)),
                        "req-bad-window")));
        assertEquals(HttpStatus.BAD_REQUEST, badWindow.status());

        // 同键随后可成功
        long zv = createHitZone("z-issue", 1500);
        MutationResponse ok = service.issuePermit(new PermitIssueRequest(
                "p-fail", routeId, 1, List.of(item("z-issue", zv, 5)), requestId));
        assertFalse(ok.replayed());
        // permitKey 唯一：再次签发（即使新 requestId）→ 409
        ApiException dup = assertThrows(ApiException.class, () -> issue(
                "p-fail", routeId, 1, item("z-issue", zv, 5)));
        assertEquals(HttpStatus.CONFLICT, dup.status());
        assertEquals("PERMIT_ALREADY_EXISTS", dup.code());
    }

    @Test
    @DisplayName("签发绑定的航线不存在/版本不匹配分别 404/409")
    void issueValidatesRouteExistenceAndVersion() {
        ApiException noRoute = assertThrows(ApiException.class, () -> service.issuePermit(
                new PermitIssueRequest("p-noroute", "ghost", 1,
                        List.of(item("z1", 1, 5)), "req-noroute")));
        assertEquals(HttpStatus.NOT_FOUND, noRoute.status());

        createRoute("r-ver", 1600);
        ApiException wrongVersion = assertThrows(ApiException.class, () -> service.issuePermit(
                new PermitIssueRequest("p-wrongver", "r-ver", 9,
                        List.of(item("z1", 1, 5)), "req-wrongver")));
        assertEquals(HttpStatus.CONFLICT, wrongVersion.status());
        assertEquals(0, count("permit"));
    }

    // ============================ 撤销语义 ============================

    @Test
    @DisplayName("撤销不存在/重复撤销分别 404/409；撤销幂等重放；历史核销保留")
    void revokeSemanticsAndHistoryKept() {
        ApiException missing = assertThrows(ApiException.class, () -> service.revokePermit(
                new PermitRevokeRequest("ghost", "req-rev-missing")));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());

        String routeId = createRoute("r-revoke", 1700);
        long zv = createHitZone("z-revoke", 1700);
        issue("p-revoke", routeId, 1, item("z-revoke", zv, 2));
        review("fk-before-revoke", routeId, NOW);
        assertEquals(1, remaining("p-revoke", "z-revoke"));

        String revokeReq = "req-revoke-1";
        MutationResponse revoked = service.revokePermit(
                new PermitRevokeRequest("p-revoke", revokeReq));
        PermitResult result = objectMapper.convertValue(revoked.data(), PermitResult.class);
        assertEquals("REVOKED", result.status());
        assertEquals(2, result.version());

        // 同 requestId 重放
        MutationResponse replay = service.revokePermit(
                new PermitRevokeRequest("p-revoke", revokeReq));
        assertTrue(replay.replayed());
        // 新 requestId 重复撤销 → 409
        ApiException twice = assertThrows(ApiException.class, () -> service.revokePermit(
                new PermitRevokeRequest("p-revoke", "req-revoke-2")));
        assertEquals(HttpStatus.CONFLICT, twice.status());

        // 余额冻结，历史核销不回写
        assertEquals(1, remaining("p-revoke", "z-revoke"));
        List<PermitRedeemDto> redeems = service.getRedeems("p-revoke");
        assertEquals(1, redeems.size());
        assertEquals("fk-before-revoke", redeems.get(0).flightKey());
    }

    // ============================ 只读查询 ============================

    @Test
    @DisplayName("豁免余额、核销流水与审核历史只读查询，缺失资源 404")
    void readOnlyQueries() {
        String routeId = createRoute("r-query", 1800);
        long za = createZoneAt("z-q1", 1800, 40, 50);
        long zb = createZoneAt("z-q2", 1800, 70, 80);
        issue("p-query", routeId, 1, item("z-q1", za, 2), item("z-q2", zb, 2));
        review("fk-q", routeId, NOW);

        PermitResult permit = service.getPermit("p-query");
        assertEquals("ACTIVE", permit.status());
        assertEquals(2, permit.items().size());
        for (PermitItemResult itemResult : permit.items()) {
            assertEquals(1, itemResult.remaining());
            assertEquals(2, itemResult.quota());
        }
        List<PermitRedeemDto> redeems = service.getRedeems("p-query");
        assertEquals(2, redeems.size());

        List<FlightReviewResultDto> history = service.getFlightHistory("fk-q");
        assertEquals(1, history.size());
        FlightReviewResultDto fetched = service.getFlightReview(history.get(0).reviewId());
        assertEquals("CLEAR", fetched.conclusion());
        assertNotNull(fetched.reviewId());

        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class, () -> service.getPermit("ghost")).status());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class, () -> service.getRedeems("ghost")).status());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class, () -> service.getFlightReview("fr-ghost")).status());
    }

    // ============================ 并发边界 ============================

    @Test
    @DisplayName("同豁免包两个飞行审核并发：额度 1 时恰好一次 CLEAR、一次 BLOCKED，只扣一次")
    void concurrentReviewsOnSamePermitSerializeQuota() throws Exception {
        String routeId = createRoute("r-race1", 2000);
        long zv = createHitZone("z-race1", 2000);
        issue("p-race1", routeId, 1, item("z-race1", zv, 1));

        int n = 2;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String flightKey = "fk-race-" + i;
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.flightReview(new FlightReviewRequest(
                                flightKey, routeId, NOW, "req-race-" + flightKey));
                    } catch (ApiException ex) {
                        return ex;
                    }
                }));
            }
            int clear = 0;
            int blocked = 0;
            for (Future<Object> f : futures) {
                Object outcome = f.get(20, TimeUnit.SECONDS);
                assertTrue(outcome instanceof MutationResponse, "两次评估都应正常返回结果");
                String conclusion = dataOf((MutationResponse) outcome).conclusion();
                if ("CLEAR".equals(conclusion)) {
                    clear++;
                } else {
                    blocked++;
                    assertEquals("EXHAUSTED",
                            defectOf(dataOf((MutationResponse) outcome), "z-race1").reason());
                }
            }
            assertEquals(1, clear, "额度仅 1，只能有一次 CLEAR");
            assertEquals(1, blocked, "落败审核必须是 BLOCKED(EXHAUSTED) 而非异常");
            assertEquals(0, remaining("p-race1", "z-race1"));
            assertEquals(1, count("permit_redeem"));
            // 只有一次最终 CLEAR；落败者是 BLOCKED 快照
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM flight_review WHERE final_key IS NOT NULL", Integer.class).intValue());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("额度充足时同包并发审核各自扣 1，总额度扣减数恰好等于 CLEAR 数")
    void concurrentReviewsWithEnoughQuotaEachRedeemOnce() throws Exception {
        String routeId = createRoute("r-race2", 2100);
        long zv = createHitZone("z-race2", 2100);
        issue("p-race2", routeId, 1, item("z-race2", zv, 4));

        int n = 4;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String flightKey = "fk-race2-" + i;
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.flightReview(new FlightReviewRequest(
                            flightKey, routeId, NOW, "req-race2-" + flightKey));
                }));
            }
            for (Future<Object> f : futures) {
                assertEquals("CLEAR", dataOf((MutationResponse) f.get(20, TimeUnit.SECONDS)).conclusion());
            }
            assertEquals(0, remaining("p-race2", "z-race2"));
            assertEquals(4, count("permit_redeem"));
            assertEquals(4, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM flight_review WHERE conclusion='CLEAR'", Integer.class).intValue());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("核销与豁免撤销并发：结果二选一且余额/流水始终自洽，撤销后历史核销保留")
    void concurrentRedeemAndRevokeIsConsistent() throws Exception {
        String routeId = createRoute("r-race3", 2200);
        long zv = createHitZone("z-race3", 2200);
        issue("p-race3", routeId, 1, item("z-race3", zv, 3));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> reviewFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return service.flightReview(new FlightReviewRequest(
                            "fk-race3", routeId, NOW, "req-race3-review"));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            Future<Object> revokeFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return service.revokePermit(new PermitRevokeRequest(
                            "p-race3", "req-race3-revoke"));
                } catch (ApiException ex) {
                    return ex;
                }
            });

            Object reviewOutcome = reviewFuture.get(20, TimeUnit.SECONDS);
            Object revokeOutcome = revokeFuture.get(20, TimeUnit.SECONDS);
            assertTrue(revokeOutcome instanceof MutationResponse, "撤销必须成功一次");

            int redeems = count("permit_redeem");
            int remainingNow = remaining("p-race3", "z-race3");
            if (reviewOutcome instanceof MutationResponse mr
                    && "CLEAR".equals(dataOf(mr).conclusion())) {
                // 审核先拿包锁：扣 1 提交，撤销随后冻结剩余余额
                assertEquals(1, redeems);
                assertEquals(2, remainingNow, "撤销冻结的是核销后的余额");
            } else {
                // 撤销先提交：审核必须 BLOCKED(REVOKED)，一分不扣
                assertEquals("BLOCKED", dataOf((MutationResponse) reviewOutcome).conclusion());
                assertEquals("REVOKED",
                        defectOf(dataOf((MutationResponse) reviewOutcome), "z-race3").reason());
                assertEquals(0, redeems);
                assertEquals(3, remainingNow);
            }
            // 两种顺序下豁免包最终都是 REVOKED，且不会出现流水数与余额矛盾
            assertEquals("REVOKED", service.getPermit("p-race3").status());
            assertEquals(3 - redeems, remainingNow);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("核销与航线替换并发：要么基于旧版本 CLEAR，要么新版本无匹配 BLOCKED，不混合")
    void concurrentRedeemAndRouteReplaceUsesConsistentView() throws Exception {
        String routeId = createRoute("r-race4", 2300);
        long zv = createHitZone("z-race4", 2300);
        // 豁免包绑定航线版本 1
        issue("p-race4", routeId, 1, item("z-race4", zv, 3));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> reviewFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return service.flightReview(new FlightReviewRequest(
                            "fk-race4", routeId, NOW, "req-race4-review"));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            Future<Object> replaceFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    // 替换为同样穿过区域的点列，版本推进到 2；豁免包仍绑定版本 1
                    return airspaceService.replaceRoute(new RouteReplaceRequest(
                            routeId, 1, pts(0, 2300, 100, 2300), "req-race4-replace"));
                } catch (ApiException ex) {
                    return ex;
                }
            });

            Object reviewOutcome = reviewFuture.get(20, TimeUnit.SECONDS);
            Object replaceOutcome = replaceFuture.get(20, TimeUnit.SECONDS);
            assertTrue(replaceOutcome instanceof MutationResponse, "替换必须成功一次");
            FlightReviewResultDto dto = dataOf((MutationResponse) reviewOutcome);
            if (dto.routeVersion() == 1) {
                // 审核先锁航线：基于版本 1 与绑定版本 1 的豁免包 → CLEAR 扣 1
                assertEquals("CLEAR", dto.conclusion());
                assertEquals("p-race4", dto.permitKey());
                assertEquals(2, remaining("p-race4", "z-race4"));
            } else {
                // 替换先提交：审核基于版本 2，版本 1 的豁免包不匹配 → BLOCKED，不扣
                assertEquals(2, dto.routeVersion());
                assertEquals("BLOCKED", dto.conclusion());
                assertEquals("MISSING", defectOf(dto, "z-race4").reason());
                assertEquals(3, remaining("p-race4", "z-race4"));
                assertEquals(0, count("permit_redeem"));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
