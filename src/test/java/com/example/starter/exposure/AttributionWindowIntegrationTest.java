package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignDiagnosticsResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReceiptBatchRequest;
import com.example.starter.exposure.web.ReceiptBatchResponse;
import com.example.starter.exposure.web.ReceiptItemResult;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationDetailResponse;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateWindowRequest;
import com.example.starter.exposure.web.WindowVersionResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公告归因窗口 H2（MODE=MySQL）集成测试：窗口边界、422 失败分支、整批事务、
 * 状态裁决、窗口版本不可变、幂等重放与只读查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AttributionWindowIntegrationTest {

    /** 可控时钟：固定起点，可按毫秒推进。 */
    static class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        void advanceMillis(long millis) {
            this.instant = instant.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    static final Instant BASE = Instant.parse("2026-09-28T10:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 9, 28);

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock mutableClock() {
            return new MutableClock(BASE);
        }
    }

    @Autowired
    ExposureService service;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MockMvc mockMvc;
    @Autowired
    Clock clock;

    private MutableClock mutableClock() {
        return (MutableClock) clock;
    }

    @BeforeEach
    void cleanAndReset() {
        jdbc.update("DELETE FROM attribution_receipt");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign_window_version");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    // ---- 构造辅助 ----

    private CreateCampaignRequest campaign(String requestId, String campaignId, int windowMinutes) {
        return new CreateCampaignRequest(requestId, campaignId, 100, 10, windowMinutes, "op-admin");
    }

    private ReservationResponse apply(String requestId, String campaignId, String visitorId) {
        return service.apply(new ApplyExposureRequest(requestId, campaignId, visitorId));
    }

    private ReceiptBatchRequest.ReceiptItem item(ReservationResponse r, long receiptAtUtc) {
        return new ReceiptBatchRequest.ReceiptItem(
                r.reservationId(), r.campaignId(), r.visitorId(), receiptAtUtc);
    }

    private ReceiptBatchRequest.ReceiptItem item(String reservationId, String campaignId,
                                                 String visitorId, long receiptAtUtc) {
        return new ReceiptBatchRequest.ReceiptItem(reservationId, campaignId, visitorId, receiptAtUtc);
    }

    private ReceiptBatchResponse receipts(String requestId, String operator,
                                          ReceiptBatchRequest.ReceiptItem... items) {
        return service.submitReceipts(
                new ReceiptBatchRequest(requestId, operator, List.of(items)));
    }

    private ApiException expect422(Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(422, ex.getStatus().value());
        return ex;
    }

    // ---- 主流程与窗口边界 ----

    @Test
    @DisplayName("窗口起点（预占时刻）回执计入归因：ATTRIBUTED 结算且额度持续占用")
    void receipt_atWindowStart_isAttributedAndQuotaStaysOccupied() {
        service.createCampaign(campaign("req-c", "c1", 5));
        ReservationResponse r = apply("req-a1", "c1", "v1");
        long start = BASE.toEpochMilli();

        ReceiptBatchResponse resp = receipts("req-r1", "op-1", item(r, start));

        assertEquals(1, resp.totalCount());
        assertEquals(1, resp.attributedCount());
        assertEquals(0, resp.lateCount());
        assertEquals(1.0, resp.attributedRatio(), 0.0);
        ReceiptItemResult detail = resp.results().get(0);
        assertEquals("ATTRIBUTED", detail.result());
        assertEquals(start, detail.windowStartUtc());
        assertEquals(start + 5 * 60_000L, detail.windowEndUtc());
        assertTrue(detail.withinWindow());
        assertEquals(0L, detail.deltaFromStartMillis());
        assertEquals(5 * 60_000L, detail.deltaToEndMillis());
        assertEquals(5, detail.attributionWindowMinutes());
        assertEquals(1, detail.windowVersion());
        assertNull(detail.lateReason());

        ReservationResponse after = service.getReservation(r.reservationId());
        assertEquals("ATTRIBUTED", after.status());
        assertEquals(start, after.attributedAtUtc(), "归因业务时刻=回执时刻");
        assertEquals(start, after.terminalAtUtc(), "服务端受理时刻=BASE");
        assertEquals("req-r1", after.attributedRequestId());
        assertEquals(5, after.attributionWindowMinutes());
        assertEquals(1, after.windowVersion());

        QuotaResponse quota = service.queryQuota("c1", "v1", DAY);
        assertEquals(1, quota.usedTotal(), "归因结算不释放额度");
        assertEquals(1, quota.usedVisitor());
    }

    @Test
    @DisplayName("左闭右开：窗口终点前 1ms 计入，恰在终点为 LATE_RECEIPT")
    void window_isLeftClosedRightOpen() {
        service.createCampaign(campaign("req-c", "c1", 10));
        long start = BASE.toEpochMilli();
        long end = start + 10 * 60_000L;
        ReservationResponse inside = apply("req-a1", "c1", "v1");
        ReservationResponse atEnd = apply("req-a2", "c1", "v2");

        ReceiptBatchResponse ok = receipts("req-r1", "op-1", item(inside, end - 1L));
        assertEquals("ATTRIBUTED", ok.results().get(0).result());
        assertTrue(ok.results().get(0).withinWindow());
        assertEquals(1L, ok.results().get(0).deltaToEndMillis());

        // 受理时刻仍在预占 TTL 内（提前提交），回执业务时刻恰为窗口终点：几何窗口外
        ReceiptBatchResponse late = receipts("req-r2", "op-1", item(atEnd, end));
        assertEquals(1, late.lateCount());
        ReceiptItemResult lateItem = late.results().get(0);
        assertEquals("LATE_RECEIPT", lateItem.result());
        assertFalse(lateItem.withinWindow());
        assertEquals("AT_OR_AFTER_WINDOW_END", lateItem.lateReason());
        assertEquals(0L, lateItem.deltaToEndMillis(), "差额=要求终点-实际值=0");
        assertEquals(10 * 60_000L, lateItem.deltaFromStartMillis());

        // 窗口外不二次确认、不释放配额：预占仍为 RESERVED，两级额度保持占用
        assertEquals("RESERVED", service.getReservation(atEnd.reservationId()).status());
        assertEquals(2, service.queryQuota("c1", null, DAY).usedTotal());
    }

    @Test
    @DisplayName("窗口范围 1～1440 分钟：边界值可建，越界 400")
    void windowMinutes_bounds1to1440() throws Exception {
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1\",\"campaignId\":\"cw1\",\"dailyTotalCap\":1,"
                                + "\"perVisitorDailyCap\":1,\"attributionWindowMinutes\":1,"
                                + "\"operatorId\":\"op\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attributionWindowMinutes").value(1))
                .andExpect(jsonPath("$.windowVersion").value(1));

        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w2\",\"campaignId\":\"cw2\",\"dailyTotalCap\":1,"
                                + "\"perVisitorDailyCap\":1,\"attributionWindowMinutes\":1440,"
                                + "\"operatorId\":\"op\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attributionWindowMinutes").value(1440));

        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w3\",\"campaignId\":\"cw3\",\"dailyTotalCap\":1,"
                                + "\"perVisitorDailyCap\":1,\"attributionWindowMinutes\":0,"
                                + "\"operatorId\":\"op\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w4\",\"campaignId\":\"cw4\",\"dailyTotalCap\":1,"
                                + "\"perVisitorDailyCap\":1,\"attributionWindowMinutes\":1441,"
                                + "\"operatorId\":\"op\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- 422 失败分支与整批事务 ----

    @Test
    @DisplayName("422：预占不存在、回执早于预占、已取消、公告/访客归属不匹配")
    void invalidReceipts_return422WithDistinguishableCodes() {
        service.createCampaign(campaign("req-c", "c1", 10));
        ReservationResponse r = apply("req-a1", "c1", "v1");
        ReservationResponse cancelled = apply("req-a2", "c1", "v2");
        service.cancel(cancelled.reservationId(), new ReservationActionRequest("req-x1"));
        long start = BASE.toEpochMilli();

        assertEquals("RESERVATION_NOT_FOUND",
                expect422(() -> receipts("req-e1", "op-1",
                        item("no-such-reservation", "c1", "v1", start))).getCode());
        assertEquals("RECEIPT_BEFORE_RESERVATION",
                expect422(() -> receipts("req-e2", "op-1", item(r, start - 1L))).getCode());
        assertEquals("RESERVATION_CANCELLED",
                expect422(() -> receipts("req-e3", "op-1",
                        item(cancelled, start + 1_000L))).getCode());
        assertEquals("CAMPAIGN_MISMATCH",
                expect422(() -> receipts("req-e4", "op-1",
                        item(r.reservationId(), "c-other", "v1", start + 1_000L))).getCode());
        assertEquals("VISITOR_MISMATCH",
                expect422(() -> receipts("req-e5", "op-1",
                        item(r.reservationId(), "c1", "v-other", start + 1_000L))).getCode());
        assertEquals("DUPLICATE_RESERVATION_IN_BATCH",
                expect422(() -> receipts("req-e6", "op-1",
                        item(r, start + 1_000L), item(r, start + 2_000L))).getCode());

        // 全部失败均不落任何流水、不占幂等键
        assertEquals(Integer.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM attribution_receipt", Integer.class));
        // 失败键可被成功操作复用（失败不占键）
        ReceiptBatchResponse retry = receipts("req-e2", "op-1", item(r, start + 1_000L));
        assertEquals("ATTRIBUTED", retry.results().get(0).result());
    }

    @Test
    @DisplayName("批量中任一预占归属不匹配：整批不结算、不留半成品、不写流水")
    void batch_oneMismatch_entireBatchRolledBack() {
        service.createCampaign(campaign("req-c", "c1", 10));
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        ReservationResponse r2 = apply("req-a2", "c1", "v2");
        long start = BASE.toEpochMilli();

        ApiException ex = expect422(() -> receipts("req-b1", "op-1",
                item(r1, start + 1_000L),
                item(r2.reservationId(), "c-other", "v2", start + 1_000L)));
        assertEquals("CAMPAIGN_MISMATCH", ex.getCode());

        // 两条预占都保持 RESERVED，没有任何流水
        assertEquals("RESERVED", service.getReservation(r1.reservationId()).status());
        assertEquals("RESERVED", service.getReservation(r2.reservationId()).status());
        assertEquals(Integer.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM attribution_receipt", Integer.class));
        // 幂等键未被占用
        assertEquals(Integer.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'req-b1'",
                Integer.class));
    }

    @Test
    @DisplayName("混合批次一次事务裁决：ATTRIBUTED/LATE/ALREADY_* 数量、比例与明细正确")
    void mixedBatch_adjudicatesAllInOneTransaction() {
        service.createCampaign(campaign("req-c", "c1", 10));
        long start = BASE.toEpochMilli();
        ReservationResponse fresh = apply("req-a1", "c1", "v1");
        ReservationResponse confirmed = apply("req-a2", "c1", "v2");
        service.confirm(confirmed.reservationId(), new ReservationActionRequest("req-k1"));
        ReservationResponse attributed = apply("req-a3", "c1", "v3");
        receipts("req-r0", "op-1", item(attributed, start + 1_000L));
        // 业务时刻在窗口终点之后，但受理时刻仍在预占 TTL 内
        ReservationResponse late = apply("req-a4", "c1", "v4");

        ReceiptBatchResponse resp = receipts("req-r1", "op-1",
                item(fresh, start + 2_000L),
                item(confirmed, start + 2_000L),
                item(attributed, start + 3_000L),
                item(late, start + 10 * 60_000L));

        // 提交集合顺序无关：结果按预占编号升序稳定返回；按预占编号建映射核对
        assertEquals(4, resp.totalCount());
        assertEquals(1, resp.attributedCount());
        assertEquals(1, resp.lateCount());
        assertEquals(1, resp.alreadyAttributedCount());
        assertEquals(1, resp.alreadyConfirmedCount());
        assertEquals(0.25, resp.attributedRatio(), 1e-9);
        java.util.Map<String, ReceiptItemResult> byId = new java.util.HashMap<>();
        for (ReceiptItemResult itemResult : resp.results()) {
            byId.put(itemResult.reservationId(), itemResult);
        }
        assertEquals("ATTRIBUTED", byId.get(fresh.reservationId()).result());
        assertEquals("ALREADY_CONFIRMED", byId.get(confirmed.reservationId()).result());
        ReceiptItemResult alreadyAttributed = byId.get(attributed.reservationId());
        assertEquals("ALREADY_ATTRIBUTED", alreadyAttributed.result());
        assertEquals(start + 1_000L, alreadyAttributed.attributedAtUtc(),
                "ALREADY_ATTRIBUTED 回填首次归因业务时刻");
        ReceiptItemResult lateItem = byId.get(late.reservationId());
        assertEquals("LATE_RECEIPT", lateItem.result());
        assertEquals("AT_OR_AFTER_WINDOW_END", lateItem.lateReason());
        assertNull(lateItem.attributedAtUtc());
    }

    // ---- 只结算一次 / 状态竞争 ----

    @Test
    @DisplayName("同一预占多次回执：仅首次窗口内回执 ATTRIBUTED，之后全部 ALREADY_ATTRIBUTED 且不重复结算")
    void repeatedReceipts_attributeOnlyOnce() {
        service.createCampaign(campaign("req-c", "c1", 10));
        ReservationResponse r = apply("req-a1", "c1", "v1");
        long start = BASE.toEpochMilli();

        ReceiptBatchResponse first = receipts("req-r1", "op-1", item(r, start + 1_000L));
        ReceiptBatchResponse second = receipts("req-r2", "op-1", item(r, start + 2_000L));
        ReceiptBatchResponse third = receipts("req-r3", "op-1", item(r, start + 3_000L));

        assertEquals("ATTRIBUTED", first.results().get(0).result());
        assertEquals("ALREADY_ATTRIBUTED", second.results().get(0).result());
        assertEquals("ALREADY_ATTRIBUTED", third.results().get(0).result());
        assertEquals(start + 1_000L, second.results().get(0).attributedAtUtc());
        assertEquals(start + 1_000L, third.results().get(0).attributedAtUtc());

        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());
        assertEquals(Integer.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM attribution_receipt WHERE reservation_id = ? AND result = 'ATTRIBUTED'",
                Integer.class, r.reservationId()));
        // 已归因预占不允许再确认/取消
        ApiException confirmEx = assertThrows(ApiException.class,
                () -> service.confirm(r.reservationId(), new ReservationActionRequest("req-k9")));
        assertEquals(409, confirmEx.getStatus().value());
        ApiException cancelEx = assertThrows(ApiException.class,
                () -> service.cancel(r.reservationId(), new ReservationActionRequest("req-x9")));
        assertEquals(409, cancelEx.getStatus().value());
    }

    @Test
    @DisplayName("已确认预占的回执为 ALREADY_CONFIRMED；已过期预占窗口内时刻回执为 LATE_RECEIPT 且不重新占额")
    void confirmedAndExpiredReservations_doNotSettle() {
        service.createCampaign(campaign("req-c", "c1", 10));
        long start = BASE.toEpochMilli();
        ReservationResponse confirmed = apply("req-a1", "c1", "v1");
        service.confirm(confirmed.reservationId(), new ReservationActionRequest("req-k1"));

        ReceiptBatchResponse resp = receipts("req-r1", "op-1",
                item(confirmed, start + 1_000L));
        assertEquals("ALREADY_CONFIRMED", resp.results().get(0).result());
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());

        // 预占到期后（窗口比 TTL 长）提交业务时刻落在窗口内的回执：过期已释放，回执只记录
        ReservationResponse expired = apply("req-a2", "c1", "v2");
        mutableClock().advanceMillis(60_000L);
        assertEquals("EXPIRED", service.getReservation(expired.reservationId()).status());
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal(),
                "过期已释放（确认单仍占 1）");

        ReceiptBatchResponse late = receipts("req-r2", "op-1",
                item(expired, start + 30_000L));
        assertEquals("LATE_RECEIPT", late.results().get(0).result());
        assertEquals("RESERVATION_EXPIRED", late.results().get(0).lateReason());
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal(),
                "LATE_RECEIPT 不重新占用额度");
        assertEquals("EXPIRED", service.getReservation(expired.reservationId()).status());
    }

    // ---- 窗口版本不可变 ----

    @Test
    @DisplayName("窗口版本：调整追加新版本；历史预占按快照版本裁决，不重算")
    void windowUpdate_appendsVersionAndHistoryUsesSnapshot() {
        service.createCampaign(campaign("req-c", "c1", 5));
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        assertEquals(1, r1.windowVersion());
        assertEquals(5, r1.attributionWindowMinutes());

        // 推进 1 秒后调整窗口为 10 分钟（受理时刻仍在 r1 的 60 秒 TTL 内，避免过期干扰）
        mutableClock().advanceMillis(1_000L);
        var updated = service.updateWindow("c1",
                new UpdateWindowRequest("req-u1", "op-admin", 10));
        assertEquals(10, updated.attributionWindowMinutes());
        assertEquals(2, updated.windowVersion());

        // 相同窗口值 409
        ApiException unchanged = assertThrows(ApiException.class,
                () -> service.updateWindow("c1", new UpdateWindowRequest("req-u2", "op-admin", 10)));
        assertEquals(409, unchanged.getStatus().value());
        assertEquals("WINDOW_UNCHANGED", unchanged.getCode());

        ReservationResponse r2 = apply("req-a2", "c1", "v2");
        assertEquals(2, r2.windowVersion());
        assertEquals(10, r2.attributionWindowMinutes());

        // 版本历史按版本号升序、不可变
        List<WindowVersionResponse> versions = service.listWindowVersions("c1");
        assertEquals(2, versions.size());
        assertEquals(1, versions.get(0).version());
        assertEquals(5, versions.get(0).attributionWindowMinutes());
        assertEquals(2, versions.get(1).version());
        assertEquals(10, versions.get(1).attributionWindowMinutes());
        assertEquals("op-admin", versions.get(1).operatorId());

        // 受理时刻再推进 1 秒（BASE+2s），两单均仍 RESERVED（未到各自 60 秒 TTL）。
        // 回执业务时刻可晚于受理时刻：r1Start+6min 相对 r1 的 V1(5min) 窗口为几何窗口外，
        // 相对 r2 的 V2(10min) 窗口为窗口内；差异完全来自预占快照的窗口版本。
        mutableClock().advanceMillis(1_000L);
        long r1Start = r1.createdAtUtc();
        long r2Start = r2.createdAtUtc();
        ReceiptBatchResponse resp = receipts("req-r1", "op-1",
                item(r1, r1Start + 6 * 60_000L),
                item(r2, r2Start + 6 * 60_000L));
        java.util.Map<String, ReceiptItemResult> byId = new java.util.HashMap<>();
        for (ReceiptItemResult itemResult : resp.results()) {
            byId.put(itemResult.reservationId(), itemResult);
        }
        assertEquals("LATE_RECEIPT", byId.get(r1.reservationId()).result(),
                "历史预占沿用 5 分钟快照窗口");
        assertEquals("AT_OR_AFTER_WINDOW_END", byId.get(r1.reservationId()).lateReason());
        assertEquals("ATTRIBUTED", byId.get(r2.reservationId()).result(),
                "新预占使用 10 分钟新窗口");
        assertEquals(1, byId.get(r1.reservationId()).windowVersion());
        assertEquals(5, byId.get(r1.reservationId()).attributionWindowMinutes());
        assertEquals(2, byId.get(r2.reservationId()).windowVersion());
        assertEquals(10, byId.get(r2.reservationId()).attributionWindowMinutes());
    }

    // ---- 幂等 ----

    @Test
    @DisplayName("回执幂等：同键同参重放首次完整快照；异参 409")
    void receiptIdempotency_replaysSnapshot_conflictOnDifferentParams() {
        service.createCampaign(campaign("req-c", "c1", 10));
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        ReservationResponse r2 = apply("req-a2", "c1", "v2");
        long start = BASE.toEpochMilli();

        ReceiptBatchRequest req = new ReceiptBatchRequest("key-1", "op-1", List.of(
                item(r2, start + 2_000L), item(r1, start + 1_000L)));
        ReceiptBatchResponse first = service.submitReceipts(req);
        ReceiptBatchResponse replay = service.submitReceipts(req);

        // 首次完整快照原样重放（含受理时刻、明细顺序、流水编号）
        assertEquals(first.receivedAtUtc(), replay.receivedAtUtc());
        assertEquals(first.attributedCount(), replay.attributedCount());
        assertEquals(first.results().size(), replay.results().size());
        for (int i = 0; i < first.results().size(); i++) {
            ReceiptItemResult a = first.results().get(i);
            ReceiptItemResult b = replay.results().get(i);
            assertEquals(a.reservationId(), b.reservationId());
            assertEquals(a.result(), b.result());
            assertEquals(a.windowStartUtc(), b.windowStartUtc());
            assertEquals(a.receiptAtUtc(), b.receiptAtUtc());
            assertEquals(a.deltaFromStartMillis(), b.deltaFromStartMillis());
            assertEquals(a.deltaToEndMillis(), b.deltaToEndMillis());
        }
        // 重放不新增流水、不重复结算
        assertEquals(Integer.valueOf(2), jdbc.queryForObject(
                "SELECT COUNT(*) FROM attribution_receipt WHERE request_id = 'key-1'",
                Integer.class));
        assertEquals(2, service.queryQuota("c1", null, DAY).usedTotal());

        // 同键异参（不同操作者/不同回执时刻/不同集合）→ 409
        assert409(() -> service.submitReceipts(new ReceiptBatchRequest("key-1", "op-2",
                List.of(item(r1, start + 1_000L), item(r2, start + 2_000L)))));
        assert409(() -> service.submitReceipts(new ReceiptBatchRequest("key-1", "op-1",
                List.of(item(r1, start + 9_000L), item(r2, start + 2_000L)))));
        assert409(() -> service.submitReceipts(new ReceiptBatchRequest("key-1", "op-1",
                List.of(item(r1, start + 1_000L)))));
        // 同键用于其他操作类型 → 409
        assert409(() -> service.confirm(r1.reservationId(),
                new ReservationActionRequest("key-1")));
    }

    private void assert409(Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(409, ex.getStatus().value());
    }

    // ---- 只读明细/历史/诊断 ----

    @Test
    @DisplayName("只读查询：明细、回执历史、诊断的数量/比例正确，且不触发结算不改状态")
    void readOnlyQueries_doNotSettleOrMutate() {
        service.createCampaign(campaign("req-c", "c1", 10));
        long start = BASE.toEpochMilli();
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        receipts("req-r1", "op-1", item(r1, start + 1_000L));
        ReservationResponse r2 = apply("req-a2", "c1", "v2");
        // r2 不提交回执；推进到其到期之后，但不调用任何会结算的接口
        mutableClock().advanceMillis(61_000L);

        // 明细/回执历史严格只读：r2 在库里仍是 RESERVED
        ReservationDetailResponse detail = service.getReservationDetail(r2.reservationId());
        assertEquals("RESERVED", detail.reservation().status());
        assertEquals(10, detail.windowMinutes());
        assertEquals(r2.createdAtUtc(), detail.windowStartUtc());
        assertEquals(r2.createdAtUtc() + 10 * 60_000L, detail.windowEndUtc());
        assertTrue(detail.receipts().isEmpty());
        assertEquals(Integer.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM attribution_receipt WHERE reservation_id = ?",
                Integer.class, r2.reservationId()));

        List<ReceiptItemResult> history = service.listReceipts(r1.reservationId());
        assertEquals(1, history.size());
        assertEquals("ATTRIBUTED", history.get(0).result());
        assertEquals(start + 1_000L, history.get(0).receiptAtUtc());

        // 诊断同样不结算：RESERVED 计数包含已到期但未结算的 r2
        CampaignDiagnosticsResponse diag = service.getCampaignDiagnostics("c1", 100);
        assertEquals(10, diag.attributionWindowMinutes());
        assertEquals(1, diag.windowVersion(), "未调整窗口，仍为 V1");
        assertEquals(2, diag.totalReservations());
        assertEquals(1, diag.reservationCounts().get("ATTRIBUTED"));
        assertEquals(1, diag.reservationCounts().get("RESERVED"));
        assertEquals(1, diag.totalReceipts());
        assertEquals(1, diag.receiptCounts().get("ATTRIBUTED"));
        assertEquals(1.0, diag.attributedRatio(), 0.0);
        assertEquals(1, diag.recentReceipts().size());

        ApiException notFound = assertThrows(ApiException.class,
                () -> service.getReservationDetail("missing"));
        assertEquals(404, notFound.getStatus().value());
    }

    @Test
    @DisplayName("HTTP：窗口外回执 200 且记 LATE_RECEIPT；归属不匹配 422 且返回可区分 code")
    void http_semanticsForReceipts() throws Exception {
        service.createCampaign(campaign("req-c", "c1", 1));
        ReservationResponse r = apply("req-a1", "c1", "v1");
        long start = BASE.toEpochMilli();

        mockMvc.perform(post("/api/exposure/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-r1\",\"operatorId\":\"op-1\",\"receipts\":["
                                + "{\"reservationId\":\"" + r.reservationId() + "\","
                                + "\"campaignId\":\"c1\",\"visitorId\":\"v1\","
                                + "\"receiptAtUtc\":" + (start + 60_000L) + "}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lateCount").value(1))
                .andExpect(jsonPath("$.results[0].result").value("LATE_RECEIPT"))
                .andExpect(jsonPath("$.results[0].lateReason")
                        .value("AT_OR_AFTER_WINDOW_END"));

        mockMvc.perform(post("/api/exposure/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-r2\",\"operatorId\":\"op-1\",\"receipts\":["
                                + "{\"reservationId\":\"" + r.reservationId() + "\","
                                + "\"campaignId\":\"cX\",\"visitorId\":\"v1\","
                                + "\"receiptAtUtc\":" + start + "}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("CAMPAIGN_MISMATCH"));
    }
}
