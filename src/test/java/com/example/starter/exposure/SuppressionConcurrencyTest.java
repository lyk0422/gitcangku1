package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.exposure.SuppressionService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.ExposureDecisionResponse;
import com.example.starter.exposure.web.SuppressionAddSpec;
import com.example.starter.exposure.web.SuppressionBatchUpdateRequest;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 名单变更与曝光申请并发裁决 H2 测试：版本 CAS 串行化、按事务提交顺序裁决、
 * 同键并发只执行一次、抑制不扣账。
 */
@SpringBootTest
@ActiveProfiles("test")
class SuppressionConcurrencyTest {

    /** 固定时钟：并发过程中时刻不变，抑制区间覆盖该时刻。 */
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
    static final long T0 = BASE.toEpochMilli();

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return new FixedClock(BASE);
        }
    }

    @Autowired
    ExposureService exposureService;
    @Autowired
    SuppressionService suppressionService;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM suppression_delete_record");
        jdbc.update("DELETE FROM suppression_interval");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    @DisplayName("并发携带相同 expectedVersion 的名单变更：恰好一个成功，其余 409，版本只+1")
    void concurrentBatchUpdates_sameExpectedVersion_onlyOneSucceeds() throws Exception {
        exposureService.createCampaign(new CreateCampaignRequest("req-c", "cap", 100, 100));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 每个线程新增互不重叠的不同访客区间，语义都合法，仅版本竞争决定胜负
                    suppressionService.batchUpdate("cap", new SuppressionBatchUpdateRequest(
                            "req-b-" + idx, 0,
                            List.of(new SuppressionAddSpec("visitor-" + idx, T0, T0 + 1000)),
                            List.of()));
                    success.incrementAndGet();
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
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(1, success.get(), "相同 expectedVersion 只允许一个事务提交");
        assertEquals(threads - 1, conflicts.get(), "其余事务必须版本冲突 409");
        assertEquals(1, jdbc.queryForObject("SELECT version FROM campaign WHERE campaign_id = 'cap'",
                Integer.class).intValue(), "版本号必须恰好 +1");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM suppression_interval WHERE campaign_id = 'cap'", Integer.class));
    }

    @Test
    @DisplayName("并发曝光申请与名单变更按提交顺序裁决：被抑制的申请不扣账，总额账与提交结果一致")
    void concurrentApplyAndSuppression_commitOrderDecides() throws Exception {
        exposureService.createCampaign(new CreateCampaignRequest("req-c", "cap", 100, 100));

        int applyThreads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(17);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reserved = new AtomicInteger();
        AtomicInteger suppressed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        // 一个名单变更事务：新增覆盖当前时刻的抑制区间（visitor-x）
        pool.submit(() -> {
            try {
                start.await();
                suppressionService.createInterval("cap",
                        new com.example.starter.exposure.web.CreateSuppressionRequest(
                                "req-sup", "visitor-x", T0 - 1000, T0 + 100_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 多个曝光申请事务，全部针对 visitor-x
        for (int i = 0; i < applyThreads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ExposureDecisionResponse decision = exposureService.decide(
                            new ApplyExposureRequest("req-a-" + idx, "cap", "visitor-x",
                                    "banner", null));
                    if (decision.outcome().equals(ExposureDecisionResponse.OUTCOME_RESERVED)) {
                        reserved.incrementAndGet();
                    } else {
                        suppressed.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, errors.get(), "不应出现非预期错误");
        assertEquals(applyThreads, reserved.get() + suppressed.get());
        // 抑制名单提交前的申请创建预占并扣账；提交后的申请全部 SUPPRESSED 不扣账
        Integer usedTotalNullable = jdbc.query(
                "SELECT used_total FROM quota_total_ledger WHERE campaign_id = 'cap' AND utc_date = ?",
                rs -> rs.next() ? rs.getInt(1) : null, java.sql.Date.valueOf(DAY));
        Integer usedVisitorNullable = jdbc.query(
                "SELECT used_visitor FROM quota_visitor_ledger "
                        + "WHERE campaign_id = 'cap' AND visitor_id = 'visitor-x' AND utc_date = ?",
                rs -> rs.next() ? rs.getInt(1) : null, java.sql.Date.valueOf(DAY));
        int usedTotal = usedTotalNullable == null ? 0 : usedTotalNullable;
        int usedVisitor = usedVisitorNullable == null ? 0 : usedVisitorNullable;
        assertEquals(reserved.get(), usedTotal, "总账占用必须恰好等于 RESERVED 数量");
        assertEquals(reserved.get(), usedVisitor, "访客账占用必须恰好等于 RESERVED 数量");
        assertEquals(applyThreads - reserved.get(), suppressed.get());

        // 抑制名单已提交：此后（时钟固定）新申请必为 SUPPRESSED
        assertEquals(ExposureDecisionResponse.OUTCOME_SUPPRESSED,
                exposureService.decide(new ApplyExposureRequest(
                        "req-after", "cap", "visitor-x", "banner", null)).outcome());
        Integer afterTotal = jdbc.query(
                "SELECT used_total FROM quota_total_ledger WHERE campaign_id = 'cap' AND utc_date = ?",
                rs -> rs.next() ? rs.getInt(1) : null, java.sql.Date.valueOf(DAY));
        assertEquals(reserved.get(), afterTotal == null ? 0 : afterTotal,
                "裁决后的被抑制申请不得改变账目");
    }

    @Test
    @DisplayName("同一 requestId 并发名单批量更新：业务只执行一次，版本只+1")
    void concurrentSameKeyBatchUpdate_executesOnce() throws Exception {
        exposureService.createCampaign(new CreateCampaignRequest("req-c", "cap", 100, 100));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    suppressionService.batchUpdate("cap", new SuppressionBatchUpdateRequest(
                            "same-batch-key", 0,
                            List.of(new SuppressionAddSpec("visitor-x", T0, T0 + 1000)),
                            List.of()));
                    success.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(0, errors.get(), "并发同键重放不应报错");
        assertEquals(threads, success.get(), "所有调用重放同一成功结果");
        assertEquals(1, jdbc.queryForObject("SELECT version FROM campaign WHERE campaign_id = 'cap'",
                Integer.class).intValue());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM suppression_interval WHERE campaign_id = 'cap'", Integer.class));
    }
}
