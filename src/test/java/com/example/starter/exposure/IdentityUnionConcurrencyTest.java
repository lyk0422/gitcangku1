package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.GroupQuotaResponse;
import com.example.starter.exposure.web.IdentityGroupResponse;
import com.example.starter.exposure.web.MergeIdentityRequest;
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
 * 身份归并并发测试：归并与申请/归并之间形成串行组账，不超卖、不重复建组。
 */
@SpringBootTest
@ActiveProfiles("test")
class IdentityUnionConcurrencyTest {

    /** 固定时钟：所有操作落在同一 UTC 日，预占均保持 RESERVED。 */
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

    static final Instant BASE = Instant.parse("2026-10-06T10:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 10, 6);

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
        jdbc.update("DELETE FROM identity_group_member");
        jdbc.update("DELETE FROM identity_group");
        jdbc.update("DELETE FROM campaign");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    @DisplayName("归并与两边申请并发：组账串行，合计不超过每访客上限")
    void concurrentMergeAndApply_serializedNoOversell() throws Exception {
        service.createCampaign(new CreateCampaignRequest("req-c", "cap", 100, 2));

        int appliesPerSide = 4;
        int threads = 1 + appliesPerSide * 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger applySuccess = new AtomicInteger();
        AtomicInteger applyRejected = new AtomicInteger();
        AtomicInteger mergeDone = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        pool.submit(() -> {
            try {
                start.await();
                service.mergeIdentities(new MergeIdentityRequest("req-m", "ops", "A", "B", 0));
                mergeDone.incrementAndGet();
            } catch (Exception e) {
                unexpected.incrementAndGet();
            }
        });
        for (int i = 0; i < appliesPerSide * 2; i++) {
            final String visitor = i % 2 == 0 ? "A" : "B";
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.apply(new ApplyExposureRequest("req-a-" + idx, "cap", visitor));
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

        assertEquals(0, unexpected.get(), "不应出现非预期异常");
        assertEquals(1, mergeDone.get(), "归并必须成功一次");
        assertEquals(appliesPerSide * 2, applySuccess.get() + applyRejected.get());

        // 组账串行：成功申请数等于组合计；每个原编号各自不超过上限 2
        // （归并前各自合法申请可令组合计达到 4，归并仍生效，此后组禁止新增）
        GroupQuotaResponse gq = service.queryGroupQuota("cap", "A", DAY);
        assertEquals(2, gq.members().size(), "归并后组内必须恰好两个编号");
        assertEquals(applySuccess.get(), gq.usedGroup(), "组合计必须等于成功申请数");
        for (GroupQuotaResponse.MemberUsage member : gq.members()) {
            assertTrue(member.usedVisitor() <= 2,
                    "单编号账目不得超过上限: " + member.visitorId() + "=" + member.usedVisitor());
        }
        assertTrue(gq.usedGroup() >= 2, "串行组账下至少应有 2 次成功，实际 " + gq.usedGroup());

        // 归并生效且合计已达上限：后续申请必须 429
        ApiException post = assertThrows(ApiException.class,
                () -> service.apply(new ApplyExposureRequest("req-post", "cap", "A")));
        assertEquals(429, post.getStatus().value());

        Integer reservations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation WHERE campaign_id = 'cap'", Integer.class);
        assertEquals(applySuccess.get(), reservations, "预占单数必须等于成功申请数");
    }

    @Test
    @DisplayName("同对编号并发归并（不同幂等键）：恰好一个生效，只建一个组")
    void concurrentMerges_samePair_singleGroup() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        Set<String> groupIds = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    IdentityGroupResponse r = service.mergeIdentities(
                            new MergeIdentityRequest("req-m-" + idx, "ops", "A", "B", 0));
                    succeeded.incrementAndGet();
                    groupIds.add(r.groupId());
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
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(threads, succeeded.get() + conflicts.get());
        assertEquals(1, groupIds.size(), "所有成功归并必须指向同一个组");
        Integer groupCount = jdbc.queryForObject("SELECT COUNT(*) FROM identity_group", Integer.class);
        assertEquals(1, groupCount, "全库只能存在一个组");
        Integer memberCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM identity_group_member", Integer.class);
        assertEquals(2, memberCount, "重复归并不新增成员");
    }

    @Test
    @DisplayName("同一 requestId 并发归并重放：只执行一次，响应一致")
    void concurrentSameKeyMerge_replaysOnce() throws Exception {
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> groupIds = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    IdentityGroupResponse r = service.mergeIdentities(
                            new MergeIdentityRequest("same-merge-key", "ops", "A", "B", 0));
                    groupIds.add(r.groupId());
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(0, errors.get(), "并发同键重放不应报错");
        assertEquals(1, groupIds.size(), "归并只执行一次");
        IdentityGroupResponse group = service.getIdentityGroup("A");
        assertEquals(1, group.version(), "版本只推进一次");
        assertEquals(2, group.memberVisitorIds().size());
    }
}
