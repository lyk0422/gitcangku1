package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.BatchApplyRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.GrantConsentRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同意版本与频控联合裁决的真实多线程并发测试（H2 MODE=MySQL 行锁）：
 * 冷却频控并发、同意授予并发版本互斥、批量与单申请竞争预算不超卖、同键并发重放只执行一次。
 */
@SpringBootTest
@ActiveProfiles("test")
class ConsentConcurrencyTest {

    /** 固定时钟：所有并发操作落在同一时刻与同意窗口内，预占均不过期。 */
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
    static final LocalDate DAY = LocalDate.of(2026, 9, 26);

    @org.springframework.boot.test.context.TestConfiguration
    static class TestClockConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
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
        jdbc.update("DELETE FROM consent_scope");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private void createCampaign(String campaignId, int total, int perVisitor, Long minIntervalMillis) {
        service.createCampaign(new CreateCampaignRequest("req-c-" + campaignId, campaignId,
                total, perVisitor, "promo", null, null, minIntervalMillis));
    }

    private void grantAllow(String reqId, String visitor, long version) {
        service.grantConsent(new GrantConsentRequest(reqId, visitor, "promo", "ALLOW", version,
                BASE.toEpochMilli() - 3_600_000L, BASE.toEpochMilli() + 86_400_000L));
    }

    @Test
    @DisplayName("同访客并发申请命中冷却频控：至多一笔成功，其余 FREQUENCY_LIMIT，账目恰好 +1")
    void concurrentApply_sameVisitor_frequencyLimitSerializes() throws Exception {
        createCampaign("cap", 100_000, 100_000, 60_000L);
        grantAllow("req-g1", "v1", 1L);

        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger frequencyLimited = new AtomicInteger();
        AtomicInteger otherErrors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.apply(new ApplyExposureRequest("req-a-" + idx, "cap", "v1"));
                    success.incrementAndGet();
                } catch (ApiException ex) {
                    if ("FREQUENCY_LIMIT".equals(ex.getReason())) {
                        frequencyLimited.incrementAndGet();
                    } else {
                        otherErrors.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(1, success.get(), "冷却窗口内同访客只允许一笔有效曝光");
        assertEquals(threads - 1, frequencyLimited.get(), "其余请求必须为 FREQUENCY_LIMIT");
        assertEquals(0, otherErrors.get(), "不应出现其他错误");
        QuotaResponse quota = service.queryQuota("cap", "v1", DAY);
        assertEquals(1, quota.usedTotal());
        assertEquals(1, quota.usedVisitor());
    }

    @Test
    @DisplayName("同裁决域并发提交相同版本同意：恰好一个胜出，其余 CONSENT_VERSION_CONFLICT，不产生重复区间")
    void concurrentGrantConsent_sameVersion_singleWinner() throws Exception {
        createCampaign("cap", 100, 100, null);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger versionConflicts = new AtomicInteger();
        AtomicInteger otherErrors = new AtomicInteger();
        Set<String> consentIds = Collections.synchronizedSet(new HashSet<>());

        // 所有线程并发提交同一 (访客, 类别) 的版本 1 同意
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    var resp = service.grantConsent(new GrantConsentRequest(
                            "req-g-" + idx, "v1", "promo", "ALLOW", 1L,
                            BASE.toEpochMilli() - 1_000L, BASE.toEpochMilli() + 10_000L));
                    success.incrementAndGet();
                    consentIds.add(resp.consentId());
                } catch (ApiException ex) {
                    if ("CONSENT_VERSION_CONFLICT".equals(ex.getReason())) {
                        versionConflicts.incrementAndGet();
                    } else {
                        otherErrors.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, success.get(), "同版本并发提交只允许一个胜出");
        assertEquals(threads - 1, versionConflicts.get(), "其余必须为版本冲突");
        assertEquals(0, otherErrors.get(), "不应出现其他错误");
        assertEquals(1, consentIds.size());
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM visitor_consent WHERE visitor_id = 'v1' "
                        + "AND category = 'promo' AND status = 'ACTIVE'",
                Long.class);
        assertEquals(1L, rows, "只允许存在一条版本 1 区间");

        // 胜出同意即时生效：申请可成功
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a1", "cap", "v1"));
        assertEquals(1L, r.consentVersion());
    }

    @Test
    @DisplayName("批量预占与单申请并发竞争总额度：成功占用总数恰好等于总额度，不超卖")
    void concurrentBatchAndSingle_neverOversells() throws Exception {
        int totalCap = 30;
        createCampaign("cap", totalCap, 100_000, null);
        // 50 个不同访客全部有同意
        for (int i = 1; i <= 50; i++) {
            grantAllow("req-g-" + i, "v" + i, 1L);
        }

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reservedBySingle = new AtomicInteger();
        AtomicInteger batchAccepted = new AtomicInteger();
        AtomicInteger budgetRejected = new AtomicInteger();
        AtomicInteger otherErrors = new AtomicInteger();

        // 3 个批量（每批 10 访客）+ 20 个单申请（互不重叠的另外 20 访客）
        for (int b = 0; b < 3; b++) {
            final int base = b * 10;
            pool.submit(() -> {
                try {
                    start.await();
                    List<String> visitors = java.util.stream.IntStream.rangeClosed(base + 1, base + 10)
                            .mapToObj(i -> "v" + i).toList();
                    service.batchApply(new BatchApplyRequest("req-batch-" + base, "cap", visitors));
                    batchAccepted.addAndGet(10);
                } catch (ApiException ex) {
                    if ("BUDGET_EXHAUSTED".equals(ex.getReason()) || "CONSENT_DENIED".equals(ex.getReason())) {
                        budgetRejected.addAndGet(10);
                    } else {
                        otherErrors.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        for (int i = 31; i <= 50; i++) {
            final String visitor = "v" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.apply(new ApplyExposureRequest("req-single-" + visitor, "cap", visitor));
                    reservedBySingle.incrementAndGet();
                } catch (ApiException ex) {
                    if ("BUDGET_EXHAUSTED".equals(ex.getReason())) {
                        budgetRejected.incrementAndGet();
                    } else {
                        otherErrors.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(0, otherErrors.get(), "不应出现预算/同意之外的错误");
        assertEquals(totalCap, batchAccepted.get() + reservedBySingle.get(),
                "成功占用总数必须恰好等于总额度");
        QuotaResponse quota = service.queryQuota("cap", null, DAY);
        assertEquals(totalCap, quota.usedTotal());
        assertEquals(0, quota.remainingTotal());
    }

    @Test
    @DisplayName("同一 requestKey 并发申请（含同意裁决）：只创建一笔预占并返回最初同意判定")
    void concurrentSameRequestId_consentApply_executesOnce() throws Exception {
        createCampaign("cap", 100, 100, 60_000L);
        grantAllow("req-g1", "v1", 7L);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> reservationIds = Collections.synchronizedSet(new HashSet<>());
        Set<Long> consentVersions = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ReservationResponse r = service.apply(
                            new ApplyExposureRequest("same-key", "cap", "v1"));
                    reservationIds.add(r.reservationId());
                    consentVersions.add(r.consentVersion());
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(0, errors.get(), "并发同键重放不应报错");
        assertEquals(1, reservationIds.size(), "业务只执行一次，预占单唯一");
        assertEquals(Set.of(7L), consentVersions, "所有重放返回最初固化的同意版本");
        assertEquals(1, service.queryQuota("cap", "v1", DAY).usedVisitor());
    }
}
