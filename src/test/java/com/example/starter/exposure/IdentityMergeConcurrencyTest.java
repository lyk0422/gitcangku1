package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.GroupQuotaResponse;
import com.example.starter.exposure.web.IdentityGroupResponse;
import com.example.starter.exposure.web.MergeIdentityRequest;
import com.example.starter.exposure.web.ReservationActionRequest;
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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 身份归并真实多线程并发测试：组内申请不超卖、并发归并只产生一组、
 * 归并与申请形成串行组账、竞争不泄露唯一键异常。
 */
@SpringBootTest
@ActiveProfiles("test")
class IdentityMergeConcurrencyTest {

    /** 固定时钟：所有并发操作落在同一 UTC 日，预占均保持 RESERVED。 */
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
        jdbc.update("DELETE FROM visitor_identity");
        jdbc.update("DELETE FROM identity_group");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    @DisplayName("组内两编号并发申请：成功数恰好等于原每访客上限，组账不超卖")
    void concurrentApplyInMergedGroup_doesNotOversell() throws Exception {
        int perVisitorCap = 3;
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100, perVisitorCap));
        service.mergeIdentities(new MergeIdentityRequest("req-m", "ga", "gb", "tester", 0));

        int threads = 30;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Set<String> reservationIds = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    String visitor = idx % 2 == 0 ? "ga" : "gb";
                    ReservationResponse r = service.apply(
                            new ApplyExposureRequest("req-a-" + idx, "cap", visitor));
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

        assertEquals(perVisitorCap, success.get(), "组内成功预占数必须等于原每访客上限");
        assertEquals(threads - perVisitorCap, rejected.get(), "其余请求必须为 429");
        assertEquals(perVisitorCap, reservationIds.size());

        GroupQuotaResponse gq = service.queryGroupQuota("ga", "cap", DAY);
        assertEquals(perVisitorCap, gq.usedGroup());
        assertEquals(0, gq.remainingGroup());
        int usedTotal = jdbc.queryForObject(
                "SELECT used_total FROM quota_total_ledger WHERE campaign_id = 'cap' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertEquals(perVisitorCap, usedTotal, "公告总额度按原计数，不超卖");
    }

    @Test
    @DisplayName("并发双向归并同一对编号：只产生一个组，竞争不泄露唯一键异常")
    void concurrentMergeSamePair_singleGroup() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        Set<String> groupIds = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 一半 merge(ga,gb)，一半 merge(gb,ga)，各自独立 requestId
                    IdentityGroupResponse g = idx % 2 == 0
                            ? service.mergeIdentities(
                                    new MergeIdentityRequest("req-m-" + idx, "ga", "gb", "tester", 0))
                            : service.mergeIdentities(
                                    new MergeIdentityRequest("req-m-" + idx, "gb", "ga", "tester", 0));
                    success.incrementAndGet();
                    groupIds.add(g.groupId());
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        conflicts.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                } catch (Exception e) {
                    unexpected.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, unexpected.get(), "竞争不得泄露唯一键等意外异常");
        assertEquals(1, success.get(), "同一对编号并发归并只应成功一次");
        assertEquals(threads - 1, conflicts.get(), "其余并发归并必须为 409");
        assertEquals(1, groupIds.size());

        // 最终只有一组、两个成员、版本 1
        Integer groupCount = jdbc.queryForObject("SELECT COUNT(*) FROM identity_group", Integer.class);
        Integer memberCount = jdbc.queryForObject("SELECT COUNT(*) FROM visitor_identity", Integer.class);
        assertEquals(1, groupCount);
        assertEquals(2, memberCount);
        IdentityGroupResponse state = service.mergeIdentities(
                new MergeIdentityRequest("req-verify", "ga", "gb", "tester", 1));
        assertEquals(2, state.members().size());
        assertEquals(1, state.version());
    }

    @Test
    @DisplayName("归并与对侧申请并发：串行组账，组账合计有界且不为负，满员后一律 429")
    void concurrentMergeAndApply_serializedGroupAccount() throws Exception {
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100, 2));
        ReservationResponse confirmed = service.apply(new ApplyExposureRequest("req-a0", "cap", "ga"));
        service.confirm(confirmed.reservationId(), new ReservationActionRequest("req-ok"));

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger mergeDone = new AtomicInteger();
        AtomicInteger applySuccess = new AtomicInteger();
        AtomicInteger applyRejected = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        pool.submit(() -> {
            try {
                start.await();
                service.mergeIdentities(new MergeIdentityRequest("req-m", "ga", "gb", "tester", 0));
                mergeDone.incrementAndGet();
            } catch (Exception e) {
                unexpected.incrementAndGet();
            }
        });
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.apply(new ApplyExposureRequest("req-b-" + idx, "cap", "gb"));
                    applySuccess.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 429) {
                        applyRejected.incrementAndGet();
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

        assertEquals(0, unexpected.get(), "不应出现 429 以外的申请异常或归并异常");
        assertEquals(1, mergeDone.get());

        // gb 归并前作为单编号最多成功 2 次（自身上限），归并后按组账执行：
        // 合计 = ga 已确认 1 + gb 成功数，上界为 3
        GroupQuotaResponse gq = service.queryGroupQuota("ga", "cap", DAY);
        assertTrue(gq.usedGroup() >= 2 && gq.usedGroup() <= 3,
                "组账合计必须在 [2,3] 内，实际 " + gq.usedGroup());
        assertTrue(applySuccess.get() <= 2, "gb 在归并前最多按自身上限成功 2 次");

        // 组账已满（>=2）：两编号后续申请一律 429
        assert429(() -> service.apply(new ApplyExposureRequest("req-z1", "cap", "ga")));
        assert429(() -> service.apply(new ApplyExposureRequest("req-z2", "cap", "gb")));

        int usedVisitor = jdbc.queryForObject(
                "SELECT COALESCE(SUM(used_visitor), 0) FROM quota_visitor_ledger WHERE campaign_id = 'cap'",
                Integer.class);
        assertTrue(usedVisitor >= 0, "账目不得为负");
    }

    private void assert429(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(429, ex.getStatus().value());
    }
}
