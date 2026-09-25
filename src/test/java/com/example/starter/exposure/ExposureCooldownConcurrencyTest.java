package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CooldownNotElapsedException;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.DecayRecordResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateCooldownRequest;
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
import java.util.ArrayList;
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
 * 冷却与衰减的真实多线程并发测试：同访客并发确认衰减序号唯一递增、
 * 并发冷却配置修改按提交顺序裁决、冷却期内并发申请全部 429 且不占额度。
 */
@SpringBootTest
@ActiveProfiles("test")
class ExposureCooldownConcurrencyTest {

    /** 固定时钟：全部操作落在同一 UTC 日，预占不触发到期。 */
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
        jdbc.update("DELETE FROM exposure_decay_record");
        jdbc.update("DELETE FROM visitor_last_confirmation");
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
    @DisplayName("同访客并发确认多个预占：衰减序号恰好为 1..N 各一次，最近确认时刻唯一")
    void concurrentConfirms_decaySequenceUnique() throws Exception {
        int n = 12;
        service.createCampaign(new CreateCampaignRequest("req-c", "cc", 1000, 1000, 0));
        List<String> reservationIds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ReservationResponse r = service.apply(
                    new ApplyExposureRequest("req-a-" + i, "cc", "v1"));
            reservationIds.add(r.reservationId());
        }

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            final String reservationId = reservationIds.get(i);
            pool.submit(() -> {
                try {
                    start.await();
                    ReservationResponse result = service.confirm(reservationId,
                            new ReservationActionRequest("req-k-" + reservationId));
                    if ("CONFIRMED".equals(result.status().name())) {
                        confirmed.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, errors.get(), "并发确认不应出现意外异常");
        assertEquals(n, confirmed.get(), "全部预占应确认成功");

        // 衰减序号恰好 1..N 各出现一次
        List<DecayRecordResponse> decay = service.queryDecay("cc", "v1", DAY);
        assertEquals(n, decay.size());
        Set<Integer> sequences = new HashSet<>();
        for (DecayRecordResponse record : decay) {
            sequences.add(record.sequenceNo());
        }
        assertEquals(n, sequences.size(), "序号不得重复");
        for (int seq = 1; seq <= n; seq++) {
            assertTrue(sequences.contains(seq), "缺少序号 " + seq);
        }

        // 最近确认时刻已写入且唯一一行
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM visitor_last_confirmation "
                        + "WHERE campaign_id = 'cc' AND visitor_id = 'v1'",
                Integer.class);
        assertEquals(1, rows);
        Long lastConfirmed = jdbc.queryForObject(
                "SELECT last_confirmed_at_utc FROM visitor_last_confirmation "
                        + "WHERE campaign_id = 'cc' AND visitor_id = 'v1'",
                Long.class);
        assertEquals(BASE.toEpochMilli(), lastConfirmed);
    }

    @Test
    @DisplayName("并发修改冷却配置（同 expectedVersion）：只有一个成功，其余 409，版本只 +1 一次")
    void concurrentCooldownUpdates_singleWinner() throws Exception {
        service.createCampaign(new CreateCampaignRequest("req-c", "cu", 10, 10, 5));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    CampaignResponse r = service.updateCooldown("cu",
                            new UpdateCooldownRequest("req-u-" + idx, 0L, 30));
                    if (r.version() == 1L) {
                        success.incrementAndGet();
                    }
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        conflicts.incrementAndGet();
                    } else {
                        errors.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, errors.get(), "不应出现 409 以外的异常");
        assertEquals(1, success.get(), "同版本并发修改只允许一个成功");
        assertEquals(threads - 1, conflicts.get(), "其余必须为版本冲突 409");

        CampaignResponse current = service.updateCooldown("cu",
                new UpdateCooldownRequest("req-final", 1L, 0));
        assertEquals(2L, current.version(), "首次并发修改只应推进一个版本");
    }

    @Test
    @DisplayName("冷却期内并发申请：全部 429 携带冷却结束时刻，额度零占用、无衰减记录")
    void concurrentApplyDuringCooldown_allRejected() throws Exception {
        service.createCampaign(new CreateCampaignRequest("req-c", "cl", 100, 100, 60));
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a0", "cl", "v1"));
        service.confirm(r.reservationId(), new ReservationActionRequest("req-k0"));
        long until = BASE.toEpochMilli() + 60 * 60_000L;

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger wrongUntil = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.apply(new ApplyExposureRequest("req-r-" + idx, "cl", "v1"));
                    unexpected.incrementAndGet();
                } catch (CooldownNotElapsedException ex) {
                    rejected.incrementAndGet();
                    if (ex.getCooldownUntilUtc() != until) {
                        wrongUntil.incrementAndGet();
                    }
                } catch (Exception e) {
                    unexpected.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(threads, rejected.get(), "冷却期内全部申请必须 429");
        assertEquals(0, wrongUntil.get(), "429 必须携带一致的冷却结束时刻");
        assertEquals(0, unexpected.get(), "不应有申请成功或其他异常");

        // 额度未被冷却拦截的申请占用；衰减记录仍只有首次确认的一条
        assertEquals(1, service.queryQuota("cl", "v1", DAY).usedTotal());
        assertEquals(1, service.queryQuota("cl", "v1", DAY).usedVisitor());
        assertEquals(1, service.queryDecay("cl", "v1", DAY).size());
    }
}
