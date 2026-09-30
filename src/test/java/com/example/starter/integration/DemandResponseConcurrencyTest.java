package com.example.starter.integration;

import com.example.starter.service.DemandResponseService;
import com.example.starter.service.RewardCalculator;
import com.example.starter.testsupport.TestClockConfiguration;
import com.example.starter.web.ApiException;
import com.example.starter.web.dto.CommitmentRequest;
import com.example.starter.web.dto.InvitationRequest;
import com.example.starter.web.dto.ReadingRequest;
import com.example.starter.web.dto.SettlementRequest;
import com.example.starter.web.dto.SettlementView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 H2 并发测试：并发结算的 requestId 幂等、读数更正与首次结算竞争。
 * 用 CyclicBarrier 对齐真实并发操作，Future 设超时，最后核对数据库最终状态而非打印。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfiguration.class)
@Sql(scripts = "/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class DemandResponseConcurrencyTest {

    @Autowired
    private DemandResponseService service;
    @Autowired
    private JdbcTemplate jdbc;

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Instant DAY_ZERO = Instant.parse("2026-01-01T18:00:00Z");

    @Test
    void concurrentSettleWithSameRequestIdProducesExactlyOneFirstLedger() throws Exception {
        ChainContext ctx = prepareChain("u-conc-same", "30", "23");
        long invitationId = ctx.invitationId;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            CallableOutcome o1 = new CallableOutcome();
            CallableOutcome o2 = new CallableOutcome();
            Future<?> f1 = pool.submit(() -> runSettle(invitationId, "u-conc-same", "req-same", barrier, o1));
            Future<?> f2 = pool.submit(() -> runSettle(invitationId, "u-conc-same", "req-same", barrier, o2));
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);

            // 两次并发调用都返回同一笔首次结算：一次新建、一次重放，金额均为 560。
            assertNull(o1.otherError);
            assertNull(o2.otherError);
            assertTrue(o1.success && o2.success, "同参并发结算都应成功返回");
            assertEquals(560L, o1.view.amountFen());
            assertEquals(560L, o2.view.amountFen());
            assertEquals(o1.view.settlementId(), o2.view.settlementId());
            assertTrue(o1.view.replayed() ^ o2.view.replayed(), "恰有一次为重放");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1L, ledgerCount(invitationId, "u-conc-same"), "并发重试只产生一笔首账");
        assertEquals(1L, settlementCount(invitationId, "u-conc-same"));
    }

    @Test
    void concurrentSettleWithDifferentRequestIdsYieldsOneSuccessOne409() throws Exception {
        ChainContext ctx = prepareChain("u-conc-diff", "30", "23");
        long invitationId = ctx.invitationId;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            CallableOutcome o1 = new CallableOutcome();
            CallableOutcome o2 = new CallableOutcome();
            Future<?> f1 = pool.submit(() -> runSettle(invitationId, "u-conc-diff", "req-a", barrier, o1));
            Future<?> f2 = pool.submit(() -> runSettle(invitationId, "u-conc-diff", "req-b", barrier, o2));
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);

            long successes = (o1.success ? 1 : 0) + (o2.success ? 1 : 0);
            long conflicts = (o1.conflict409 ? 1 : 0) + (o2.conflict409 ? 1 : 0);
            assertNull(o1.otherError);
            assertNull(o2.otherError);
            assertEquals(1L, successes, "只能有一笔结算成功");
            assertEquals(1L, conflicts, "另一笔必须是 409");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1L, ledgerCount(invitationId, "u-conc-diff"));
        assertEquals(1L, settlementCount(invitationId, "u-conc-diff"));
    }

    @Test
    void correctionRacingSettlementNeverMixesReadingVersions() throws Exception {
        // 多轮提高两种先后顺序都被覆盖的概率；每轮独立用户。
        for (int round = 0; round < 6; round++) {
            String userId = "u-race-" + round + "-" + SEQ.incrementAndGet();
            ChainContext ctx = prepareChain(userId, "30", "23");
            long invitationId = ctx.invitationId;
            final String uid = userId;
            final String reason = "竞争更正" + round;
            final String settleRequestId = "settle-" + round;

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CyclicBarrier barrier = new CyclicBarrier(2);
            try {
                Future<?> settleFuture = pool.submit(() -> {
                    barrier.await();
                    service.settle(invitationId, new SettlementRequest(uid, settleRequestId));
                    return null;
                });
                Future<?> correctFuture = pool.submit(() -> {
                    barrier.await();
                    service.submitReading(invitationId, readingWith(uid, new BigDecimal("30"),
                            new BigDecimal("25"), ctx.start, ctx.end, reason));
                    return null;
                });
                settleFuture.get(30, TimeUnit.SECONDS);
                correctFuture.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            assertConsistentLedger(invitationId, userId, "30", "23", "25", "10");
        }
    }

    @Test
    void correctionRacingSettlementBothSeeCommittedStateAcrossRepeatedRounds() throws Exception {
        // 串行版本对照：先结算后更正，作为并发不变量的基准（净 560-160=400）。
        ChainContext ctx = prepareChain("u-serial-ref", "30", "23");
        SettlementView view = service.settle(ctx.invitationId, new SettlementRequest("u-serial-ref", "req-ref"));
        assertEquals(560L, view.amountFen());
        service.submitReading(ctx.invitationId, readingWith("u-serial-ref", new BigDecimal("30"),
                new BigDecimal("25"), ctx.start, ctx.end, "串行更正"));
        assertConsistentLedger(ctx.invitationId, "u-serial-ref", "30", "23", "25", "10");
    }

    // ---------- helpers ----------

    private ChainContext prepareChain(String userId, String baseline, String actual) {
        int n = SEQ.incrementAndGet();
        Instant start = DAY_ZERO.plus(n, ChronoUnit.DAYS);
        Instant end = start.plus(1, ChronoUnit.HOURS);
        long invitationId = service.createInvitation(new InvitationRequest(start, end, 80)).id();
        service.createCommitment(invitationId,
                new CommitmentRequest(userId, new BigDecimal("10"), "commit-" + userId + "-" + n));
        service.submitReading(invitationId, readingWith(userId, new BigDecimal(baseline),
                new BigDecimal(actual), start, end, null));
        return new ChainContext(invitationId, start, end);
    }

    private ReadingRequest readingWith(String userId, BigDecimal baseline, BigDecimal actual,
                                       Instant start, Instant end, String reason) {
        return new ReadingRequest(userId, baseline, actual, start, end, start, end, reason);
    }

    private void runSettle(long invitationId, String userId, String requestId,
                           CyclicBarrier barrier, CallableOutcome outcome) {
        try {
            barrier.await();
            outcome.view = service.settle(invitationId, new SettlementRequest(userId, requestId));
            outcome.success = true;
        } catch (ApiException ex) {
            outcome.conflict409 = ex.getStatus() == 409;
            outcome.errorStatus = ex.getStatus();
        } catch (Exception ex) {
            outcome.otherError = ex;
        }
    }

    private long ledgerCount(long invitationId, String userId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entry WHERE invitation_id = ? AND user_id = ?",
                Long.class, invitationId, userId);
        return count == null ? 0 : count;
    }

    private long settlementCount(long invitationId, String userId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement WHERE invitation_id = ? AND user_id = ?",
                Long.class, invitationId, userId);
        return count == null ? 0 : count;
    }

    /**
     * 不变量：结算固化的版本基线/实际成对、账目版本不重复、净金额与读数重算一致；
     * 无论结算先于还是晚于更正提交都成立。
     */
    private void assertConsistentLedger(long invitationId, String userId, String baselineV1,
                                        String actualV1, String actualV2, String committed) {
        Map<String, Object> settlement = jdbc.queryForMap(
                "SELECT reading_version, reward_kwh, amount_fen FROM settlement "
                        + "WHERE invitation_id = ? AND user_id = ?", invitationId, userId);
        int settledVersion = ((Number) settlement.get("reading_version")).intValue();

        // 结算版本的基线与实际必须同时存在且为同一已提交版本（不允许旧基线拼新实际）。
        Integer baselineRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM meter_reading WHERE invitation_id = ? AND user_id = ? "
                        + "AND reading_type = 'BASELINE' AND version = ?",
                Integer.class, invitationId, userId, settledVersion);
        Integer actualRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM meter_reading WHERE invitation_id = ? AND user_id = ? "
                        + "AND reading_type = 'ACTUAL' AND version = ?",
                Integer.class, invitationId, userId, settledVersion);
        assertEquals(1, baselineRows, "结算基线版本必须存在且唯一");
        assertEquals(1, actualRows, "结算实际版本必须存在且唯一");

        List<Map<String, Object>> entries = jdbc.queryForList(
                "SELECT entry_type, old_version, new_version, amount_fen FROM ledger_entry "
                        + "WHERE invitation_id = ? AND user_id = ? ORDER BY id", invitationId, userId);
        long distinctVersions = entries.stream().map(e -> ((Number) e.get("new_version")).intValue()).distinct().count();
        assertEquals(entries.size(), distinctVersions, "相同读数版本不得重复入账");
        assertEquals("FIRST", entries.get(0).get("entry_type"));
        assertEquals(settledVersion, ((Number) entries.get(0).get("new_version")).intValue());

        long netFen = entries.stream().mapToLong(e -> ((Number) e.get("amount_fen")).longValue()).sum();
        BigDecimal v1Reward = RewardCalculator.rewardKwh(new BigDecimal(committed),
                new BigDecimal(baselineV1), new BigDecimal(actualV1));
        BigDecimal v2Reward = RewardCalculator.rewardKwh(new BigDecimal(committed),
                new BigDecimal(baselineV1), new BigDecimal(actualV2));
        BigDecimal expectedSettledReward;
        long expectedNet;
        if (settledVersion == 1) {
            // 结算先于更正：首账按 v1（23→7kWh/560），随后一笔更正差额到 v2（25→5kWh/400）。
            assertEquals(2, entries.size(), "结算 v1 后应追加一笔更正差额");
            expectedSettledReward = v1Reward;
            expectedNet = RewardCalculator.amountFen(v2Reward, 80);
            assertEquals(RewardCalculator.amountFen(v1Reward, 80),
                    ((Number) entries.get(0).get("amount_fen")).longValue());
            assertEquals(RewardCalculator.amountFen(v2Reward, 80) - RewardCalculator.amountFen(v1Reward, 80),
                    ((Number) entries.get(1).get("amount_fen")).longValue(), "更正差额可为负");
        } else {
            // 更正先于结算：唯一首账直接按 v2 固化，不存在拼接。
            assertEquals(2, settledVersion, "存在 v1 读数时，结算版本只能是 1 或 2");
            assertEquals(1, entries.size(), "更正先提交时结算直接固化新版本，只产生一笔账");
            expectedSettledReward = v2Reward;
            expectedNet = RewardCalculator.amountFen(v2Reward, 80);
            assertFalse(entries.stream().anyMatch(e -> "CORRECTION".equals(e.get("entry_type"))));
        }
        assertEquals(expectedNet, netFen, "净结算额必须与最终读数重算一致");
        assertEquals(0, expectedSettledReward.compareTo((BigDecimal) settlement.get("reward_kwh")),
                "结算固化的奖励电量必须与其固化版本读数一致，不拼接新旧读数");
    }

    /** 线程内结算结果收集。 */
    private static final class CallableOutcome {
        private volatile SettlementView view;
        private volatile boolean success;
        private volatile boolean conflict409;
        private volatile int errorStatus;
        private volatile Exception otherError;
    }

    /** 一条已准备好的业务链：邀约 ID 与 UTC 时段。 */
    private record ChainContext(long invitationId, Instant start, Instant end) {
    }
}
