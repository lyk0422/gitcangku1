package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.ChannelUsageResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.MigrateChannelRequest;
import com.example.starter.exposure.web.UpsertChannelConfigRequest;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渠道频控真实多线程并发测试：同一渠道多公告共享日额度不超卖、
 * 配置版本并发冲突唯一胜出、迁移与申请并发按事务提交顺序裁决。
 */
@SpringBootTest
@ActiveProfiles("test")
class ChannelConcurrencyTest {

    /** 固定时钟：所有并发申请落在同一 UTC 日，预占均保持 RESERVED。 */
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
        jdbc.update("DELETE FROM channel_reservation");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM channel_daily_ledger");
        jdbc.update("DELETE FROM channel_cap_config");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    @DisplayName("同一渠道下多个公告并发申请：渠道日额度不超卖")
    void concurrentApply_sharedChannelCap_notOversold() throws Exception {
        int channelCap = 20;
        int campaigns = 4;
        int threadsPerCampaign = 25;
        service.upsertChannelConfig("ch", new UpsertChannelConfigRequest("req-ch", channelCap, null));
        for (int c = 0; c < campaigns; c++) {
            service.createCampaign(new CreateCampaignRequest(
                    "req-c" + c, "c" + c, 100_000, 100_000, "ch"));
        }

        int threads = campaigns * threadsPerCampaign;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.apply(new ApplyExposureRequest(
                            "req-a-" + idx, "c" + (idx % campaigns), "visitor-" + idx));
                    success.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 429) {
                        rejected.incrementAndGet();
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

        assertEquals(0, unexpected.get(), "不应出现 429 以外的异常");
        assertEquals(channelCap, success.get(), "成功预占数必须等于渠道日额度");
        assertEquals(threads - channelCap, rejected.get(), "其余请求必须为 429");

        ChannelUsageResponse usage = service.queryChannelUsage("ch", DAY);
        assertEquals(channelCap, usage.usedTotal(), "渠道账目不得超卖");
        assertEquals(0, usage.remaining());

        int channelRecords = jdbc.queryForObject(
                "SELECT COUNT(*) FROM channel_reservation WHERE channel_key = 'ch'", Integer.class);
        assertEquals(channelCap, channelRecords, "渠道预占记录数必须等于成功数");
    }

    @Test
    @DisplayName("并发修改渠道配置：同一 expectedVersion 只有一个胜出，其余 409")
    void concurrentConfigUpdate_singleWinner() throws Exception {
        service.upsertChannelConfig("ch", new UpsertChannelConfigRequest("req-ch", 10, null));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger updated = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.upsertChannelConfig("ch",
                            new UpsertChannelConfigRequest("req-u-" + idx, 10 + idx, 1L));
                    updated.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        conflicts.incrementAndGet();
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

        assertEquals(0, unexpected.get(), "不应出现 409 以外的异常");
        assertEquals(1, updated.get(), "同一 expectedVersion 并发修改只能有一个胜出");
        assertEquals(threads - 1, conflicts.get());
        assertEquals(2L, jdbc.queryForObject(
                "SELECT version FROM channel_cap_config WHERE channel_key = 'ch'", Long.class),
                "版本必须恰好递增一次");
    }

    @Test
    @DisplayName("迁移与申请并发：按事务提交顺序裁决，两侧渠道账目与预占记录一致")
    void concurrentMigrateAndApply_consistentLedgers() throws Exception {
        service.upsertChannelConfig("chA", new UpsertChannelConfigRequest("req-chA", 100, null));
        service.upsertChannelConfig("chB", new UpsertChannelConfigRequest("req-chB", 100, null));
        service.createCampaign(new CreateCampaignRequest("req-c", "c1", 100_000, 100_000, "chA"));

        int threads = 40;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    if (idx == 0) {
                        service.migrateCampaignChannel("c1", new MigrateChannelRequest("req-mg", "chB"));
                    } else {
                        service.apply(new ApplyExposureRequest(
                                "req-a-" + idx, "c1", "visitor-" + idx));
                        applied.incrementAndGet();
                    }
                } catch (ApiException ex) {
                    unexpected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, unexpected.get(), "申请与迁移均不应失败");
        assertEquals(threads - 1, applied.get());

        // 每个成功申请恰好落在一个渠道上：两渠道占用之和等于成功数
        int usedA = service.queryChannelUsage("chA", DAY).usedTotal();
        int usedB = service.queryChannelUsage("chB", DAY).usedTotal();
        assertEquals(applied.get(), usedA + usedB, "渠道占用合计必须等于成功申请数");

        int records = jdbc.queryForObject("SELECT COUNT(*) FROM channel_reservation", Integer.class);
        assertEquals(applied.get(), records, "渠道预占记录数必须等于成功申请数");
        int totalUsed = jdbc.queryForObject(
                "SELECT used_total FROM quota_total_ledger WHERE campaign_id = 'c1' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertEquals(applied.get(), totalUsed, "公告总账必须等于成功申请数");
    }
}
