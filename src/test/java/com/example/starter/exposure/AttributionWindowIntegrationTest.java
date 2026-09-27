package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.AttributionDiagnosticsResponse;
import com.example.starter.exposure.web.BatchReceiptRequest;
import com.example.starter.exposure.web.BatchReceiptResponse;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReceiptItem;
import com.example.starter.exposure.web.ReceiptRequest;
import com.example.starter.exposure.web.ReceiptResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公告归因窗口 H2（MODE=MySQL）集成测试：窗口裁决主流程、边界、失败分支、
 * 批量事务、幂等、窗口版本不可变与只读查询。
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

    static final Instant BASE = Instant.parse("2026-09-22T10:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    static final long MINUTE = 60_000L;

    /** 以可控时钟覆盖生产系统时钟。 */
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
        jdbc.update("DELETE FROM exposure_receipt");
        jdbc.update("DELETE FROM campaign_window_history");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private void createCampaign(String requestId, String campaignId, Integer windowMinutes) {
        service.createCampaign(new CreateCampaignRequest(requestId, campaignId, 10, 5, windowMinutes));
    }

    private ReservationResponse apply(String requestId, String campaignId, String visitorId) {
        return service.apply(new ApplyExposureRequest(requestId, campaignId, visitorId));
    }

    private ReceiptResponse receipt(String requestId, String reservationId, long receiptAtUtc) {
        return service.submitReceipt(reservationId, new ReceiptRequest(requestId, "op-1", receiptAtUtc));
    }

    @Test
    @DisplayName("创建公告：归因窗口缺省 1440 分钟、可配置，窗口版本从 1 开始并写入历史")
    void createCampaign_windowDefaultsAndExplicit() {
        CampaignResponse def = service.createCampaign(
                new CreateCampaignRequest("req-c1", "c1", 10, 5));
        assertEquals(1440, def.attributionWindowMinutes());
        assertEquals(1, def.windowVersion());

        CampaignResponse explicit = service.createCampaign(
                new CreateCampaignRequest("req-c2", "c2", 10, 5, 30));
        assertEquals(30, explicit.attributionWindowMinutes());
        assertEquals(1, explicit.windowVersion());

        List<WindowVersionResponse> history = service.listWindowHistory("c2");
        assertEquals(1, history.size());
        assertEquals(1, history.get(0).windowVersion());
        assertEquals(30, history.get(0).windowMinutes());
        assertEquals(BASE.toEpochMilli(), history.get(0).changedAtUtc());

        // 预占单快照申请时刻的窗口版本与分钟数
        ReservationResponse r = apply("req-a1", "c2", "v1");
        assertEquals(1, r.windowVersion());
        assertEquals(30, r.windowMinutes());
    }

    @Test
    @DisplayName("窗口取值边界：1 与 1440 分钟合法，0 与 1441 返回 400")
    void windowValidation_bounds() throws Exception {
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w1\",\"campaignId\":\"cw1\",\"dailyTotalCap\":5,"
                                + "\"perVisitorDailyCap\":5,\"attributionWindowMinutes\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attributionWindowMinutes").value(1));
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w2\",\"campaignId\":\"cw2\",\"dailyTotalCap\":5,"
                                + "\"perVisitorDailyCap\":5,\"attributionWindowMinutes\":1440}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attributionWindowMinutes").value(1440));
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w3\",\"campaignId\":\"cw3\",\"dailyTotalCap\":5,"
                                + "\"perVisitorDailyCap\":5,\"attributionWindowMinutes\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w4\",\"campaignId\":\"cw4\",\"dailyTotalCap\":5,"
                                + "\"perVisitorDailyCap\":5,\"attributionWindowMinutes\":1441}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/exposure/campaigns/cw1/window")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w5\",\"operator\":\"op-1\","
                                + "\"attributionWindowMinutes\":1441}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("窗口内回执：计入归因并结算为 CONFIRMED，额度保持占用，响应含实际/要求时长")
    void receiptWithinWindow_attributesAndConfirms() {
        createCampaign("req-c1", "c1", 30);
        ReservationResponse r = apply("req-a1", "c1", "v1");

        ReceiptResponse receipt = receipt("req-r1", r.reservationId(), BASE.toEpochMilli() + 10 * MINUTE);

        assertEquals("ATTRIBUTED", receipt.result().name());
        assertEquals(r.reservationId(), receipt.reservationId());
        assertEquals("c1", receipt.campaignId());
        assertEquals("v1", receipt.visitorId());
        assertEquals("op-1", receipt.operator());
        assertEquals(BASE.toEpochMilli(), receipt.reservedAtUtc());
        assertEquals(1, receipt.windowVersion());
        assertEquals(30, receipt.windowMinutes());
        assertEquals(10 * MINUTE, receipt.delayMillis());
        assertEquals(30 * MINUTE, receipt.windowMillis());
        assertNull(receipt.lateByMillis());
        assertNotNull(receipt.receiptId());

        ReservationResponse detail = service.getReservation(r.reservationId());
        assertEquals("CONFIRMED", detail.status().name());
        assertEquals(BASE.toEpochMilli(), detail.terminalAtUtc());

        QuotaResponse quota = service.queryQuota("c1", "v1", DAY);
        assertEquals(1, quota.usedTotal());
        assertEquals(1, quota.usedVisitor());
    }

    @Test
    @DisplayName("窗口边界左闭右开：等于预占时刻计入归因，等于窗口终点记 LATE_RECEIPT")
    void receiptWindowBoundaries_leftClosedRightOpen() {
        createCampaign("req-c1", "c1", 30);
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        ReservationResponse r2 = apply("req-a2", "c1", "v2");
        ReservationResponse r3 = apply("req-a3", "c1", "v3");

        // 左闭：receiptAt == reservedAt 计入归因
        ReceiptResponse atStart = receipt("req-r1", r1.reservationId(), BASE.toEpochMilli());
        assertEquals("ATTRIBUTED", atStart.result().name());
        assertEquals(0L, atStart.delayMillis());
        assertNull(atStart.lateByMillis());

        // 右开：receiptAt == reservedAt + window 记迟到，差额为 0
        ReceiptResponse atEnd = receipt("req-r2", r2.reservationId(), BASE.toEpochMilli() + 30 * MINUTE);
        assertEquals("LATE_RECEIPT", atEnd.result().name());
        assertEquals(30 * MINUTE, atEnd.delayMillis());
        assertEquals(0L, atEnd.lateByMillis());

        // 窗口外 1 毫秒：迟到差额 1 毫秒
        ReceiptResponse beyond = receipt("req-r3", r3.reservationId(), BASE.toEpochMilli() + 30 * MINUTE + 1);
        assertEquals("LATE_RECEIPT", beyond.result().name());
        assertEquals(1L, beyond.lateByMillis());
    }

    @Test
    @DisplayName("窗口外回执：记录 LATE_RECEIPT，不确认也不释放配额，之后仍可确认")
    void lateReceipt_doesNotConfirmOrRelease() {
        createCampaign("req-c1", "c1", 30);
        ReservationResponse r = apply("req-a1", "c1", "v1");

        ReceiptResponse late = receipt("req-r1", r.reservationId(), BASE.toEpochMilli() + 45 * MINUTE);
        assertEquals("LATE_RECEIPT", late.result().name());
        assertEquals(15 * MINUTE, late.lateByMillis());

        // 预占未被确认、配额未被释放
        ReservationResponse detail = service.getReservation(r.reservationId());
        assertEquals("RESERVED", detail.status().name());
        assertNull(detail.terminalAtUtc());
        QuotaResponse quota = service.queryQuota("c1", "v1", DAY);
        assertEquals(1, quota.usedTotal());
        assertEquals(1, quota.usedVisitor());

        // 迟到回执不占用确认机会：之后仍可正常确认
        ReservationResponse confirmed = service.confirm(r.reservationId(),
                new ReservationActionRequest("req-k1"));
        assertEquals("CONFIRMED", confirmed.status().name());
    }

    @Test
    @DisplayName("回执 422 分支：预占不存在、已取消、回执时刻早于预占；失败不占幂等键")
    void receiptRejected_422_andFailureDoesNotOccupyKey() {
        createCampaign("req-c1", "c1", 30);
        ReservationResponse r = apply("req-a1", "c1", "v1");

        // 预占不存在 → 422
        assert422(() -> receipt("req-r1", "nonexistent", BASE.toEpochMilli()));

        // 已取消 → 422
        service.cancel(r.reservationId(), new ReservationActionRequest("req-x1"));
        assert422(() -> receipt("req-r2", r.reservationId(), BASE.toEpochMilli() + MINUTE));

        // 回执时刻早于预占 → 422，失败不占键：同键修正参数后成功
        ReservationResponse r2 = apply("req-a2", "c1", "v2");
        assert422(() -> receipt("req-r3", r2.reservationId(), BASE.toEpochMilli() - 1));
        ReceiptResponse retried = receipt("req-r3", r2.reservationId(), BASE.toEpochMilli() + MINUTE);
        assertEquals("ATTRIBUTED", retried.result().name());
    }

    @Test
    @DisplayName("过期预占上的回执返回 409：过期按提交顺序先裁决并释放额度")
    void receiptOnExpired_conflict409() {
        createCampaign("req-c1", "c1", 30);
        ReservationResponse r = apply("req-a1", "c1", "v1");

        mutableClock().advanceMillis(60_000L);

        assert409(() -> receipt("req-r1", r.reservationId(), BASE.toEpochMilli() + 30_000L));
        ReservationResponse detail = service.getReservation(r.reservationId());
        assertEquals("EXPIRED", detail.status().name());
        QuotaResponse quota = service.queryQuota("c1", "v1", DAY);
        assertEquals(0, quota.usedTotal());
        assertEquals(0, quota.usedVisitor());
        assertEquals(0, service.listReceipts(r.reservationId()).size());
    }

    @Test
    @DisplayName("回执幂等：同键同参重放首次快照，异参 409，回执只落一行")
    void receiptIdempotency_replaySameSnapshot_conflictOnDifferentParams() {
        createCampaign("req-c1", "c1", 30);
        ReservationResponse r = apply("req-a1", "c1", "v1");

        ReceiptResponse first = receipt("key-r1", r.reservationId(), BASE.toEpochMilli() + MINUTE);
        ReceiptResponse replay = receipt("key-r1", r.reservationId(), BASE.toEpochMilli() + MINUTE);
        assertEquals(first.receiptId(), replay.receiptId());
        assertEquals(first.result(), replay.result());
        assertEquals(first.createdAtUtc(), replay.createdAtUtc());

        // 同键异参（不同回执时刻、不同操作者）→ 409
        assert409(() -> receipt("key-r1", r.reservationId(), BASE.toEpochMilli() + 2 * MINUTE));
        assert409(() -> service.submitReceipt(r.reservationId(),
                new ReceiptRequest("key-r1", "op-2", BASE.toEpochMilli() + MINUTE)));

        assertEquals(1, service.listReceipts(r.reservationId()).size());
    }

    @Test
    @DisplayName("同一预占多笔窗口内回执：只结算一次，额度不重复占用")
    void duplicateReceipts_settleOnlyOnce() {
        createCampaign("req-c1", "c1", 30);
        ReservationResponse r = apply("req-a1", "c1", "v1");

        ReceiptResponse first = receipt("req-r1", r.reservationId(), BASE.toEpochMilli() + MINUTE);
        ReceiptResponse second = receipt("req-r2", r.reservationId(), BASE.toEpochMilli() + 2 * MINUTE);
        assertEquals("ATTRIBUTED", first.result().name());
        assertEquals("ATTRIBUTED", second.result().name());
        assertTrue(!first.receiptId().equals(second.receiptId()));

        ReservationResponse detail = service.getReservation(r.reservationId());
        assertEquals("CONFIRMED", detail.status().name());
        assertEquals(BASE.toEpochMilli(), detail.terminalAtUtc(), "终态时刻保持首次结算值");

        QuotaResponse quota = service.queryQuota("c1", "v1", DAY);
        assertEquals(1, quota.usedTotal());
        assertEquals(1, quota.usedVisitor());
        assertEquals(2, service.listReceipts(r.reservationId()).size());
    }

    @Test
    @DisplayName("调整窗口：版本递增、历史可查；旧预占按快照裁决，历史回执不回算")
    void updateWindow_versionsSnapshotAndNoRecompute() {
        createCampaign("req-c1", "c1", 30);
        ReservationResponse r1 = apply("req-a1", "c1", "v1");

        // 旧窗口（30 分钟）外的回执：LATE
        ReceiptResponse late = receipt("req-r1", r1.reservationId(), BASE.toEpochMilli() + 31 * MINUTE);
        assertEquals("LATE_RECEIPT", late.result().name());

        // 调整窗口为 120 分钟：版本 2
        CampaignResponse updated = service.updateWindow("c1",
                new UpdateWindowRequest("req-w1", "op-9", 120));
        assertEquals(120, updated.attributionWindowMinutes());
        assertEquals(2, updated.windowVersion());

        List<WindowVersionResponse> history = service.listWindowHistory("c1");
        assertEquals(2, history.size());
        assertEquals(1, history.get(0).windowVersion());
        assertEquals(30, history.get(0).windowMinutes());
        assertEquals(2, history.get(1).windowVersion());
        assertEquals(120, history.get(1).windowMinutes());

        // 历史回执不回算：仍 LATE，快照窗口 30 分钟
        ReceiptResponse reread = service.getReceipt(late.receiptId());
        assertEquals("LATE_RECEIPT", reread.result().name());
        assertEquals(30, reread.windowMinutes());
        assertEquals(1, reread.windowVersion());

        // 旧预占按申请时快照（30 分钟）裁决，即使当前窗口已放宽到 120 分钟
        ReceiptResponse stillLate = receipt("req-r2", r1.reservationId(), BASE.toEpochMilli() + 40 * MINUTE);
        assertEquals("LATE_RECEIPT", stillLate.result().name());
        assertEquals(30, stillLate.windowMinutes());

        // 新预占快照新窗口（120 分钟、版本 2）：60 分钟处回执计入归因
        ReservationResponse r2 = apply("req-a2", "c1", "v2");
        assertEquals(2, r2.windowVersion());
        assertEquals(120, r2.windowMinutes());
        ReceiptResponse attributed = receipt("req-r3", r2.reservationId(), BASE.toEpochMilli() + 60 * MINUTE);
        assertEquals("ATTRIBUTED", attributed.result().name());
        assertEquals(120, attributed.windowMinutes());
        assertEquals("CONFIRMED", service.getReservation(r2.reservationId()).status().name());
    }

    @Test
    @DisplayName("批量回执：整批一次事务提交，结果按提交顺序，重放返回首次快照")
    void batchReceipts_mixedResults_singleTransactionAndReplay() {
        createCampaign("req-c1", "c1", 30);
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        ReservationResponse r2 = apply("req-a2", "c1", "v2");
        ReservationResponse r3 = apply("req-a3", "c1", "v3");

        BatchReceiptRequest batch = new BatchReceiptRequest("key-b1", "op-1", "c1", List.of(
                new ReceiptItem(r1.reservationId(), BASE.toEpochMilli() + 10 * MINUTE),
                new ReceiptItem(r2.reservationId(), BASE.toEpochMilli() + 90 * MINUTE),
                new ReceiptItem(r3.reservationId(), BASE.toEpochMilli())));
        BatchReceiptResponse response = service.submitBatchReceipts(batch);

        assertEquals(3, response.totalCount());
        assertEquals(2, response.attributedCount());
        assertEquals(1, response.lateCount());
        assertEquals("ATTRIBUTED", response.results().get(0).result().name());
        assertEquals("LATE_RECEIPT", response.results().get(1).result().name());
        assertEquals("ATTRIBUTED", response.results().get(2).result().name());
        assertEquals(r1.reservationId(), response.results().get(0).reservationId());
        assertEquals(r2.reservationId(), response.results().get(1).reservationId());
        assertEquals(r3.reservationId(), response.results().get(2).reservationId());

        assertEquals("CONFIRMED", service.getReservation(r1.reservationId()).status().name());
        assertEquals("RESERVED", service.getReservation(r2.reservationId()).status().name());
        assertEquals("CONFIRMED", service.getReservation(r3.reservationId()).status().name());

        // 同键重放：返回首次完整快照，不重复落回执
        BatchReceiptResponse replay = service.submitBatchReceipts(batch);
        assertEquals(response.results().get(0).receiptId(), replay.results().get(0).receiptId());
        assertEquals(response.results().get(2).receiptId(), replay.results().get(2).receiptId());
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM exposure_receipt", Integer.class);
        assertEquals(3, rows);

        // 同键异参（不同对象集合）→ 409
        BatchReceiptRequest different = new BatchReceiptRequest("key-b1", "op-1", "c1", List.of(
                new ReceiptItem(r1.reservationId(), BASE.toEpochMilli() + 11 * MINUTE),
                new ReceiptItem(r2.reservationId(), BASE.toEpochMilli() + 90 * MINUTE),
                new ReceiptItem(r3.reservationId(), BASE.toEpochMilli())));
        assert409(() -> service.submitBatchReceipts(different));
    }

    @Test
    @DisplayName("批量回执归属不匹配：整批 422 不结算，不留半成品，失败不占键")
    void batchReceipts_ownershipMismatch_rollsBackEntireBatch() {
        createCampaign("req-c1", "c1", 30);
        createCampaign("req-c2", "c2", 30);
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        ReservationResponse r2 = apply("req-a2", "c2", "v2");

        BatchReceiptRequest mixed = new BatchReceiptRequest("key-b1", "op-1", "c1", List.of(
                new ReceiptItem(r1.reservationId(), BASE.toEpochMilli() + MINUTE),
                new ReceiptItem(r2.reservationId(), BASE.toEpochMilli() + MINUTE)));
        assert422(() -> service.submitBatchReceipts(mixed));

        // 整批回滚：无回执落库，r1 未确认，额度未变
        assertEquals(0, service.listReceipts(r1.reservationId()).size());
        assertEquals(0, service.listReceipts(r2.reservationId()).size());
        assertEquals("RESERVED", service.getReservation(r1.reservationId()).status().name());
        assertEquals(1, service.queryQuota("c1", "v1", DAY).usedTotal());

        // 失败不占键：同键修正为仅含本公告预占后成功
        BatchReceiptResponse fixed = service.submitBatchReceipts(new BatchReceiptRequest(
                "key-b1", "op-1", "c1",
                List.of(new ReceiptItem(r1.reservationId(), BASE.toEpochMilli() + MINUTE))));
        assertEquals(1, fixed.attributedCount());
        assertEquals("CONFIRMED", service.getReservation(r1.reservationId()).status().name());
    }

    @Test
    @DisplayName("批量回执任一项失败（已取消/时刻早于预占/不存在）：整批回滚并返回可区分原因")
    void batchReceipts_anyItemFailure_rollsBackWithDistinctReason() {
        createCampaign("req-c1", "c1", 30);
        ReservationResponse r1 = apply("req-a1", "c1", "v1");
        ReservationResponse r2 = apply("req-a2", "c1", "v2");
        ReservationResponse r3 = apply("req-a3", "c1", "v3");
        service.cancel(r2.reservationId(), new ReservationActionRequest("req-x1"));

        // 含已取消预占：整批 422，消息指明具体预占
        ApiException cancelled = assertThrows(ApiException.class,
                () -> service.submitBatchReceipts(new BatchReceiptRequest("key-b1", "op-1", "c1",
                        List.of(new ReceiptItem(r1.reservationId(), BASE.toEpochMilli() + MINUTE),
                                new ReceiptItem(r2.reservationId(), BASE.toEpochMilli() + MINUTE)))));
        assertEquals(422, cancelled.getStatus().value());
        assertTrue(cancelled.getMessage().contains(r2.reservationId()));

        // 含不存在的预占：整批 422
        assert422(() -> service.submitBatchReceipts(new BatchReceiptRequest("key-b2", "op-1", "c1",
                List.of(new ReceiptItem(r1.reservationId(), BASE.toEpochMilli() + MINUTE),
                        new ReceiptItem("missing", BASE.toEpochMilli() + MINUTE)))));

        // 含回执时刻早于预占：整批 422
        assert422(() -> service.submitBatchReceipts(new BatchReceiptRequest("key-b3", "op-1", "c1",
                List.of(new ReceiptItem(r1.reservationId(), BASE.toEpochMilli() + MINUTE),
                        new ReceiptItem(r3.reservationId(), BASE.toEpochMilli() - 1)))));

        // 整批回滚：r1/r3 均未确认、无回执落库；r2 已取消释放额度，占用仅剩 r1/r3 两笔
        assertEquals("RESERVED", service.getReservation(r1.reservationId()).status().name());
        assertEquals("RESERVED", service.getReservation(r3.reservationId()).status().name());
        assertEquals(0, service.listReceipts(r1.reservationId()).size());
        assertEquals(0, service.listReceipts(r3.reservationId()).size());
        assertEquals(2, service.queryQuota("c1", null, DAY).usedTotal());
    }

    @Test
    @DisplayName("回执明细/历史/诊断查询：只读、排序稳定，读取不改状态")
    void receiptQueriesAndDiagnostics_readOnlyStableOrdering() {
        createCampaign("req-c1", "c1", 30);
        ReservationResponse r = apply("req-a1", "c1", "v1");
        ReceiptResponse first = receipt("req-r1", r.reservationId(), BASE.toEpochMilli() + MINUTE);
        ReceiptResponse second = receipt("req-r2", r.reservationId(),
                BASE.toEpochMilli() + 26 * 60 * MINUTE);

        // 明细
        ReceiptResponse detail = service.getReceipt(first.receiptId());
        assertEquals(first.receiptId(), detail.receiptId());
        assertEquals("ATTRIBUTED", detail.result().name());
        assert404(() -> service.getReceipt("missing"));

        // 历史按提交顺序升序
        List<ReceiptResponse> history = service.listReceipts(r.reservationId());
        assertEquals(2, history.size());
        assertEquals(first.receiptId(), history.get(0).receiptId());
        assertEquals(second.receiptId(), history.get(1).receiptId());
        assertEquals("LATE_RECEIPT", history.get(1).result().name());

        // 诊断：全部范围与按 UTC 日过滤（第二笔回执落在次日 12:00）
        AttributionDiagnosticsResponse all = service.attributionDiagnostics("c1", null);
        assertEquals(1, all.attributedCount());
        assertEquals(1, all.lateReceiptCount());
        assertEquals(2, all.totalReceipts());
        assertEquals(1, all.currentWindowVersion());
        assertEquals(30, all.currentWindowMinutes());
        assertNull(all.utcDate());

        AttributionDiagnosticsResponse day = service.attributionDiagnostics("c1", DAY);
        assertEquals(1, day.attributedCount());
        assertEquals(0, day.lateReceiptCount());
        AttributionDiagnosticsResponse nextDay = service.attributionDiagnostics(
                "c1", DAY.plusDays(1));
        assertEquals(0, nextDay.attributedCount());
        assertEquals(1, nextDay.lateReceiptCount());

        // 只读：查询不结算过期、不改状态（推进时钟超过 TTL 后读取仍不回写）
        ReservationResponse pending = apply("req-a2", "c1", "v2");
        mutableClock().advanceMillis(120_000L);
        service.getReceipt(first.receiptId());
        service.listReceipts(r.reservationId());
        service.listReceipts(pending.reservationId());
        service.attributionDiagnostics("c1", null);
        service.listWindowHistory("c1");
        String status = jdbc.queryForObject(
                "SELECT status FROM exposure_reservation WHERE reservation_id = ?",
                String.class, r.reservationId());
        assertEquals("CONFIRMED", status, "只读查询不得改写预占状态");
        String pendingStatus = jdbc.queryForObject(
                "SELECT status FROM exposure_reservation WHERE reservation_id = ?",
                String.class, pending.reservationId());
        assertEquals("RESERVED", pendingStatus, "只读查询不得结算过期预占");
    }

    @Test
    @DisplayName("HTTP 语义：单笔/批量回执 201，窗口外回执 422，诊断查询 200")
    void httpSemantics_receipts() throws Exception {
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"campaignId\":\"ch\",\"dailyTotalCap\":10,"
                                + "\"perVisitorDailyCap\":5,\"attributionWindowMinutes\":30}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attributionWindowMinutes").value(30))
                .andExpect(jsonPath("$.windowVersion").value(1));

        String reservationId = mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h2\",\"campaignId\":\"ch\",\"visitorId\":\"u1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.windowMinutes").value(30))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"reservationId\":\"", "").replaceAll("\".*", "");

        mockMvc.perform(post("/api/exposure/reservations/" + reservationId + "/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h3\",\"operator\":\"op-1\",\"receiptAtUtc\":"
                                + (BASE.toEpochMilli() + MINUTE) + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("ATTRIBUTED"))
                .andExpect(jsonPath("$.delayMillis").value(MINUTE))
                .andExpect(jsonPath("$.windowMillis").value(30 * MINUTE));

        // 已确认后窗口外回执仍记录 LATE_RECEIPT（201）
        mockMvc.perform(post("/api/exposure/reservations/" + reservationId + "/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h4\",\"operator\":\"op-1\",\"receiptAtUtc\":"
                                + (BASE.toEpochMilli() + 60 * MINUTE) + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("LATE_RECEIPT"))
                .andExpect(jsonPath("$.lateByMillis").value(30 * MINUTE));

        // 不存在的预占 → 422
        mockMvc.perform(post("/api/exposure/reservations/missing/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h5\",\"operator\":\"op-1\",\"receiptAtUtc\":"
                                + BASE.toEpochMilli() + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));

        mockMvc.perform(post("/api/exposure/receipts/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h6\",\"operator\":\"op-1\",\"campaignId\":\"ch\","
                                + "\"items\":[{\"reservationId\":\"" + reservationId
                                + "\",\"receiptAtUtc\":" + (BASE.toEpochMilli() + 2 * MINUTE) + "}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.attributedCount").value(1));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/exposure/campaigns/ch/attribution"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributedCount").value(2))
                .andExpect(jsonPath("$.lateReceiptCount").value(1))
                .andExpect(jsonPath("$.totalReceipts").value(3));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/exposure/campaigns/ch/window-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].windowVersion").value(1))
                .andExpect(jsonPath("$[0].windowMinutes").value(30));
    }

    private void assert422(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(422, ex.getStatus().value());
    }

    private void assert409(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(409, ex.getStatus().value());
    }

    private void assert404(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(404, ex.getStatus().value());
    }
}
