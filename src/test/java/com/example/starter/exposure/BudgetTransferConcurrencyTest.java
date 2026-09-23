package com.example.starter.exposure;

import com.example.starter.exposure.exposure.BudgetService;
import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ActivateTransferRequest;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.BudgetAccountResponse;
import com.example.starter.exposure.web.CreateBudgetAccountRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.TransferLineRequest;
import com.example.starter.exposure.web.TransferResponse;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预算转移真实多线程并发测试：同版本竞争只有一个生效、transferKey 唯一、
 * 转移与预占并发时预算恒等式对每个活动成立。
 */
@SpringBootTest
@ActiveProfiles("test")
class BudgetTransferConcurrencyTest {

    /** 固定时钟：窗口尚未开始，预占均保持 RESERVED（不触发到期）。 */
    static class FixedClock extends Clock {
        private final Instant fixed;

        FixedClock(Instant fixed) {
            this.fixed = fixed;
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
            return fixed;
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
        Clock fixedClock() {
            return new FixedClock(BASE);
        }
    }

    @Autowired
    BudgetService budgetService;
    @Autowired
    ExposureService exposureService;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM budget_transfer_line");
        jdbc.update("DELETE FROM budget_transfer");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign_budget_account");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private void account(String reqId, String campaignId, int budget) {
        budgetService.createAccount(new CreateBudgetAccountRequest(
                reqId, campaignId, "tenant-1", WS, WE, "audience-a", budget));
    }

    private TransferLineRequest line(String source, String target, int amount) {
        return new TransferLineRequest(source, target, amount, 0, 0);
    }

    private void assertIdentity(String campaignId) {
        BudgetAccountResponse a = budgetService.getAccount(campaignId);
        assertEquals(a.budget(), a.transferable() + a.inFlight() + a.confirmed(),
                "预算恒等式 总预算=可转+在途+已确认 必须成立: " + campaignId);
        assertTrue(a.budget() >= 0 && a.transferable() >= 0, "预算与可转余额不得为负");
    }

    @Test
    @DisplayName("并发同版本转移同一组活动：只有一次生效，其余 409，总额守恒")
    void concurrentTransfersOnSameAccounts_exactlyOneSucceeds() throws Exception {
        account("req-a", "c1", 100);
        account("req-b", "c2", 100);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    budgetService.activate(new ActivateTransferRequest(
                            "req-t-" + idx, "tk-" + idx,
                            List.of(line("c1", "c2", 10), line("c2", "c1", 4))));
                    success.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        conflict.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, unexpected.get(), "不应出现 409 以外的失败");
        assertEquals(1, success.get(), "同一期望版本下只有一次转移能生效");
        assertEquals(threads - 1, conflict.get(), "其余转移必须因版本变化返回 409");

        // 只生效一次：c1 = 100-10+4 = 94，c2 = 100+10-4 = 106，版本各为 1
        assertEquals(94, budgetService.getAccount("c1").budget());
        assertEquals(106, budgetService.getAccount("c2").budget());
        assertEquals(1, budgetService.getAccount("c1").version());
        assertEquals(1, budgetService.getAccount("c2").version());
        assertEquals(200, budgetService.getAccount("c1").budget()
                + budgetService.getAccount("c2").budget(), "全局总额守恒");
        assertIdentity("c1");
        assertIdentity("c2");
    }

    @Test
    @DisplayName("并发复用同一 transferKey：只有一单冻结，其余 409")
    void concurrentSameTransferKey_singleRecord() throws Exception {
        account("req-a", "c1", 100);
        account("req-b", "c2", 100);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    budgetService.activate(new ActivateTransferRequest(
                            "req-k-" + idx, "tk-same",
                            List.of(line("c1", "c2", 10), line("c2", "c1", 4))));
                    success.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        conflict.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, unexpected.get(), "不应出现 409 以外的失败");
        assertEquals(1, success.get(), "同一 transferKey 只能激活一次");
        assertEquals(threads - 1, conflict.get());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_transfer WHERE transfer_key = 'tk-same'",
                Integer.class));
        TransferResponse evidence = budgetService.getTransfer("tk-same");
        assertEquals(94, evidence.after().stream()
                .filter(s -> s.campaignId().equals("c1")).findFirst().orElseThrow().budget());
    }

    @Test
    @DisplayName("转移与预占并发：按提交顺序结算，预算恒等式与总额守恒始终成立")
    void concurrentApplyAndTransfer_budgetIdentityHolds() throws Exception {
        exposureService.createCampaign(new CreateCampaignRequest("req-cp1", "c1", 1_000, 1_000));
        account("req-a", "c1", 50);
        account("req-b", "c2", 0);
        account("req-c", "c3", 0);

        int applies = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger transferred = new AtomicInteger();
        AtomicInteger transferFailed = new AtomicInteger();

        for (int i = 0; i < applies; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ReservationResponse r = exposureService.apply(
                            new ApplyExposureRequest("req-r-" + idx, "c1", "v" + idx));
                    if ("RESERVED".equals(r.status().name())) {
                        applied.incrementAndGet();
                    }
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 429) {
                        rejected.incrementAndGet();
                    } else {
                        throw ex;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        // 两个并发转移：c1 -> c2 转出 20，c2 -> c3 转出 1
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    budgetService.activate(new ActivateTransferRequest(
                            "req-t-" + idx, "tk-" + idx,
                            List.of(line("c1", "c2", 20), line("c2", "c3", 1))));
                    transferred.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409 || ex.getStatus().value() == 422) {
                        transferFailed.incrementAndGet();
                    } else {
                        throw ex;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(1, transferred.get(), "同版本并发转移只生效一次");
        assertEquals(1, transferFailed.get());
        assertEquals(applies, applied.get() + rejected.get(), "申请要么成功要么 429");

        BudgetAccountResponse c1 = budgetService.getAccount("c1");
        BudgetAccountResponse c2 = budgetService.getAccount("c2");
        BudgetAccountResponse c3 = budgetService.getAccount("c3");
        assertEquals(applied.get(), c1.inFlight(), "成功预占必须全部计入在途");
        assertEquals(30, c1.budget(), "c1 预算恰好被转走 20");
        assertEquals(19, c2.budget());
        assertEquals(1, c3.budget());
        assertEquals(50, c1.budget() + c2.budget() + c3.budget(), "全局总额守恒");
        assertIdentity("c1");
        assertIdentity("c2");
        assertIdentity("c3");
    }
}
