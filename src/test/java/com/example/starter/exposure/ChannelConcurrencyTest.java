package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.ChannelResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreateChannelRequest;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateChannelCapRequest;
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
 * 渠道总量频控真实多线程并发测试：同渠道跨公告并发申请不超卖、
 * 渠道额度修改与并发确认按事务提交顺序裁决、渠道写操作同键并发只执行一次。
 */
@SpringBootTest
@ActiveProfiles("test")
class ChannelConcurrencyTest {

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
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM channel_daily_ledger");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM channel_config");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    @DisplayName("同渠道多个公告并发申请：渠道日额度跨公告不超卖，成功数恰等于渠道额度")
    void concurrentApplyAcrossCampaigns_channelNotOversold() throws Exception {
        int channelCap = 15;
        int campaigns = 3;
        int threadsPerCampaign = 20;
        service.createChannel(new CreateChannelRequest("req-ch", "ch-a", channelCap));
        for (int c = 0; c < campaigns; c++) {
            service.createCampaign(new CreateCampaignRequest(
                    "req-c" + c, "camp-" + c, 100_000, 100_000, "ch-a"));
        }

        int threads = campaigns * threadsPerCampaign;
        ExecutorService pool = Executors.newFixedThreadPool(24);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Set<String> reservationIds = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    String campaignId = "camp-" + (idx % campaigns);
                    ReservationResponse r = service.apply(
                            new ApplyExposureRequest("req-a-" + idx, campaignId, "visitor-" + idx));
                    success.incrementAndGet();
                    reservationIds.add(r.reservationId());
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
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(channelCap, success.get(), "渠道跨公告成功预占数必须恰等于渠道日额度");
        assertEquals(threads - channelCap, rejected.get(), "其余请求必须全部为 429");
        assertEquals(channelCap, reservationIds.size(), "预占单编号必须唯一");

        Integer channelUsed = jdbc.queryForObject(
                "SELECT used FROM channel_daily_ledger WHERE channel_key = 'ch-a' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertEquals(channelCap, channelUsed, "渠道账目不得超卖");

        Integer campaignTotal = jdbc.queryForObject(
                "SELECT COALESCE(SUM(used_total), 0) FROM quota_total_ledger WHERE utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertEquals(channelCap, campaignTotal, "三个公告总账之和必须等于渠道占用");
    }

    @Test
    @DisplayName("并发确认与并发修改渠道额度：全部确认成功，版本 0 修改至多一个成功，最终额度不低于已确认数")
    void concurrentCapUpdateAndConfirm_floorAndVersionHold() throws Exception {
        int cap = 20;
        service.createChannel(new CreateChannelRequest("req-ch", "ch-a", cap));
        service.createCampaign(new CreateCampaignRequest("req-c1", "c1", 100_000, 100_000, "ch-a"));

        // 先占满 20 个预占（均持有渠道名额）
        for (int i = 0; i < cap; i++) {
            service.apply(new ApplyExposureRequest("req-a-" + i, "c1", "visitor-" + i));
        }
        var reservations = jdbc.queryForList(
                "SELECT reservation_id FROM exposure_reservation ORDER BY reservation_id",
                String.class);

        int confirmerThreads = 20;
        // 修改目标额度均为 20（不低于任何时刻已确认数），只裁决版本冲突：至多一个成功
        int updaterThreads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger confirmOk = new AtomicInteger();
        AtomicInteger updateOk = new AtomicInteger();
        AtomicInteger updateVersionConflict = new AtomicInteger();

        for (int i = 0; i < confirmerThreads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.confirm(reservations.get(idx),
                            new ReservationActionRequest("req-k-" + idx));
                    confirmOk.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        for (int i = 0; i < updaterThreads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ChannelResponse resp = service.updateChannelCap("ch-a",
                            new UpdateChannelCapRequest("req-u-" + idx, cap, 0));
                    if (resp.version() == 1 && resp.dailyTotalCap() == cap) {
                        updateOk.incrementAndGet();
                    } else {
                        updateVersionConflict.incrementAndGet();
                    }
                } catch (ApiException ex) {
                    assertEquals(409, ex.getStatus().value(), "只应出现版本冲突 409");
                    updateVersionConflict.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(confirmerThreads, confirmOk.get(), "20 个已预占请求应全部确认成功");
        assertTrue(updateOk.get() <= 1, "expectedVersion=0 的并发修改至多一个成功");
        assertEquals(updaterThreads, updateOk.get() + updateVersionConflict.get());

        // 全部确认完成后，下调到 19（低于已确认数 20）必须 409；下调到等于已确认数 20 允许
        assert409(() -> service.updateChannelCap("ch-a",
                new UpdateChannelCapRequest("req-u-low", 19, 1)));
        ChannelResponse lowered = service.updateChannelCap("ch-a",
                new UpdateChannelCapRequest("req-u-eq", 20, 1));
        assertEquals(2, lowered.version());

        int confirmed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation WHERE status = 'CONFIRMED'",
                Integer.class);
        int finalCap = jdbc.queryForObject(
                "SELECT daily_total_cap FROM channel_config WHERE channel_key = 'ch-a'",
                Integer.class);
        assertEquals(20, confirmed);
        assertEquals(20, finalCap, "最终额度不得低于当前已确认数");
        assertEquals(20, service.queryChannelUsage("ch-a", DAY).used());
    }

    private void assert409(Runnable action) {
        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ApiException.class, action::run);
        assertEquals(409, ex.getStatus().value());
    }

    @Test
    @DisplayName("渠道修改同键并发重放：只执行一次，版本只增加一次")
    void concurrentSameUpdateKey_executesOnce() throws Exception {
        service.createChannel(new CreateChannelRequest("req-ch", "ch-a", 10));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ChannelResponse resp = service.updateChannelCap("ch-a",
                            new UpdateChannelCapRequest("same-up-key", 8, 0));
                    if (resp.version() != 1 || resp.dailyTotalCap() != 8) {
                        errors.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(0, errors.get(), "并发同键重放不应报错且结果一致");
        Integer version = jdbc.queryForObject(
                "SELECT version FROM channel_config WHERE channel_key = 'ch-a'", Integer.class);
        assertEquals(1, version, "业务只执行一次，版本只能到 1");
    }
}
