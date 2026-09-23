package com.example.starter.exposure;

import com.example.starter.exposure.exposure.BudgetService;
import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.AccountStateResponse;
import com.example.starter.exposure.web.ActivateTransferRequest;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.BudgetAccountResponse;
import com.example.starter.exposure.web.CreateBudgetAccountRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.PreviewTransferRequest;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.TransferLineRequest;
import com.example.starter.exposure.web.TransferPreviewResponse;
import com.example.starter.exposure.web.TransferResponse;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 活动预算转移 H2（MODE=MySQL）集成测试：闭环守恒、在途冻结、迟到回执、
 * 整体回滚、幂等、窗口/版本/规则边界与证据查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BudgetTransferIntegrationTest {

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
    /** 投放窗口：[BASE+1h, BASE+3h)，激活时窗口必须尚未开始。 */
    static final long WS = BASE.toEpochMilli() + 3_600_000L;
    static final long WE = WS + 7_200_000L;

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock mutableClock() {
            return new MutableClock(BASE);
        }
    }

    @Autowired
    BudgetService budgetService;
    @Autowired
    ExposureService exposureService;
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
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM budget_transfer_line");
        jdbc.update("DELETE FROM budget_transfer");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign_budget_account");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    // ---- 测试辅助 ----

    private BudgetAccountResponse account(String reqId, String campaignId, int budget) {
        return budgetService.createAccount(new CreateBudgetAccountRequest(
                reqId, campaignId, "tenant-1", WS, WE, "audience-a", budget));
    }

    private TransferLineRequest line(String source, String target, int amount) {
        return new TransferLineRequest(source, target, amount, 0, 0);
    }

    private TransferResponse activate(String reqId, String key, TransferLineRequest... lines) {
        return budgetService.activate(new ActivateTransferRequest(reqId, key, List.of(lines)));
    }

    private Map<String, AccountStateResponse> byCampaign(List<AccountStateResponse> states) {
        return states.stream().collect(Collectors.toMap(AccountStateResponse::campaignId,
                Function.identity()));
    }

    private void assertIdentity(String campaignId) {
        BudgetAccountResponse a = budgetService.getAccount(campaignId);
        assertEquals(a.budget(), a.transferable() + a.inFlight() + a.confirmed(),
                "预算恒等式 总预算=可转+在途+已确认 必须成立: " + campaignId);
    }

    private void assertStatus(int expected, Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(expected, ex.getStatus().value(), ex.getMessage());
    }

    // ---- 用例 ----

    @Test
    @DisplayName("闭环转移 A→B→C→A：全局总额守恒、各活动非负、逐活动增版并冻结快照")
    void closedLoopTransfer_conservesTotalAndIncrementsVersions() {
        account("req-a", "c1", 100);
        account("req-b", "c2", 50);
        account("req-c", "c3", 30);

        TransferResponse response = activate("req-t1", "tk-1",
                line("c1", "c2", 40), line("c2", "c3", 20), line("c3", "c1", 10));

        assertEquals("tk-1", response.transferKey());
        assertEquals("ACTIVATED", response.status());
        Map<String, AccountStateResponse> after = byCampaign(response.after());
        assertEquals(70, after.get("c1").budget());
        assertEquals(70, after.get("c2").budget());
        assertEquals(40, after.get("c3").budget());
        int afterSum = response.after().stream().mapToInt(AccountStateResponse::budget).sum();
        int beforeSum = response.before().stream().mapToInt(AccountStateResponse::budget).sum();
        assertEquals(180, beforeSum);
        assertEquals(beforeSum, afterSum, "全局总额必须守恒");
        assertTrue(response.after().stream().allMatch(s -> s.budget() >= 0), "各活动预算必须非负");
        assertTrue(response.after().stream().allMatch(s -> s.version() == 1), "逐活动增版");

        // 落库后的账本与快照一致，恒等式成立
        assertIdentity("c1");
        assertIdentity("c2");
        assertIdentity("c3");
        assertEquals(70, budgetService.getAccount("c1").budget());
        assertEquals(1, budgetService.getAccount("c1").version());

        // 证据查询：冻结明细按（源,目标）稳定排序
        TransferResponse evidence = budgetService.getTransfer("tk-1");
        assertEquals(response, evidence);
        assertEquals(List.of("c1", "c2", "c3"),
                evidence.lines().stream().map(l -> l.sourceCampaignId()).toList());
        assertEquals(List.of("c1", "c2", "c3"),
                evidence.before().stream().map(AccountStateResponse::campaignId).toList());
    }

    @Test
    @DisplayName("预览按完整后态返回且不写数据：预算、版本、转移单均不变")
    void preview_returnsPostStateWithoutWrites() {
        account("req-a", "c1", 100);
        account("req-b", "c2", 50);

        TransferPreviewResponse preview = budgetService.preview(new PreviewTransferRequest(
                List.of(line("c1", "c2", 30), line("c2", "c1", 10))));

        Map<String, AccountStateResponse> post = byCampaign(preview.accounts());
        assertEquals(80, post.get("c1").budget());
        assertEquals(70, post.get("c2").budget());
        assertEquals(80, post.get("c1").transferable());
        assertEquals(0, post.get("c1").inFlight());
        assertEquals(0, post.get("c1").confirmed());
        assertEquals(List.of("c1", "c2"),
                preview.accounts().stream().map(AccountStateResponse::campaignId).toList());

        // 不写数据
        assertEquals(100, budgetService.getAccount("c1").budget());
        assertEquals(0, budgetService.getAccount("c1").version());
        assertEquals(0, budgetService.getAccount("c2").version());
        Integer transferRows = jdbc.queryForObject("SELECT COUNT(*) FROM budget_transfer",
                Integer.class);
        assertEquals(0, transferRows);
    }

    @Test
    @DisplayName("在途预占冻结：未回执未过期数量不得转走；过期释放后可转")
    void inFlightBudgetIsFrozen_untilExpiryReleases() {
        exposureService.createCampaign(new CreateCampaignRequest("req-cp1", "c1", 100, 100));
        exposureService.createCampaign(new CreateCampaignRequest("req-cp2", "c2", 100, 100));
        account("req-a", "c1", 10);
        account("req-b", "c2", 0);
        account("req-c", "c3", 0);

        // 3 份预占 -> 在途 3，可转 7
        exposureService.apply(new ApplyExposureRequest("req-r1", "c1", "v1"));
        exposureService.apply(new ApplyExposureRequest("req-r2", "c1", "v2"));
        exposureService.apply(new ApplyExposureRequest("req-r3", "c1", "v3"));
        BudgetAccountResponse c1 = budgetService.getAccount("c1");
        assertEquals(3, c1.inFlight());
        assertEquals(7, c1.transferable());

        // 转 8 超出可转余额 -> 422，且失败不占键
        assertStatus(422, () -> activate("req-t1", "tk-1",
                line("c1", "c2", 8), line("c2", "c3", 1)));
        assertEquals(10, budgetService.getAccount("c1").budget());

        // 预占过期后（推进 60 秒，窗口仍未开始），在途释放，8 可转
        mutableClock().advanceMillis(60_000L);
        TransferResponse response = activate("req-t1", "tk-1",
                line("c1", "c2", 8), line("c2", "c3", 1));
        Map<String, AccountStateResponse> after = byCampaign(response.after());
        assertEquals(2, after.get("c1").budget());
        assertEquals(0, after.get("c1").inFlight(), "过期预占在激活时结算释放");
        assertIdentity("c1");
        assertIdentity("c2");
    }

    @Test
    @DisplayName("取消预占释放在途预算，可转余额恢复")
    void cancelReleasesInFlightBudget() {
        exposureService.createCampaign(new CreateCampaignRequest("req-cp1", "c1", 100, 100));
        account("req-a", "c1", 10);
        ReservationResponse r = exposureService.apply(new ApplyExposureRequest("req-r1", "c1", "v1"));
        assertEquals(1, budgetService.getAccount("c1").inFlight());

        exposureService.cancel(r.reservationId(), new ReservationActionRequest("req-x1"));
        BudgetAccountResponse c1 = budgetService.getAccount("c1");
        assertEquals(0, c1.inFlight());
        assertEquals(10, c1.transferable());
        assertIdentity("c1");
    }

    @Test
    @DisplayName("迟到回执归原 campaign：转移前预占在转移后确认，计入原活动已确认")
    void lateReceiptStaysWithOriginalCampaign() {
        exposureService.createCampaign(new CreateCampaignRequest("req-cp1", "c1", 100, 100));
        account("req-a", "c1", 10);
        account("req-b", "c2", 5);
        account("req-c", "c3", 5);
        ReservationResponse r = exposureService.apply(new ApplyExposureRequest("req-r1", "c1", "v1"));
        assertEquals(1, budgetService.getAccount("c1").inFlight());

        // 转移后 c1 预算 10-5=5，在途仍为 1
        activate("req-t1", "tk-1", line("c1", "c2", 5), line("c2", "c3", 1));
        BudgetAccountResponse c1AfterTransfer = budgetService.getAccount("c1");
        assertEquals(5, c1AfterTransfer.budget());
        assertEquals(1, c1AfterTransfer.inFlight());

        // 迟到回执：确认转移前创建的预占，归原 campaign
        exposureService.confirm(r.reservationId(), new ReservationActionRequest("req-k1"));
        BudgetAccountResponse c1 = budgetService.getAccount("c1");
        assertEquals(0, c1.inFlight());
        assertEquals(1, c1.confirmed(), "已确认必须计入原 campaign，不得改挂目标");
        assertEquals(4, c1.transferable());
        assertEquals(0, budgetService.getAccount("c2").confirmed());
        assertEquals(0, budgetService.getAccount("c3").confirmed());
        assertIdentity("c1");
    }

    @Test
    @DisplayName("转移后新预占按新预算校验：预算转空后申请 429，转入方可正常申请")
    void newReservationsValidatedAgainstNewBudget() {
        exposureService.createCampaign(new CreateCampaignRequest("req-cp1", "c1", 100, 100));
        exposureService.createCampaign(new CreateCampaignRequest("req-cp2", "c2", 100, 100));
        account("req-a", "c1", 4);
        account("req-b", "c2", 10);
        account("req-c", "c3", 0);

        activate("req-t1", "tk-1", line("c1", "c2", 4), line("c2", "c3", 1));
        assertEquals(0, budgetService.getAccount("c1").budget());
        assertEquals(13, budgetService.getAccount("c2").budget());

        // c1 预算已转空：新预占 429
        assertStatus(429, () -> exposureService.apply(new ApplyExposureRequest("req-r1", "c1", "v1")));
        // c2 转入后可正常申请
        ReservationResponse ok = exposureService.apply(new ApplyExposureRequest("req-r2", "c2", "v1"));
        assertEquals("RESERVED", ok.status().name());
        assertEquals(1, budgetService.getAccount("c2").inFlight());
        assertIdentity("c2");
    }

    @Test
    @DisplayName("整体回滚：任一明细非法则全部不变，失败不占 requestId 与 transferKey")
    void activationIsAtomic_failureRollsBackAndDoesNotOccupyKeys() {
        account("req-a", "c1", 100);
        account("req-b", "c2", 50);

        // 第二条明细指向不存在的账本 -> 422，整体回滚
        assertStatus(422, () -> activate("req-t1", "tk-1",
                line("c1", "c2", 10), line("c2", "cX", 1)));

        assertEquals(100, budgetService.getAccount("c1").budget());
        assertEquals(50, budgetService.getAccount("c2").budget());
        assertEquals(0, budgetService.getAccount("c1").version());
        assertEquals(0, budgetService.getAccount("c2").version());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM budget_transfer", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'req-t1'",
                Integer.class), "失败的激活不得占用幂等键");

        // 同 requestId 同 transferKey 修正参数后可成功（失败不占键）
        TransferResponse ok = activate("req-t1", "tk-1", line("c1", "c2", 10), line("c2", "c1", 1));
        assertEquals("tk-1", ok.transferKey());
        assertEquals(91, budgetService.getAccount("c1").budget());
    }

    @Test
    @DisplayName("幂等：同参重放首次快照、明细换序等价、异参 409")
    void idempotency_replayReorderEquivalent_conflictOnDifferentParams() {
        account("req-a", "c1", 100);
        account("req-b", "c2", 50);
        account("req-c", "c3", 20);

        TransferResponse first = activate("req-t1", "tk-1",
                line("c1", "c2", 10), line("c2", "c3", 5));

        // 同 requestId、明细换序：等价，重放首次快照，不重复生效
        TransferResponse replay = activate("req-t1", "tk-1",
                line("c2", "c3", 5), line("c1", "c2", 10));
        assertEquals(first, replay);
        assertEquals(1, budgetService.getAccount("c1").version(), "重放不得重复增版");
        assertEquals(90, budgetService.getAccount("c1").budget());

        // 同 requestId 异参（金额不同）-> 409
        assertStatus(409, () -> activate("req-t1", "tk-1",
                line("c1", "c2", 11), line("c2", "c3", 5)));
        // 同 requestId 异参（transferKey 不同）-> 409
        assertStatus(409, () -> activate("req-t1", "tk-2",
                line("c1", "c2", 10), line("c2", "c3", 5)));
    }

    @Test
    @DisplayName("transferKey 全局唯一：不同 requestId 复用同一 transferKey 返回 409")
    void transferKeyIsUnique() {
        account("req-a", "c1", 100);
        account("req-b", "c2", 50);

        activate("req-t1", "tk-1", line("c1", "c2", 10), line("c2", "c1", 5));
        // 版本已变为 1，新请求使用新期望版本以隔离 transferKey 唯一性校验
        assertStatus(409, () -> budgetService.activate(new ActivateTransferRequest(
                "req-t2", "tk-1",
                List.of(new TransferLineRequest("c1", "c2", 1, 1, 1),
                        new TransferLineRequest("c2", "c1", 1, 1, 1)))));
    }

    @Test
    @DisplayName("版本边界：期望版本不符 409；成功转移后旧版本失效")
    void versionMismatchReturns409() {
        account("req-a", "c1", 100);
        account("req-b", "c2", 50);

        // c1 期望版本一致但错写成 1（当前为 0）-> 409
        assertStatus(409, () -> activate("req-t1", "tk-1",
                new TransferLineRequest("c1", "c2", 10, 1, 0),
                new TransferLineRequest("c2", "c1", 5, 0, 1)));

        activate("req-t2", "tk-2", line("c1", "c2", 10), line("c2", "c1", 5));
        // 版本已 +1，再用旧期望版本 0 -> 409
        assertStatus(409, () -> activate("req-t3", "tk-3",
                line("c1", "c2", 1), line("c2", "c1", 1)));
        // 同一活动在不同明细中期望版本自相矛盾 -> 422
        assertStatus(422, () -> activate("req-t4", "tk-4",
                new TransferLineRequest("c1", "c2", 1, 1, 1),
                new TransferLineRequest("c2", "c1", 1, 1, 0)));
    }

    @Test
    @DisplayName("窗口已开始（到达起始时刻）激活返回 409")
    void windowAlreadyStartedReturns409() {
        account("req-a", "c1", 100);
        account("req-b", "c2", 50);

        mutableClock().setInstant(Instant.ofEpochMilli(WS));
        assertStatus(409, () -> activate("req-t1", "tk-1",
                line("c1", "c2", 10), line("c2", "c1", 5)));
        assertEquals(100, budgetService.getAccount("c1").budget());
    }

    @Test
    @DisplayName("规则不一致 422：租户、窗口、受众规则任一不同均拒绝")
    void ruleMismatchReturns422() {
        account("req-a", "c1", 100);
        budgetService.createAccount(new CreateBudgetAccountRequest(
                "req-b", "c2", "tenant-2", WS, WE, "audience-a", 50));
        budgetService.createAccount(new CreateBudgetAccountRequest(
                "req-c", "c3", "tenant-1", WS, WE + 1, "audience-a", 50));
        budgetService.createAccount(new CreateBudgetAccountRequest(
                "req-d", "c4", "tenant-1", WS, WE, "audience-b", 50));

        assertStatus(422, () -> activate("req-t1", "tk-1",
                line("c1", "c2", 1), line("c2", "c1", 1)));
        assertStatus(422, () -> activate("req-t2", "tk-2",
                line("c1", "c3", 1), line("c3", "c1", 1)));
        assertStatus(422, () -> activate("req-t3", "tk-3",
                line("c1", "c4", 1), line("c4", "c1", 1)));
    }

    @Test
    @DisplayName("源目标相同 422；账本缺失 422；账本查询 404；证据查询 404")
    void invalidLinesAndMissingResources() {
        account("req-a", "c1", 100);
        account("req-b", "c2", 50);

        assertStatus(422, () -> activate("req-t1", "tk-1",
                line("c1", "c1", 10), line("c1", "c2", 5)));
        assertStatus(422, () -> activate("req-t2", "tk-2",
                line("c1", "cX", 10), line("c2", "c1", 5)));
        assertStatus(404, () -> budgetService.getAccount("cX"));
        assertStatus(404, () -> budgetService.getTransfer("tk-x"));
    }

    @Test
    @DisplayName("证据查询只读且稳定排序：重复查询结果一致，不改变任何状态")
    void evidenceQuery_isReadOnlyAndStablyOrdered() {
        account("req-a", "c1", 100);
        account("req-b", "c2", 50);
        account("req-c", "c3", 20);
        TransferResponse first = activate("req-t1", "tk-1",
                line("c2", "c3", 5), line("c1", "c2", 10), line("c3", "c1", 2));

        TransferResponse q1 = budgetService.getTransfer("tk-1");
        TransferResponse q2 = budgetService.getTransfer("tk-1");
        assertEquals(q1, q2);
        assertEquals(first, q1);
        // 明细按（源,目标）排序：c1->c2, c2->c3, c3->c1
        assertEquals("c1", q1.lines().get(0).sourceCampaignId());
        assertEquals("c2", q1.lines().get(1).sourceCampaignId());
        assertEquals("c3", q1.lines().get(2).sourceCampaignId());
        assertEquals(1, budgetService.getAccount("c1").version(), "只读查询不得改变状态");
    }

    @Test
    @DisplayName("HTTP 语义：建账 201、预览 200、激活 201、证据 200、明细条数越界 400")
    void httpSemantics() throws Exception {
        mockMvc.perform(post("/api/exposure/budget/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"campaignId\":\"c1\",\"tenantId\":\"t1\","
                                + "\"windowStartUtc\":" + WS + ",\"windowEndUtc\":" + WE + ","
                                + "\"audienceRule\":\"aud-a\",\"initialBudget\":100}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.budget").value(100))
                .andExpect(jsonPath("$.transferable").value(100));

        mockMvc.perform(post("/api/exposure/budget/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h2\",\"campaignId\":\"c2\",\"tenantId\":\"t1\","
                                + "\"windowStartUtc\":" + WS + ",\"windowEndUtc\":" + WE + ","
                                + "\"audienceRule\":\"aud-a\",\"initialBudget\":50}"))
                .andExpect(status().isCreated());

        String lines = "[{\"sourceCampaignId\":\"c1\",\"targetCampaignId\":\"c2\",\"amount\":10,"
                + "\"sourceExpectedVersion\":0,\"targetExpectedVersion\":0},"
                + "{\"sourceCampaignId\":\"c2\",\"targetCampaignId\":\"c1\",\"amount\":5,"
                + "\"sourceExpectedVersion\":0,\"targetExpectedVersion\":0}]";

        mockMvc.perform(post("/api/exposure/budget/transfers/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":" + lines + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts[0].budget").value(95))
                .andExpect(jsonPath("$.accounts[1].budget").value(55));

        mockMvc.perform(post("/api/exposure/budget/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h3\",\"transferKey\":\"tk-h1\",\"lines\":"
                                + lines + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVATED"))
                .andExpect(jsonPath("$.after[0].budget").value(95))
                .andExpect(jsonPath("$.after[0].version").value(1));

        mockMvc.perform(get("/api/exposure/budget/transfers/tk-h1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferKey").value("tk-h1"));

        mockMvc.perform(get("/api/exposure/budget/accounts/c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budget").value(95));

        // 明细少于 2 条 -> 400
        mockMvc.perform(post("/api/exposure/budget/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h4\",\"transferKey\":\"tk-h2\",\"lines\":["
                                + "{\"sourceCampaignId\":\"c1\",\"targetCampaignId\":\"c2\","
                                + "\"amount\":1,\"sourceExpectedVersion\":1,"
                                + "\"targetExpectedVersion\":1}]}"))
                .andExpect(status().isBadRequest());

        // 转移数量必须为正 -> 400
        mockMvc.perform(post("/api/exposure/budget/transfers/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":["
                                + "{\"sourceCampaignId\":\"c1\",\"targetCampaignId\":\"c2\","
                                + "\"amount\":0,\"sourceExpectedVersion\":1,"
                                + "\"targetExpectedVersion\":1},"
                                + "{\"sourceCampaignId\":\"c2\",\"targetCampaignId\":\"c1\","
                                + "\"amount\":1,\"sourceExpectedVersion\":1,"
                                + "\"targetExpectedVersion\":1}]}"))
                .andExpect(status().isBadRequest());

        // 窗口区间非法 -> 422
        mockMvc.perform(post("/api/exposure/budget/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h5\",\"campaignId\":\"c3\",\"tenantId\":\"t1\","
                                + "\"windowStartUtc\":" + WE + ",\"windowEndUtc\":" + WS + ","
                                + "\"audienceRule\":\"aud-a\",\"initialBudget\":1}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
