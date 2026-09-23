package com.example.starter.exposure.budget;

import com.example.starter.exposure.budget.web.BudgetApplyRequest;
import com.example.starter.exposure.budget.web.BudgetReservationActionRequest;
import com.example.starter.exposure.budget.web.BudgetReservationResponse;
import com.example.starter.exposure.budget.web.BudgetTransferActivateRequest;
import com.example.starter.exposure.budget.web.BudgetTransferActivateResponse;
import com.example.starter.exposure.budget.web.BudgetTransferItem;
import com.example.starter.exposure.budget.web.BudgetTransferPreviewRequest;
import com.example.starter.exposure.budget.web.BudgetTransferPreviewResponse;
import com.example.starter.exposure.budget.web.CampaignLedgerResponse;
import com.example.starter.exposure.budget.web.CreateBudgetCampaignRequest;
import com.example.starter.exposure.budget.web.TransferEvidenceResponse;
import com.example.starter.exposure.web.ApiException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多活动曝光预算闭环转移 H2（MODE=MySQL）集成测试：
 * 闭环守恒、在途冻结、迟到回执归属、整体回滚、幂等、并发提交顺序与预算恒等式。
 */
@SpringBootTest
@ActiveProfiles("test")
class BudgetTransferIntegrationTest {

    /** 可控时钟：默认远早于投放窗口，可推进到窗口开始或预占到期之后。 */
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
    static final long WINDOW_START = Instant.parse("2026-09-25T00:00:00Z").toEpochMilli();
    static final long WINDOW_END = Instant.parse("2026-09-26T00:00:00Z").toEpochMilli();
    static final String RULE = "R1";

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock mutableClock() {
            return new MutableClock(BASE);
        }
    }

    @Autowired
    BudgetService service;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Clock clock;

    private MutableClock mutableClock() {
        return (MutableClock) clock;
    }

    @BeforeEach
    void cleanAndReset() {
        jdbc.update("DELETE FROM budget_transfer_snapshot");
        jdbc.update("DELETE FROM budget_transfer");
        jdbc.update("DELETE FROM budget_reservation");
        jdbc.update("DELETE FROM budget_campaign");
        jdbc.update("DELETE FROM idempotency_record");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    // ---- 构造辅助 ----

    private void createCampaign(String requestId, String campaignId, String tenant,
                                long budget, Long windowStart, Long windowEnd, String rule) {
        long ws = windowStart == null ? WINDOW_START : windowStart;
        long we = windowEnd == null ? WINDOW_END : windowEnd;
        String r = rule == null ? RULE : rule;
        service.createCampaign(new CreateBudgetCampaignRequest(
                requestId, campaignId, tenant, ws, we, r, budget));
    }

    private void createCampaign(String campaignId, long budget) {
        createCampaign("req-create-" + campaignId, campaignId, "t1", budget, null, null, null);
    }

    private BudgetTransferItem item(String source, String target, long amount,
                                    long sourceVersion, long targetVersion) {
        return new BudgetTransferItem(source, target, amount, sourceVersion, targetVersion);
    }

    /** 全部活动初始版本均为 1 时的快捷构造。 */
    private BudgetTransferItem item(String source, String target, long amount) {
        return item(source, target, amount, 1L, 1L);
    }

    private Map<String, CampaignLedgerResponse> toMap(BudgetTransferActivateResponse response) {
        Map<String, CampaignLedgerResponse> map = new HashMap<>();
        for (CampaignLedgerResponse ledger : response.ledgers()) {
            map.put(ledger.campaignId(), ledger);
        }
        return map;
    }

    private long dbBudget(String campaignId) {
        return jdbc.queryForObject(
                "SELECT budget FROM budget_campaign WHERE campaign_id = ?", Long.class, campaignId);
    }

    private long dbVersion(String campaignId) {
        return jdbc.queryForObject(
                "SELECT version FROM budget_campaign WHERE campaign_id = ?", Long.class, campaignId);
    }

    private long countStatus(String campaignId, String status) {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_reservation WHERE campaign_id = ? AND status = ?",
                Long.class, campaignId, status);
        return value == null ? 0L : value;
    }

    private long countTransfers() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM budget_transfer", Long.class);
        return value == null ? 0L : value;
    }

    /** 直接以数据库状态校验预算恒等式：总预算 = 可转 + 在途 + 已确认。 */
    private void assertIdentity(String campaignId) {
        long budget = dbBudget(campaignId);
        long inflight = countStatus(campaignId, "RESERVED");
        long confirmed = countStatus(campaignId, "CONFIRMED");
        long transferable = budget - inflight - confirmed;
        assertTrue(transferable >= 0,
                "活动 " + campaignId + " 可转余额不得为负：budget=" + budget
                        + " inflight=" + inflight + " confirmed=" + confirmed);
        assertEquals(budget, transferable + inflight + confirmed,
                "活动 " + campaignId + " 必须满足 总预算=可转+在途+已确认");
    }

    private ApiException expectStatus(int status, Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(status, ex.getStatus().value(),
                "期望 HTTP " + status + "，实际 " + ex.getStatus().value() + "：" + ex.getMessage());
        return ex;
    }

    // ---- 主流程 ----

    @Test
    @DisplayName("A→B→C→A 闭环：逐活动增版、全局总额守恒、恒等式成立并冻结明细与前后快照")
    void closedLoop_conservesBudget_andFreezesEvidence() {
        createCampaign("A", 100);
        createCampaign("B", 50);
        createCampaign("C", 30);

        List<BudgetTransferItem> details = List.of(
                item("A", "B", 40),
                item("B", "C", 20),
                item("C", "A", 10));

        BudgetTransferActivateResponse response = service.activate(
                new BudgetTransferActivateRequest("req-t1", "key-1", details));

        Map<String, CampaignLedgerResponse> ledgers = toMap(response);
        assertEquals(70, ledgers.get("A").budget());
        assertEquals(70, ledgers.get("B").budget());
        assertEquals(40, ledgers.get("C").budget());
        // 闭环净增量为 0 的参与者同样增版
        assertEquals(2, ledgers.get("A").version());
        assertEquals(2, ledgers.get("B").version());
        assertEquals(2, ledgers.get("C").version());
        // 无预占时可转余额等于总预算
        ledgers.values().forEach(l -> assertEquals(l.budget(), l.transferable()));

        assertEquals(180, dbBudget("A") + dbBudget("B") + dbBudget("C"), "全局总额守恒");
        assertIdentity("A");
        assertIdentity("B");
        assertIdentity("C");

        // 冻结明细按（源,目标）稳定排序
        List<BudgetTransferActivateResponse.FrozenDetail> frozen = response.details();
        assertEquals(List.of("A>B:40", "B>C:20", "C>A:10"),
                frozen.stream().map(d -> d.sourceCampaignId() + ">" + d.targetCampaignId()
                        + ":" + d.amount()).toList());

        // 证据只读查询
        TransferEvidenceResponse evidence = service.evidence("key-1");
        assertEquals("key-1", evidence.transferKey());
        assertEquals("req-t1", evidence.requestId());
        assertEquals("t1", evidence.tenantId());
        assertEquals("ACTIVATED", evidence.status());
        assertEquals(3, evidence.details().size(), "证据冻结 3 条规范化明细");
        assertEquals(List.of("A>B:40", "B>C:20", "C>A:10"),
                evidence.details().stream().map(d -> d.sourceCampaignId() + ">" + d.targetCampaignId()
                        + ":" + d.amount()).toList());
        assertEquals(List.of("A", "B", "C"),
                evidence.snapshots().stream().map(TransferEvidenceResponse.Snapshot::campaignId).toList());
        TransferEvidenceResponse.Snapshot snapA = evidence.snapshots().get(0);
        assertEquals(1, snapA.versionBefore());
        assertEquals(2, snapA.versionAfter());
        assertEquals(100, snapA.budgetBefore());
        assertEquals(70, snapA.budgetAfter());
        assertEquals(0, snapA.confirmedCount());
        assertEquals(0, snapA.inflightCount());
    }

    @Test
    @DisplayName("预览按完整后态返回且不写数据：版本+1、预算后态，库里无任何变化")
    void preview_returnsPostState_andWritesNothing() {
        createCampaign("A", 100);
        createCampaign("B", 50);

        BudgetTransferPreviewResponse preview = service.preview(
                new BudgetTransferPreviewRequest(List.of(item("A", "B", 30))));

        Map<String, CampaignLedgerResponse> ledgers = new HashMap<>();
        preview.ledgers().forEach(l -> ledgers.put(l.campaignId(), l));
        assertEquals(70, ledgers.get("A").budget());
        assertEquals(80, ledgers.get("B").budget());
        assertEquals(2, ledgers.get("A").version());
        assertEquals(2, ledgers.get("B").version());

        assertEquals(100, dbBudget("A"), "预览不得改预算");
        assertEquals(50, dbBudget("B"));
        assertEquals(1, dbVersion("A"), "预览不得增版");
        assertEquals(0, countTransfers(), "预览不得写转移单");
    }

    @Test
    @DisplayName("在途冻结：已预占未回执/过期与已确认均不可转走；仅可转余额能转出，激活后恒等式成立")
    void inflightAndConfirmed_areFrozen_notTransferable() {
        createCampaign("A", 10);
        createCampaign("B", 10);

        BudgetReservationResponse r1 = service.apply(new BudgetApplyRequest("req-a1", "A", "v1"));
        BudgetReservationResponse r2 = service.apply(new BudgetApplyRequest("req-a2", "A", "v2"));
        service.confirm(r1.reservationId(), new BudgetReservationActionRequest("req-c1"));
        // A: confirmed=1, inflight=1, transferable=8

        // 预览同样校验可转余额
        expectStatus(422, () -> service.preview(
                new BudgetTransferPreviewRequest(List.of(item("A", "B", 9)))));
        expectStatus(422, () -> service.activate(
                new BudgetTransferActivateRequest("req-bad", "bad-key",
                        List.of(item("A", "B", 9)))));

        BudgetTransferActivateResponse response = service.activate(
                new BudgetTransferActivateRequest("req-t1", "key-1",
                        List.of(item("A", "B", 8))));
        Map<String, CampaignLedgerResponse> ledgers = toMap(response);
        assertEquals(2, ledgers.get("A").budget());
        assertEquals(1, ledgers.get("A").inflight());
        assertEquals(1, ledgers.get("A").confirmed());
        assertEquals(0, ledgers.get("A").transferable());
        assertEquals(18, ledgers.get("B").budget());

        assertIdentity("A");
        assertIdentity("B");

        TransferEvidenceResponse evidence = service.evidence("key-1");
        TransferEvidenceResponse.Snapshot snapA = evidence.snapshots().stream()
                .filter(s -> s.campaignId().equals("A")).findFirst().orElseThrow();
        assertEquals(1, snapA.confirmedCount(), "快照冻结已确认数");
        assertEquals(1, snapA.inflightCount(), "快照冻结在途数");
        assertEquals(10, snapA.budgetBefore());
        assertEquals(2, snapA.budgetAfter());
    }

    @Test
    @DisplayName("迟到回执归属冻结：激活前的预占转移后仍归原活动并按原活动校验新预占")
    void preTransferReservations_stayAttached_lateReceiptGoesToOriginalCampaign() {
        createCampaign("A", 10);
        createCampaign("B", 10);

        BudgetReservationResponse r1 = service.apply(new BudgetApplyRequest("req-a1", "A", "v1"));
        BudgetReservationResponse r2 = service.apply(new BudgetApplyRequest("req-a2", "A", "v2"));
        // A inflight=2, transferable=8；转走 6 后 A 预算 4
        service.activate(new BudgetTransferActivateRequest("req-t1", "key-1",
                List.of(item("A", "B", 6))));

        // 迟到确认：归原活动 A，不改挂 B
        BudgetReservationResponse confirmed = service.confirm(r1.reservationId(),
                new BudgetReservationActionRequest("req-k1"));
        assertEquals("A", confirmed.campaignId());
        assertEquals("CONFIRMED", confirmed.status().name());
        assertEquals(1, countStatus("A", "CONFIRMED"));
        assertEquals(0, countStatus("B", "CONFIRMED"), "回执不得改挂目标活动");

        // A 剩余可承接：budget 4 - confirmed 1 - inflight 1 = 2
        service.apply(new BudgetApplyRequest("req-a3", "A", "v3"));
        service.apply(new BudgetApplyRequest("req-a4", "A", "v4"));
        expectStatus(429, () -> service.apply(new BudgetApplyRequest("req-a5", "A", "v5")));

        // r2 到期后在 A 上过期释放，仍归 A
        mutableClock().advanceMillis(60_000L);
        expectStatus(409, () -> service.confirm(r2.reservationId(),
                new BudgetReservationActionRequest("req-k2")));
        assertEquals("A", service.getReservation(r2.reservationId()).campaignId());
        assertEquals("EXPIRED", service.getReservation(r2.reservationId()).status().name());
        assertIdentity("A");
        assertIdentity("B");
    }

    @Test
    @DisplayName("过期释放在激活时结算：到期预占释放后可转余额恢复，过期单回执 409")
    void expiredReservationSettledAtActivation_freesTransferable() {
        createCampaign("A", 10);
        createCampaign("B", 0);

        service.apply(new BudgetApplyRequest("req-a1", "A", "v1"));
        // inflight=1 时全额转出 422
        expectStatus(422, () -> service.activate(
                new BudgetTransferActivateRequest("req-bad", "bad-key",
                        List.of(item("A", "B", 10)))));

        mutableClock().advanceMillis(60_000L);
        // 到期后激活：事务内结算，可转余额恢复为 10
        BudgetTransferActivateResponse response = service.activate(
                new BudgetTransferActivateRequest("req-t1", "key-1",
                        List.of(item("A", "B", 10))));
        assertEquals(0, toMap(response).get("A").budget());
        assertEquals(10, toMap(response).get("B").budget());
        assertIdentity("A");
        assertIdentity("B");
    }

    @Test
    @DisplayName("转移后新预占按新预算校验：B 收到预算后可预占，超额 429")
    void newReservationAfterTransfer_validatedAgainstNewBudget() {
        createCampaign("A", 10);
        createCampaign("B", 2);
        service.activate(new BudgetTransferActivateRequest("req-t1", "key-1",
                List.of(item("A", "B", 5))));
        assertEquals(7, dbBudget("B"));

        for (int i = 0; i < 7; i++) {
            service.apply(new BudgetApplyRequest("req-b" + i, "B", "visitor-" + i));
        }
        expectStatus(429, () -> service.apply(new BudgetApplyRequest("req-bx", "B", "visitor-x")));
        assertIdentity("B");
    }

    // ---- 整体回滚与失败分支 ----

    @Test
    @DisplayName("整批原子：任一端点跨租户导致 422 时全部活动预算与版本不变，失败不占 requestId")
    void wholeBatchRollsBack_onCrossTenantMismatch() {
        createCampaign("A", 100);
        createCampaign("B", 100);
        createCampaign("req-create-D", "D", "t2", 100, null, null, null);

        List<BudgetTransferItem> mixed = List.of(
                item("A", "B", 20),
                item("A", "D", 5));
        expectStatus(422, () -> service.activate(
                new BudgetTransferActivateRequest("req-mixed", "mixed-key", mixed)));

        assertEquals(100, dbBudget("A"));
        assertEquals(100, dbBudget("B"));
        assertEquals(1, dbVersion("A"));
        assertEquals(1, dbVersion("B"));
        assertEquals(0, countTransfers());

        // 同一 requestId 可用于后来合法的转移（失败不占键）
        BudgetTransferActivateResponse retry = service.activate(
                new BudgetTransferActivateRequest("req-mixed", "retry-key",
                        List.of(item("A", "B", 20))));
        assertEquals(80, toMap(retry).get("A").budget());
    }

    @Test
    @DisplayName("转出超过可转余额 422 且全部不变；转移后预算非负由数据库约束兜底")
    void insufficientBalance_422_andNoPartialUpdate() {
        createCampaign("A", 5);
        createCampaign("B", 5);
        expectStatus(422, () -> service.activate(
                new BudgetTransferActivateRequest("req-x", "key-x",
                        List.of(item("A", "B", 6)))));
        assertEquals(5, dbBudget("A"));
        assertEquals(5, dbBudget("B"));
        assertEquals(0, countTransfers());
    }

    @Test
    @DisplayName("校验分支：窗口已开始 409；版本变化 409；窗口/受众规则不一致 422；自转 422；期望版本自相矛盾 422")
    void validationBranches() {
        createCampaign("A", 100);
        createCampaign("B", 100);

        // 窗口已开始（左闭：now 达到起点即拒绝）
        mutableClock().setInstant(Instant.ofEpochMilli(WINDOW_START));
        expectStatus(409, () -> service.activate(
                new BudgetTransferActivateRequest("req-w", "key-w",
                        List.of(item("A", "B", 1)))));
        mutableClock().setInstant(BASE);

        // 期望版本不匹配
        expectStatus(409, () -> service.activate(
                new BudgetTransferActivateRequest("req-v", "key-v",
                        List.of(item("A", "B", 1, 99L, 1L)))));

        // 窗口不同
        createCampaign("req-create-W", "W", "t1", 100,
                WINDOW_START + 1_000L, WINDOW_END + 1_000L, RULE);
        expectStatus(422, () -> service.activate(
                new BudgetTransferActivateRequest("req-win", "key-win",
                        List.of(item("A", "W", 1)))));

        // 受众规则不同
        createCampaign("req-create-R", "R", "t1", 100, null, null, "R2");
        expectStatus(422, () -> service.activate(
                new BudgetTransferActivateRequest("req-rule", "key-rule",
                        List.of(item("A", "R", 1)))));

        // 自转非法
        expectStatus(422, () -> service.activate(
                new BudgetTransferActivateRequest("req-self", "key-self",
                        List.of(item("A", "A", 1)))));

        // 同一活动在不同明细中期望版本不一致
        expectStatus(422, () -> service.activate(
                new BudgetTransferActivateRequest("req-inc", "key-inc",
                        List.of(item("A", "B", 1, 1L, 1L),
                                item("B", "A", 1, 2L, 1L)))));

        assertEquals(0, countTransfers());
    }

    // ---- 幂等 ----

    @Test
    @DisplayName("requestId 同参重放首次快照；明细换序等价；异参 409；transferKey 唯一")
    void idempotency_replayOrderEquivalent_conflictOnDifferentParams_transferKeyUnique() {
        createCampaign("A", 100);
        createCampaign("B", 100);
        createCampaign("C", 100);

        List<BudgetTransferItem> ordered = List.of(
                item("A", "B", 10),
                item("B", "C", 4));
        BudgetTransferActivateResponse first = service.activate(
                new BudgetTransferActivateRequest("idem-1", "tk-1", ordered));
        assertEquals(90, toMap(first).get("A").budget());

        // 同参重放：返回首次快照，版本不重复递增
        BudgetTransferActivateResponse replay = service.activate(
                new BudgetTransferActivateRequest("idem-1", "tk-1", ordered));
        assertEquals(first.ledgers(), replay.ledgers());
        assertEquals(2, dbVersion("A"));

        // 明细换序（且同源目标拆分）等价：规范化后同指纹，仍返回首次快照
        List<BudgetTransferItem> shuffled = List.of(
                item("B", "C", 4),
                item("A", "B", 7),
                item("A", "B", 3));
        BudgetTransferActivateResponse reordered = service.activate(
                new BudgetTransferActivateRequest("idem-1", "tk-1", shuffled));
        assertEquals(first.ledgers(), reordered.ledgers());
        assertEquals(90, dbBudget("A"));
        assertEquals(1, countTransfers());

        // 同键异参（数量不同）409
        expectStatus(409, () -> service.activate(
                new BudgetTransferActivateRequest("idem-1", "tk-1",
                        List.of(item("A", "B", 11)))));

        // 新 requestId 但 transferKey 重复 → 409
        expectStatus(409, () -> service.activate(
                new BudgetTransferActivateRequest("idem-2", "tk-1",
                        List.of(item("A", "B", 10)))));
    }

    @Test
    @DisplayName("业务失败不占 requestId：窗口未开始后同键同参成功")
    void failedActivation_doesNotOccupyRequestId() {
        createCampaign("A", 100);
        createCampaign("B", 100);

        mutableClock().setInstant(Instant.ofEpochMilli(WINDOW_START));
        expectStatus(409, () -> service.activate(
                new BudgetTransferActivateRequest("req-late", "key-late",
                        List.of(item("A", "B", 10)))));

        mutableClock().setInstant(BASE);
        BudgetTransferActivateResponse ok = service.activate(
                new BudgetTransferActivateRequest("req-late", "key-late",
                        List.of(item("A", "B", 10))));
        assertNotNull(ok);
        assertEquals(90, dbBudget("A"));
        assertEquals(1, countTransfers());
    }

    // ---- 并发边界 ----

    @Test
    @DisplayName("两个交叠转移并发提交：按提交顺序只成一个，失版本方 409 且无部分更新，总额守恒")
    void concurrentOverlappingTransfers_commitOrder_singleWinner() throws Exception {
        createCampaign("A", 100);
        createCampaign("B", 100);
        createCampaign("C", 100);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        pool.submit(() -> {
            try {
                start.await();
                service.activate(new BudgetTransferActivateRequest("req-1", "tk-1",
                        List.of(item("A", "B", 20))));
                ok.incrementAndGet();
            } catch (ApiException ex) {
                if (ex.getStatus().value() == 409) {
                    conflicts.incrementAndGet();
                } else {
                    throw ex;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        pool.submit(() -> {
            try {
                start.await();
                service.activate(new BudgetTransferActivateRequest("req-2", "tk-2",
                        List.of(item("B", "C", 30))));
                ok.incrementAndGet();
            } catch (ApiException ex) {
                if (ex.getStatus().value() == 409) {
                    conflicts.incrementAndGet();
                } else {
                    throw ex;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(1, ok.get(), "恰好一个转移成功");
        assertEquals(1, conflicts.get(), "版本过期方必须 409");
        assertEquals(300, dbBudget("A") + dbBudget("B") + dbBudget("C"), "全局总额守恒");
        assertIdentity("A");
        assertIdentity("B");
        assertIdentity("C");
    }

    @Test
    @DisplayName("转移与预占并发：按提交顺序串行，最终每个活动满足预算恒等式且占用不超预算")
    void concurrentTransferAndApply_identityAlwaysHolds() throws Exception {
        createCampaign("A", 100);
        createCampaign("B", 50);

        int applyThreads = 60;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> outcomes = Collections.newSetFromMap(new ConcurrentHashMap<>());

        for (int i = 0; i < applyThreads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.apply(new BudgetApplyRequest("req-apply-" + idx, "B", "visitor-" + idx));
                    outcomes.add("apply-ok");
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 429) {
                        outcomes.add("apply-429");
                    } else if (ex.getStatus().value() == 409) {
                        outcomes.add("apply-409");
                    } else {
                        throw ex;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        pool.submit(() -> {
            try {
                start.await();
                service.activate(new BudgetTransferActivateRequest("req-transfer", "tk-transfer",
                        List.of(item("B", "A", 10))));
                outcomes.add("transfer-ok");
            } catch (ApiException ex) {
                outcomes.add("transfer-" + ex.getStatus().value());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发任务应在超时前完成");

        long occupiedB = countStatus("B", "RESERVED") + countStatus("B", "CONFIRMED");
        assertTrue(occupiedB <= dbBudget("B"), "B 占用不得超过最终总预算");
        assertIdentity("A");
        assertIdentity("B");
        assertEquals(150, dbBudget("A") + dbBudget("B"), "全局总额守恒");
    }
}
