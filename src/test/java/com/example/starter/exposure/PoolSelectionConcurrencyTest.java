package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreatePoolRequest;
import com.example.starter.exposure.web.PoolSelectionResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.SelectFromPoolRequest;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺额轮转选择池真实多线程并发测试：同池同时只占一轮、与普通申请联合裁决不超卖。
 */
@SpringBootTest
@ActiveProfiles("test")
class PoolSelectionConcurrencyTest {

    /** 固定时钟：所有并发操作落在同一 UTC 日，预占均保持 RESERVED（不触发到期）。 */
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
    static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return new FixedClock(BASE);
        }
    }

    @Autowired
    ExposureService service;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM pool_selection_deficit");
        jdbc.update("DELETE FROM pool_selection");
        jdbc.update("DELETE FROM pool_member");
        jdbc.update("DELETE FROM selection_pool");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private void campaign(String campaignId, int totalCap, int perVisitorCap) {
        service.createCampaign(
                new CreateCampaignRequest("req-c-" + campaignId, campaignId, totalCap, perVisitorCap));
    }

    private void createPool(String poolId, String c1, int w1, String c2, int w2) {
        service.createPool(new CreatePoolRequest("req-p-" + poolId, "tester", poolId,
                List.of(new CreatePoolRequest.Member(c1, w1), new CreatePoolRequest.Member(c2, w2))));
    }

    /** 并发执行 threads 次选择，返回 [成功数, 409 数]；成功响应存入 winner。 */
    private int[] runConcurrentSelects(String poolId, int threads, String keyPrefix,
                                       AtomicReference<PoolSelectionResponse> winner,
                                       ConcurrentLinkedQueue<Throwable> unexpected) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    PoolSelectionResponse r = service.selectFromPool(poolId,
                            new SelectFromPoolRequest(keyPrefix + idx, "tester", "v" + idx, null));
                    success.incrementAndGet();
                    winner.set(r);
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        conflicts.incrementAndGet();
                    } else {
                        unexpected.add(ex);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");
        return new int[]{success.get(), conflicts.get()};
    }

    @Test
    @DisplayName("并发选择同一池：每轮仅一个成功，其余 409；确认后下一轮选另一成员")
    void concurrentSelect_singleWinnerPerRound() throws Exception {
        campaign("A", 100, 100);
        campaign("B", 100, 100);
        createPool("P", "A", 1, "B", 1);

        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        AtomicReference<PoolSelectionResponse> winner1 = new AtomicReference<>();
        int[] round1 = runConcurrentSelects("P", 16, "req-s1-", winner1, unexpected);
        assertTrue(unexpected.isEmpty(), "不应出现非 409 异常: " + unexpected);
        assertEquals(1, round1[0], "同池同时只能占一轮");
        assertEquals(15, round1[1]);
        assertEquals("A", winner1.get().campaignId(), "同分取公告号小者");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pool_selection", Integer.class));

        // 确认提交缺额 (-1,1) 后，下一轮并发选择应选 B
        service.confirm(winner1.get().reservationId(), new ReservationActionRequest("req-k1"));
        AtomicReference<PoolSelectionResponse> winner2 = new AtomicReference<>();
        int[] round2 = runConcurrentSelects("P", 16, "req-s2-", winner2, unexpected);
        assertTrue(unexpected.isEmpty(), "不应出现非 409 异常: " + unexpected);
        assertEquals(1, round2[0]);
        assertEquals(15, round2[1]);
        assertEquals("B", winner2.get().campaignId());
        assertEquals(Map.of("A", 0L, "B", 0L), winner2.get().deficits());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM pool_selection", Integer.class));
    }

    @Test
    @DisplayName("池选择与普通申请并发：联合裁决不超卖，成功数恰好等于总额度")
    void concurrentSelectAndApply_noOversell() throws Exception {
        int totalCap = 10;
        campaign("A", totalCap, 100);
        campaign("B", 100, 100);
        createPool("P", "A", 1, "B", 1);

        int applyThreads = 30;
        int selectRounds = 6;
        ExecutorService pool = Executors.newFixedThreadPool(24);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger applySuccess = new AtomicInteger();
        AtomicInteger applyRejected = new AtomicInteger();
        AtomicInteger selectSuccess = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < applyThreads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.apply(new ApplyExposureRequest("req-a-" + idx, "A", "v" + idx));
                    applySuccess.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 429) {
                        applyRejected.incrementAndGet();
                    } else {
                        unexpected.add(ex);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        // 多轮选择：每轮确认后再发下一轮，与并发申请竞争同一总额度
        for (int round = 0; round < selectRounds; round++) {
            final int idx = round;
            pool.submit(() -> {
                try {
                    start.await();
                    // 每轮自旋直到本线程完成一次 选择->确认，或池/额度状态使其失败
                    for (int attempt = 0; attempt < 200; attempt++) {
                        try {
                            PoolSelectionResponse r = service.selectFromPool("P",
                                    new SelectFromPoolRequest(
                                            "req-s-" + idx + "-" + attempt, "tester", "w" + idx, null));
                            service.confirm(r.reservationId(),
                                    new ReservationActionRequest("req-k-" + idx + "-" + attempt));
                            selectSuccess.incrementAndGet();
                            return;
                        } catch (ApiException ex) {
                            if (ex.getStatus().value() == 409) {
                                continue; // 另一轮选择进行中，重试
                            }
                            if (ex.getStatus().value() == 429) {
                                return; // 全部不可选，本轮放弃
                            }
                            unexpected.add(ex);
                            return;
                        }
                    }
                    unexpected.add(new IllegalStateException("select round did not settle"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发任务应在超时前完成");
        assertTrue(unexpected.isEmpty(), "不应出现非 409/429 异常: " + unexpected);

        // 联合裁决：A 的占用（普通申请 + 池选择命中 A）不得超过总额度
        int usedA = service.queryQuota("A", null, DAY).usedTotal();
        int usedB = service.queryQuota("B", null, DAY).usedTotal();
        assertTrue(usedA <= totalCap, "A 不得超卖: " + usedA);
        assertEquals(applySuccess.get(), usedA - countPoolSelectionsOn("A"),
                "普通申请成功数应等于 A 占用减去池选择命中 A 数");
        assertEquals(selectSuccess.get(),
                countPoolSelectionsOn("A") + countPoolSelectionsOn("B"));
        assertEquals(usedB, countPoolSelectionsOn("B"), "B 的占用全部来自池选择");
        // 申请与选择共同把 A 打满或选择未命中 A：账目与预占单一致
        int reservedA = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation WHERE campaign_id = 'A' AND status = 'CONFIRMED'"
                        + " OR campaign_id = 'A' AND status = 'RESERVED'",
                Integer.class);
        assertEquals(usedA, reservedA, "A 的账目占用应等于未释放预占单数");
    }

    private int countPoolSelectionsOn(String campaignId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM pool_selection WHERE campaign_id = ?",
                Integer.class, campaignId);
    }
}
