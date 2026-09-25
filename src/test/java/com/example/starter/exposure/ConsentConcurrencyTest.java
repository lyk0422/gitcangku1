package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.BatchApplyRequest;
import com.example.starter.exposure.web.ConsentResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SubmitConsentRequest;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同意 + 频控联合裁决真实多线程并发测试（H2 MODE=MySQL 行锁）：
 * 并发同意提交的区间互斥、同意下并发预占不超卖、同键并发预占只裁决一次。
 */
@SpringBootTest
@ActiveProfiles("test")
class ConsentConcurrencyTest {

    /** 固定时钟：所有并发操作落在同一 UTC 日且远不到预占到期时刻。 */
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

    static final Instant BASE = Instant.parse("2026-09-26T10:00:00Z");
    static final long T0 = BASE.toEpochMilli();
    static final LocalDate DAY = LocalDate.of(2026, 9, 26);

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
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM visitor_consent");
        jdbc.update("DELETE FROM consent_mutex");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private CreateCampaignRequest campaign(String reqId, String id, int total, int perVisitor) {
        return new CreateCampaignRequest(reqId, id, total, perVisitor, "news", null, null);
    }

    @Test
    @DisplayName("并发提交同访客同类别同版本重叠区间：恰好一个成功，其余 CONSENT_CONFLICT")
    void concurrentSameVersionConsent_onlyOneSucceeds() throws Exception {
        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        Set<String> consentIds = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 全部相同版本 1、相同长期区间；重叠检查在互斥量保护下只允许一个通过
                    ConsentResponse c = service.submitConsent(new SubmitConsentRequest(
                            "ck-" + idx, "v1", "news", "ALLOW", 1, T0, null));
                    success.incrementAndGet();
                    consentIds.add(c.consentId());
                } catch (ApiException ex) {
                    if ("CONSENT_CONFLICT".equals(ex.getCode())) {
                        conflicts.incrementAndGet();
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

        assertEquals(1, success.get(), "同版本重叠区间只允许一个提交成功");
        assertEquals(threads - 1, conflicts.get(), "其余必须为 CONSENT_CONFLICT");
        assertEquals(1, consentIds.size());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM visitor_consent WHERE visitor_id = 'v1' AND category = 'news'",
                Integer.class).intValue(), "不得留下任何半成品或重复区间");
    }

    @Test
    @DisplayName("同意下并发预占超过访客频控：成功数恰等于上限，账目与同意快照一致")
    void concurrentApplyUnderConsent_respectsVisitorCap() throws Exception {
        int cap = 10;
        int threads = 40;
        service.createCampaign(campaign("req-c", "cap", 100_000, cap));
        service.submitConsent(new SubmitConsentRequest(
                "ck-allow", "v1", "news", "ALLOW", 1, T0, null));

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger denied = new AtomicInteger();
        AtomicInteger mismatches = new AtomicInteger();
        Set<String> reservationIds = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    ReservationResponse r = service.apply(
                            new ApplyExposureRequest("ak-" + rnd(), "cap", "v1"));
                    success.incrementAndGet();
                    reservationIds.add(r.reservationId());
                    if (!"ALLOW".equals(r.consentDecision()) || r.consentVersion() != 1) {
                        mismatches.incrementAndGet();
                    }
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 429) {
                        denied.incrementAndGet();
                    } else {
                        throw ex;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        startLatch.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(0, mismatches.get(), "所有成功预占的同意快照必须一致");
        assertEquals(cap, success.get(), "成功数必须恰好等于访客当日上限");
        assertEquals(threads - cap, denied.get(), "其余必须为额度不足");
        assertEquals(cap, reservationIds.size(), "预占单编号必须唯一");

        QuotaResponse quota = service.queryQuota("cap", "v1", DAY);
        assertEquals(cap, quota.usedVisitor());
        assertEquals(0, quota.remainingVisitor());
    }

    @Test
    @DisplayName("同一 requestId 并发预占：同意裁决只执行一次，返回同一预占与快照，账只扣一次")
    void concurrentSameKeyApply_underConsent_executesOnce() throws Exception {
        service.createCampaign(campaign("req-c", "cap", 100, 100));
        service.submitConsent(new SubmitConsentRequest(
                "ck-allow", "v1", "news", "ALLOW", 5, T0, null));

        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        Set<String> reservationIds = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    ReservationResponse r = service.apply(
                            new ApplyExposureRequest("same-apply-key", "cap", "v1"));
                    reservationIds.add(r.reservationId());
                    if (!"ALLOW".equals(r.consentDecision()) || r.consentVersion() != 5) {
                        errors.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        startLatch.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(0, errors.get(), "并发同键重放不应报错且快照必须一致");
        assertEquals(1, reservationIds.size(), "同意裁决与预占只执行一次");
        assertEquals(1, service.queryQuota("cap", "v1", DAY).usedVisitor());
    }

    @Test
    @DisplayName("并发批量预占竞争同一总预算：账目最终值不超过总额度，失败批次不留账")
    void concurrentBatchApply_competingForTotalCap_neverOversells() throws Exception {
        int totalCap = 15;
        service.createCampaign(campaign("req-c", "cap", totalCap, 100_000));
        for (int v = 0; v < 8; v++) {
            service.submitConsent(new SubmitConsentRequest(
                    "ck-" + v, "v" + v, "news", "ALLOW", 1, T0, null));
        }

        int batches = 8;
        ExecutorService pool = Executors.newFixedThreadPool(batches);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger succeededBatches = new AtomicInteger();
        AtomicInteger failedBatches = new AtomicInteger();
        AtomicInteger createdReservations = new AtomicInteger();

        for (int b = 0; b < batches; b++) {
            final int batchIdx = b;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    // 每批 3 条，访客在批内唯一（访客上限足够大），竞争总预算
                    var resp = service.batchApply(new BatchApplyRequest("bk-" + batchIdx,
                            java.util.List.of(
                                    new BatchApplyRequest.BatchApplyItem("cap", "v" + batchIdx),
                                    new BatchApplyRequest.BatchApplyItem(
                                            "cap", "v" + ((batchIdx + 1) % 8)),
                                    new BatchApplyRequest.BatchApplyItem(
                                            "cap", "v" + ((batchIdx + 2) % 8)))));
                    succeededBatches.incrementAndGet();
                    createdReservations.addAndGet(resp.reservations().size());
                } catch (ApiException ex) {
                    if ("QUOTA_EXHAUSTED".equals(ex.getCode())) {
                        failedBatches.incrementAndGet();
                    } else {
                        throw ex;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        startLatch.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(succeededBatches.get() + failedBatches.get(), batches);
        QuotaResponse quota = service.queryQuota("cap", null, DAY);
        assertEquals(createdReservations.get(), quota.usedTotal(),
                "账目占用必须恰好等于成功批创建的预占数");
        assertTrue(quota.usedTotal() <= totalCap, "总预算不得超卖");
        assertEquals(0, quota.remainingTotal() + quota.usedTotal() - totalCap,
                "占用 + 剩余必须恒等于总额度");
    }

    /** 生成短唯一后缀，避免循环内自增计数器在 lambda 中使用产生额外同步要求。 */
    private static String rnd() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
