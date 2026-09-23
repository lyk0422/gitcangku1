package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.ConsumedQuota;
import com.example.starter.api.dto.ConsumptionDto;
import com.example.starter.api.dto.FlightReviewRequest;
import com.example.starter.api.dto.FlightReviewResultDto;
import com.example.starter.api.dto.FlightReviewSnapshot;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.PermitIssueRequest;
import com.example.starter.api.dto.PermitItemRequest;
import com.example.starter.api.dto.PermitRevokeRequest;
import com.example.starter.api.dto.PermitView;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.service.AirspaceReviewService;
import com.example.starter.service.ExemptionService;
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
 * 多区域豁免包配额核销的 H2 数据库测试（MODE=MySQL）。
 * 覆盖签发/撤销、核销主流程、缺失/过期/耗尽/版本不匹配分支、BLOCKED 整体回滚、
 * requestId 与 flightKey 幂等边界，以及真实并发下的恰好一次扣减。
 */
@SpringBootTest
class ExemptionServiceH2Test {

    @Autowired
    private ExemptionService exemptionService;
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
        jdbc.update("DELETE FROM permit_consumption");
        jdbc.update("DELETE FROM flight_review");
        jdbc.update("DELETE FROM permit_item");
        jdbc.update("DELETE FROM permit_package");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
    }

    private String uniq(String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    private static List<RoutePointDto> pts(int... xy) {
        return java.util.stream.IntStream.range(0, xy.length / 2)
                .mapToObj(i -> new RoutePointDto(xy[2 * i], xy[2 * i + 1]))
                .toList();
    }

    private String createRoute(String routeId, int... xy) {
        airspaceService.createRoute(
                new RouteCreateRequest(routeId, pts(xy), uniq("req-route")));
        return routeId;
    }

    /** 建区并返回该区创建生效的空域版本（即区域版本）。 */
    private long createZone(String zoneId, int xMin, int yMin, int xMax, int yMax) {
        MutationResponse resp = airspaceService.createZone(new ZoneCreateRequest(
                zoneId, xMin, yMin, xMax, yMax, uniq("req-zone")));
        return ((Number) objectMapper.convertValue(resp.data(), Map.class)
                .get("airspaceVersion")).longValue();
    }

    private PermitItemRequest item(String regionKey, long regionVersion,
                                   long validFrom, long validTo, int quota) {
        return new PermitItemRequest(regionKey, regionVersion, validFrom, validTo, quota);
    }

    private PermitItemRequest covering(String regionKey, long regionVersion, int quota) {
        long now = System.currentTimeMillis();
        return item(regionKey, regionVersion, now - 3_600_000L, now + 3_600_000L, quota);
    }

    private MutationResponse issue(String permitKey, int routeVersion,
                                   PermitItemRequest... items) {
        return exemptionService.issuePermit(new PermitIssueRequest(
                permitKey, routeVersion, List.of(items), uniq("req-permit")));
    }

    private FlightReviewResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), FlightReviewResultDto.class);
    }

    private MutationResponse fly(String flightKey, String routeId, long reviewAt) {
        return exemptionService.reviewFlight(
                new FlightReviewRequest(flightKey, routeId, reviewAt, uniq("req-fly")));
    }

    private int remaining(String permitKey, String regionKey) {
        return jdbc.queryForObject(
                "SELECT remaining FROM permit_item WHERE permit_key = ? AND region_key = ?",
                Integer.class, permitKey, regionKey);
    }

    private int count(String sql, Object... args) {
        Integer c = jdbc.queryForObject(sql, Integer.class, args);
        return c == null ? 0 : c;
    }

    // ============================ 签发/撤销与查询 ============================

    @Test
    @DisplayName("签发：额度初始等于 quota，permitKey 与同 requestId 重放语义正确")
    void issuePermitInitializesBalancesAndRejectsDuplicates() {
        long v = createZone("za", 40, 5, 60, 15);
        MutationResponse resp = issue("p1", 1, covering("za", v, 3));
        assertFalse(resp.replayed());
        PermitView view = objectMapper.convertValue(resp.data(), PermitView.class);
        assertEquals("ISSUED", view.status());
        assertEquals(1, view.permitVersion());
        assertEquals(3, view.items().get(0).remaining());
        assertEquals(3, view.items().get(0).quota());

        // permitKey 唯一
        ApiException dup = assertThrows(ApiException.class,
                () -> issue("p1", 1, covering("za", v, 5)));
        assertEquals(HttpStatus.CONFLICT, dup.status());

        // 同 requestId 同参重放（窗口必须复用同一对象，避免构造时刻不同导致哈希不同）
        String requestId = uniq("req-fixed");
        List<PermitItemRequest> fixedItems = List.of(covering("za", v, 2));
        MutationResponse first = exemptionService.issuePermit(new PermitIssueRequest(
                "p2", 1, fixedItems, requestId));
        MutationResponse replay = exemptionService.issuePermit(new PermitIssueRequest(
                "p2", 1, fixedItems, requestId));
        assertTrue(replay.replayed());
        assertEquals(1, count("SELECT COUNT(*) FROM permit_package WHERE permit_key = 'p2'"));

        // 同 requestId 异参 → 409
        ApiException diff = assertThrows(ApiException.class, () -> exemptionService.issuePermit(
                new PermitIssueRequest("p2", 1, List.of(covering("za", v, 9)), requestId)));
        assertEquals(HttpStatus.CONFLICT, diff.status());

        // 只读余额查询
        assertEquals(2, exemptionService.getPermit("p2").items().get(0).remaining());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class, () -> exemptionService.getPermit("ghost")).status());
    }

    @Test
    @DisplayName("签发校验：项数与重复 region、UTC 区间非法返回 400")
    void issueValidationFailures() {
        long v = createZone("zv", 40, 5, 60, 15);
        long now = System.currentTimeMillis();
        ApiException duplicate = assertThrows(ApiException.class, () -> exemptionService.issuePermit(
                new PermitIssueRequest("pd", 1,
                        List.of(covering("zv", v, 1), covering("zv", v, 2)), uniq("req"))));
        assertEquals(HttpStatus.BAD_REQUEST, duplicate.status());
        assertEquals("DUPLICATE_PERMIT_ITEM", duplicate.code());

        ApiException badWindow = assertThrows(ApiException.class, () -> exemptionService.issuePermit(
                new PermitIssueRequest("pw", 1,
                        List.of(item("zv", v, now, now, 1)), uniq("req"))));
        assertEquals(HttpStatus.BAD_REQUEST, badWindow.status());
        assertEquals("INVALID_PERMIT_WINDOW", badWindow.code());
    }

    @Test
    @DisplayName("撤销：未使用余额失效、历史核销保留、重复撤销 409、幂等重放")
    void revokeInvalidatesBalanceButKeepsHistory() {
        String routeId = createRoute("rr", 0, 10, 100, 10);
        long v = createZone("zr", 40, 5, 60, 15);
        issue("pr", 1, covering("zr", v, 2));
        long reviewAt = System.currentTimeMillis();
        MutationResponse clear = fly("fr-1", routeId, reviewAt);
        assertEquals("CLEAR", dataOf(clear).conclusion());
        assertEquals(1, remaining("pr", "zr"));

        String revokeReq = uniq("req-revoke");
        MutationResponse revoked = exemptionService.revokePermit(
                new PermitRevokeRequest("pr", revokeReq));
        assertEquals("REVOKED",
                objectMapper.convertValue(revoked.data(), PermitView.class).status());
        // 撤销不回写余额与历史核销
        assertEquals(1, remaining("pr", "zr"));
        assertEquals(1, exemptionService.getConsumptions("pr").size());

        // 撤销后审核不再放行
        MutationResponse blocked = fly("fr-2", routeId, reviewAt);
        FlightReviewResultDto dto = dataOf(blocked);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals("MISSING", dto.deficits().get(0).reason());
        assertEquals(1, remaining("pr", "zr"));

        ApiException twice = assertThrows(ApiException.class, () -> exemptionService.revokePermit(
                new PermitRevokeRequest("pr", uniq("req-revoke"))));
        assertEquals(HttpStatus.CONFLICT, twice.status());
        // 同键同参重放撤销
        assertTrue(exemptionService.revokePermit(
                new PermitRevokeRequest("pr", revokeReq)).replayed());

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> exemptionService.revokePermit(
                        new PermitRevokeRequest("ghost", uniq("req-revoke")))).status());
    }

    // ============================ 核销主流程与失败分支 ============================

    @Test
    @DisplayName("主流程：全部命中区域由同一豁免包覆盖时 CLEAR 并每项扣 1，快照冻结版本与余额")
    void clearConsumesOnePerHitAndFreezesSnapshot() {
        String routeId = createRoute("rc", 0, 10, 100, 10);
        long va = createZone("za", 40, 5, 60, 15);
        long vb = createZone("zb", 80, 8, 90, 12);
        issue("pc", 1, covering("za", va, 2), covering("zb", vb, 1),
                covering("zf", 99L, 5)); // 未命中区域的项不应被消费
        long reviewAt = System.currentTimeMillis();

        MutationResponse resp = fly("fc-1", routeId, reviewAt);
        FlightReviewResultDto dto = dataOf(resp);
        assertEquals("CLEAR", dto.conclusion());
        assertEquals(List.of("za", "zb"), dto.hitRegionKeys());
        assertEquals(1, remaining("pc", "za"));
        assertEquals(0, remaining("pc", "zb"));
        assertEquals(5, remaining("pc", "zf"), "未命中区域项不消费");
        assertEquals(2, dto.consumed().size());
        ConsumedQuota ca = dto.consumed().get(0);
        assertEquals("za", ca.regionKey());
        assertEquals(2, ca.before());
        assertEquals(1, ca.after());

        // 快照冻结空域/航线/permit 版本、几何命中与核销前后余额
        FlightReviewSnapshot snap = dto.snapshot();
        assertEquals(2L, snap.airspaceVersion());
        assertEquals(1, snap.routeVersion());
        assertEquals(1, snap.permitVersion());
        assertEquals("pc", snap.permitKey());
        assertEquals(2, snap.hits().size());
        assertTrue(snap.hits().stream().allMatch(com.example.starter.api.dto.SnapshotHit::matched));
        assertEquals(2, snap.consumed().size());

        // 核销流水与审核历史只读可查
        List<ConsumptionDto> consumptions = exemptionService.getConsumptions("pc");
        assertEquals(2, consumptions.size());
        FlightReviewResultDto loaded = exemptionService.getFlightReview(dto.reviewId());
        assertEquals("CLEAR", loaded.conclusion());
        assertEquals(2, loaded.consumed().size());
        assertEquals(2L, loaded.snapshot().airspaceVersion());
        assertEquals(2, count("SELECT COUNT(*) FROM permit_consumption WHERE review_id = ?",
                dto.reviewId()));
    }

    @Test
    @DisplayName("未命中任何区域：无豁免包也 CLEAR，不消费任何项")
    void clearWithoutHitsDoesNotConsume() {
        String routeId = createRoute("rn", 0, 10, 10, 10);
        createZone("zfar", 900, 900, 990, 990);
        MutationResponse resp = fly("fn-1", routeId, System.currentTimeMillis());
        FlightReviewResultDto dto = dataOf(resp);
        assertEquals("CLEAR", dto.conclusion());
        assertTrue(dto.hitRegionKeys().isEmpty());
        assertNull(dto.permitKey());
        assertTrue(dto.consumed().isEmpty());
        assertNotNull(dto.reviewId());
    }

    @Test
    @DisplayName("缺失项：BLOCKED 返回 MISSING 且不扣任何额度，修正（签发新包）后可复用同参成功")
    void missingItemBlocksWholeTransactionAndCanBeRetried() {
        String routeId = createRoute("rm", 0, 10, 100, 10);
        long va = createZone("zm1", 40, 5, 60, 15);
        long vb = createZone("zm2", 80, 8, 90, 12);
        // 只覆盖一个命中区域
        issue("pm-partial", 1, covering("zm1", va, 3));

        String requestId = uniq("req-fly-fixed");
        long reviewAt = System.currentTimeMillis();
        MutationResponse blocked = exemptionService.reviewFlight(
                new FlightReviewRequest("fm-1", routeId, reviewAt, requestId));
        FlightReviewResultDto dto = dataOf(blocked);
        assertEquals("BLOCKED", dto.conclusion());
        assertNull(dto.reviewId());
        assertEquals(2, dto.deficits().size());
        assertEquals("zm2", dto.deficits().get(1).regionKey());
        assertEquals("MISSING", dto.deficits().get(1).reason());
        // 已覆盖项也不能扣
        assertEquals(3, remaining("pm-partial", "zm1"));
        assertEquals(0, count("SELECT COUNT(*) FROM permit_consumption"));
        assertEquals(0, count("SELECT COUNT(*) FROM flight_review"));

        // 失败不占 requestId / flightKey：签发覆盖全部命中区域的新包后同参复用成功
        issue("pm-full", 1, covering("zm1", va, 3), covering("zm2", vb, 3));
        MutationResponse clear = exemptionService.reviewFlight(
                new FlightReviewRequest("fm-1", routeId, reviewAt, requestId));
        assertEquals("CLEAR", dataOf(clear).conclusion());
        assertEquals(1, count("SELECT COUNT(*) FROM flight_review WHERE flight_key = 'fm-1'"));
    }

    @Test
    @DisplayName("版本不匹配与有效期边界：MISSING/EXPIRED 判定正确，区间为 [from, to)")
    void versionMismatchAndValidityWindowBoundaries() {
        String routeId = createRoute("rw", 0, 10, 100, 10);
        long v = createZone("zw", 40, 5, 60, 15);
        long t = 1_000_000_000_000L;

        // 有效期边界：validFrom 含、validTo 不含（此时唯一候选包，缺口原因明确）
        issue("pw-win", 1, item("zw", v, t, t + 1000, 2));
        assertEquals("CLEAR", dataOf(fly("fw-from", routeId, t)).conclusion());
        assertEquals("EXPIRED", dataOf(fly("fw-to", routeId, t + 1000)).deficits().get(0).reason());
        assertEquals("EXPIRED", dataOf(fly("fw-before", routeId, t - 1)).deficits().get(0).reason());
        // 仅一次 CLEAR 扣额，EXPIRED 不扣额
        assertEquals(1, remaining("pw-win", "zw"));

        // 撤销窗口包后，签发区域版本错误的新包：唯一候选 → MISSING 且不扣额
        exemptionService.revokePermit(
                new com.example.starter.api.dto.PermitRevokeRequest("pw-win", uniq("req-rev")));
        issue("pw-ver", 1, item("zw", v + 1, t - 1000, t + 1000, 2));
        assertEquals("MISSING", dataOf(fly("fw-v", routeId, t)).deficits().get(0).reason());
        assertEquals(2, remaining("pw-ver", "zw"));
    }

    @Test
    @DisplayName("耗尽：额度用完后 BLOCKED 为 EXHAUSTED 且不再扣减")
    void exhaustedQuotaBlocksWithoutDeduction() {
        String routeId = createRoute("re", 0, 10, 100, 10);
        long v = createZone("ze", 40, 5, 60, 15);
        issue("pe", 1, covering("ze", v, 1));
        long reviewAt = System.currentTimeMillis();
        assertEquals("CLEAR", dataOf(fly("fe-1", routeId, reviewAt)).conclusion());
        assertEquals(0, remaining("pe", "ze"));

        MutationResponse blocked = fly("fe-2", routeId, reviewAt);
        FlightReviewResultDto dto = dataOf(blocked);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals("EXHAUSTED", dto.deficits().get(0).reason());
        assertEquals(0, remaining("pe", "ze"));
        assertEquals(1, count("SELECT COUNT(*) FROM permit_consumption"));
        assertEquals(1, count("SELECT COUNT(*) FROM flight_review"));
    }

    @Test
    @DisplayName("整体回滚：多命中区域中一项耗尽，另一项有余额也不得扣减")
    void blockedRollsBackAllDeductions() {
        // 两个互不相交区域；route-a 只穿过 za，route-b 穿过 za 与 zb
        createRoute("ra", 0, 50, 30, 50);
        String bothRoute = createRoute("rb", 0, 50, 100, 50);
        long va = createZone("za", 10, 45, 20, 55);
        long vb = createZone("zb", 60, 45, 80, 55);
        issue("px", 1, covering("za", va, 1), covering("zb", vb, 1));

        // 先消耗 za 的唯一额度（航线只穿 za）
        assertEquals("CLEAR", dataOf(fly("fx-a", "ra", System.currentTimeMillis())).conclusion());
        assertEquals(0, remaining("px", "za"));
        assertEquals(1, remaining("px", "zb"));

        // 同时命中 za/zb：za 已耗尽 → BLOCKED，zb 不得被扣
        MutationResponse blocked = fly("fx-b", bothRoute, System.currentTimeMillis());
        assertEquals("BLOCKED", dataOf(blocked).conclusion());
        assertEquals(0, remaining("px", "za"));
        assertEquals(1, remaining("px", "zb"), "BLOCKED 必须整体回滚，zb 不得扣减");
        assertEquals(1, count("SELECT COUNT(*) FROM permit_consumption"));
        assertEquals(0, count("SELECT COUNT(*) FROM flight_review WHERE flight_key = 'fx-b'"));
    }

    @Test
    @DisplayName("航线版本：豁免包只对绑定的精确 routeVersion 有效，修订后审核 BLOCKED")
    void permitBoundToExactRouteVersion() {
        String routeId = createRoute("rv", 0, 10, 100, 10);
        long v = createZone("zv2", 40, 5, 60, 15);
        issue("pv", 1, covering("zv2", v, 2));
        // 替换到版本 2（点列仍穿区）
        airspaceService.replaceRoute(new RouteReplaceRequest(
                routeId, 1, pts(0, 10, 100, 10), uniq("req-replace")));
        MutationResponse blocked = fly("fv-1", routeId, System.currentTimeMillis());
        FlightReviewResultDto dto = dataOf(blocked);
        assertEquals("BLOCKED", dto.conclusion());
        assertEquals(2, dto.routeVersion());
        assertEquals("MISSING", dto.deficits().get(0).reason());
        assertNull(dto.permitKey());
        assertEquals(2, remaining("pv", "zv2"), "不匹配 routeVersion 的豁免包不得扣减");
    }

    @Test
    @DisplayName("审核航线不存在返回 404")
    void reviewMissingRouteIs404() {
        ApiException ex = assertThrows(ApiException.class,
                () -> fly("fg", "ghost", System.currentTimeMillis()));
        assertEquals(HttpStatus.NOT_FOUND, ex.status());
    }

    // ============================ 幂等边界 ============================

    @Test
    @DisplayName("requestId 幂等：同参重放不重复扣额，异参 409，BLOCKED 不占键")
    void requestIdIdempotencyAndBlockedDoesNotConsumeKey() {
        String routeId = createRoute("ri", 0, 10, 100, 10);
        long v = createZone("zi", 40, 5, 60, 15);
        issue("pi", 1, covering("zi", v, 5));
        String requestId = uniq("req-fly-fixed");
        long reviewAt = System.currentTimeMillis();
        FlightReviewRequest req = new FlightReviewRequest("fi-1", routeId, reviewAt, requestId);

        MutationResponse first = exemptionService.reviewFlight(req);
        String reviewId = dataOf(first).reviewId();
        assertEquals(4, remaining("pi", "zi"));
        MutationResponse replay = exemptionService.reviewFlight(req);
        assertTrue(replay.replayed());
        assertEquals(reviewId, dataOf(replay).reviewId());
        assertEquals(4, remaining("pi", "zi"), "重放不得重复扣额");
        assertEquals(1, count("SELECT COUNT(*) FROM flight_review"));
        assertEquals(1, count("SELECT COUNT(*) FROM permit_consumption"));

        // 同 requestId 异参 → 409
        ApiException diff = assertThrows(ApiException.class, () -> exemptionService.reviewFlight(
                new FlightReviewRequest("fi-other", routeId, reviewAt + 1, requestId)));
        assertEquals(HttpStatus.CONFLICT, diff.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", diff.code());
    }

    @Test
    @DisplayName("flightKey 唯一：同 flightKey 只形成一次审核，重放不扣额，异参 409")
    void sameFlightKeyPlaysBackAndRejectsDifferentParams() {
        String routeId = createRoute("rf", 0, 10, 100, 10);
        long v = createZone("zf2", 40, 5, 60, 15);
        issue("pf", 1, covering("zf2", v, 5));
        long reviewAt = System.currentTimeMillis();

        MutationResponse first = exemptionService.reviewFlight(
                new FlightReviewRequest("fk-1", routeId, reviewAt, uniq("req-a")));
        String reviewId = dataOf(first).reviewId();
        assertEquals(4, remaining("pf", "zf2"));
        // 新 requestId 但同 flightKey 同参：重放同一审核，不扣额
        MutationResponse replay = exemptionService.reviewFlight(
                new FlightReviewRequest("fk-1", routeId, reviewAt, uniq("req-b")));
        assertTrue(replay.replayed());
        assertEquals(reviewId, dataOf(replay).reviewId());
        assertEquals(4, remaining("pf", "zf2"));
        assertEquals(1, count("SELECT COUNT(*) FROM flight_review WHERE flight_key = 'fk-1'"));

        // 同 flightKey 异参（reviewAt 不同）→ 409
        ApiException diffTime = assertThrows(ApiException.class, () ->
                exemptionService.reviewFlight(new FlightReviewRequest(
                        "fk-1", routeId, reviewAt + 1, uniq("req-c"))));
        assertEquals(HttpStatus.CONFLICT, diffTime.status());
        assertEquals("FLIGHT_PARAM_MISMATCH", diffTime.code());
        // 同 flightKey 异参（routeId 不同）→ 409
        ApiException diffRoute = assertThrows(ApiException.class, () ->
                exemptionService.reviewFlight(new FlightReviewRequest(
                        "fk-1", "other-route", reviewAt, uniq("req-d"))));
        assertEquals(HttpStatus.CONFLICT, diffRoute.status());
    }

    // ============================ 并发边界 ============================

    @Test
    @DisplayName("并发核销：额度为 1 时两个并发航班恰好一个 CLEAR 一个 BLOCKED，总扣减恰为 1")
    void concurrentReviewsDeductExactlyOnce() throws Exception {
        String routeId = createRoute("rcx", 0, 10, 100, 10);
        long v = createZone("zcx", 40, 5, 60, 15);
        issue("pcx", 1, covering("zcx", v, 1));
        long reviewAt = System.currentTimeMillis();

        int n = 4;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return exemptionService.reviewFlight(new FlightReviewRequest(
                            "fcx-" + idx, routeId, reviewAt, "req-cx-" + idx));
                }));
            }
            int clear = 0;
            int blocked = 0;
            for (Future<MutationResponse> f : futures) {
                FlightReviewResultDto dto = dataOf(f.get(20, TimeUnit.SECONDS));
                if ("CLEAR".equals(dto.conclusion())) {
                    clear++;
                } else {
                    blocked++;
                    assertEquals("EXHAUSTED", dto.deficits().get(0).reason());
                }
            }
            assertEquals(1, clear, "恰好一个航班核销成功");
            assertEquals(n - 1, blocked);
            assertEquals(0, remaining("pcx", "zcx"));
            assertEquals(1, count("SELECT COUNT(*) FROM permit_consumption"));
            assertEquals(1, count("SELECT COUNT(*) FROM flight_review"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发签发与审核共用一致视图：结论必须自洽，BLOCKED 后修正可成功")
    void concurrentIssueAndReviewNeverYieldsPartialState() throws Exception {
        String routeId = createRoute("rci", 0, 10, 100, 10);
        long v = createZone("zci", 40, 5, 60, 15);
        long reviewAt = System.currentTimeMillis();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MutationResponse> reviewFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return fly("fci-1", routeId, reviewAt);
            });
            Future<MutationResponse> issueFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return issue("pci", 1, covering("zci", v, 1));
            });

            FlightReviewResultDto reviewDto = dataOf(reviewFuture.get(20, TimeUnit.SECONDS));
            issueFuture.get(20, TimeUnit.SECONDS);
            if ("CLEAR".equals(reviewDto.conclusion())) {
                // 签发先提交：额度恰扣 1
                assertEquals(0, remaining("pci", "zci"));
                assertEquals(1, count("SELECT COUNT(*) FROM permit_consumption"));
            } else {
                // 审核先提交（无豁免包）：BLOCKED 且无任何扣减，同参修正后可成功
                assertEquals("MISSING", reviewDto.deficits().get(0).reason());
                assertEquals(1, remaining("pci", "zci"));
                assertEquals(0, count("SELECT COUNT(*) FROM permit_consumption"));
                MutationResponse retry = fly("fci-2", routeId, reviewAt);
                assertEquals("CLEAR", dataOf(retry).conclusion());
                assertEquals(0, remaining("pci", "zci"));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
