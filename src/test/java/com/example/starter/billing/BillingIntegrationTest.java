package com.example.starter.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.billing.service.BillingService;
import com.example.starter.billing.support.MutableClock;
import com.example.starter.billing.web.dto.AccountSummaryResponse;
import com.example.starter.billing.web.dto.CommitmentResponse;
import com.example.starter.billing.web.dto.CreateCommitmentRequest;
import com.example.starter.billing.web.dto.CreateOfferRequest;
import com.example.starter.billing.web.dto.OfferResponse;
import com.example.starter.billing.web.dto.ReadingResponse;
import com.example.starter.billing.web.dto.RegisterReadingRequest;
import com.example.starter.billing.web.dto.SettleRequest;
import com.example.starter.billing.web.dto.SettlementResponse;
import com.example.starter.support.ClockService;
import com.example.starter.web.error.ConflictException;
import com.example.starter.web.error.NotFoundException;
import com.example.starter.web.error.UnprocessableEntityException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 削峰结算业务链的真实 H2（MODE=MySQL）集成测试：成功、422/409 失败分支、
 * requestId 幂等、并发承诺、并发结算与计量更正竞争、差额账目与旧新版本展示。
 */
@SpringBootTest
class BillingIntegrationTest {

    private static final Instant START = Instant.parse("2026-10-01T18:00:00Z");
    private static final Instant END = Instant.parse("2026-10-01T19:00:00Z");
    private static final String USER = "user-001";

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        ClockService clockService() {
            return new MutableClock();
        }
    }

    @Autowired
    private BillingService billingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ClockService clock;

    private MutableClock mutableClock() {
        return (MutableClock) clock;
    }

    @BeforeEach
    void cleanDatabase() {
        // 仅清理本类测试数据（offer ID 以 t-offer- 开头），保留 data.sql 的合成种子邀约。
        jdbcTemplate.update("DELETE FROM settlement WHERE offer_id LIKE 't-offer-%'");
        jdbcTemplate.update("DELETE FROM meter_reading WHERE offer_id LIKE 't-offer-%'");
        jdbcTemplate.update("DELETE FROM commitment WHERE offer_id LIKE 't-offer-%'");
        jdbcTemplate.update("DELETE FROM demand_offer WHERE id LIKE 't-offer-%'");
        mutableClock().setNow(Instant.parse("2026-10-01T17:30:00Z"));
    }

    private String newOffer() {
        String offerId = "t-offer-" + System.nanoTime();
        OfferResponse offer = billingService.createOffer(
                new CreateOfferRequest(offerId, START, END));
        assertEquals(offerId, offer.offerId());
        return offerId;
    }

    private CommitmentResponse commit(String offerId, String userId, String kwh, String requestId) {
        return billingService.createCommitment(offerId,
                new CreateCommitmentRequest(userId, new BigDecimal(kwh), requestId));
    }

    private ReadingResponse reading(String offerId, String userId, String kind, String kwh,
                                    String reason) {
        return billingService.registerReading(offerId, userId, new RegisterReadingRequest(
                kind, new BigDecimal(kwh), START, END, reason));
    }

    @Test
    @DisplayName("主流程：承诺10/基线30/实际23，时段结束后结算奖励7kWh=560分，重放只产生一笔首次结算")
    void syntheticFlowSettles560AndReplayIsIdempotent() {
        String offerId = newOffer();
        commit(offerId, USER, "10", "req-c-1");
        reading(offerId, USER, "BASELINE", "30", null);
        reading(offerId, USER, "ACTUAL", "23", null);

        // 时段未结束 → 409，不产生账目。
        assertThrows(ConflictException.class,
                () -> billingService.settle(offerId, USER, new SettleRequest("req-s-1")));
        assertEquals(0, countSettlements(offerId), "409时不得产生账目");

        mutableClock().setNow(END);
        SettlementResponse first = billingService.settle(offerId, USER, new SettleRequest("req-s-1"));
        assertEquals("INITIAL", first.type());
        assertEquals(1, first.baselineVersion());
        assertEquals(1, first.actualVersion());
        assertEquals(0, new BigDecimal("7.0").compareTo(first.rewardKwh()));
        assertEquals(560L, first.amountFen());
        assertEquals(560L, first.totalAmountFen());
        assertNull(first.correctionReason());
        assertEquals(1, countSettlements(offerId));

        // 同参重放：返回原结果，不新增账目。
        SettlementResponse replay = billingService.settle(offerId, USER, new SettleRequest("req-s-1"));
        assertEquals(first.settlementId(), replay.settlementId());
        assertEquals(1, countSettlements(offerId), "重试仍只产生一笔首次结算");

        AccountSummaryResponse summary = billingService.getSummary(offerId, USER);
        assertEquals(0, new BigDecimal("10").compareTo(summary.committedKwh()));
        assertEquals(1, summary.baselineVersion());
        assertEquals(1, summary.actualVersion());
        assertEquals(0, new BigDecimal("7.0").compareTo(summary.rewardKwh()));
        assertEquals(560L, summary.netSettlementFen());
        assertEquals(1, summary.settlements().size());
    }

    @Test
    @DisplayName("失败422：读数缺失、负电量、两个读数时段不一致，均不产生账目")
    void unprocessableCases() {
        String offerId = newOffer();
        commit(offerId, USER, "10", "req-c-2");

        // 负电量读数拒绝。
        assertThrows(UnprocessableEntityException.class,
                () -> reading(offerId, USER, "ACTUAL", "-0.1", null));

        reading(offerId, USER, "BASELINE", "30", null);
        mutableClock().setNow(END);
        // 实际读数缺失。
        assertThrows(UnprocessableEntityException.class,
                () -> billingService.settle(offerId, USER, new SettleRequest("req-s-2a")));
        assertEquals(0, countSettlements(offerId), "读数缺失不得入账");

        // 直接落一条时段不一致的实际读数（模拟历史脏数据），结算侧须再次拦截。
        jdbcTemplate.update("""
                INSERT INTO meter_reading
                    (offer_id, user_id, kind, version, value_kwh, period_start, period_end,
                     correction_reason, created_at)
                VALUES (?, ?, 'ACTUAL', 1, 23.000, ?, ?, NULL, ?)
                """, offerId, USER,
                java.sql.Timestamp.from(Instant.parse("2026-10-01T17:00:00Z")),
                java.sql.Timestamp.from(START), java.sql.Timestamp.from(Instant.now()));
        assertThrows(UnprocessableEntityException.class,
                () -> billingService.settle(offerId, USER, new SettleRequest("req-s-2b")));
        assertEquals(0, countSettlements(offerId), "读数时段不一致不得入账");

        // 登记接口也拦截与邀约时段不一致的读数。
        UnprocessableEntityException ex = assertThrows(UnprocessableEntityException.class,
                () -> billingService.registerReading(offerId, USER, new RegisterReadingRequest(
                        "BASELINE", new BigDecimal("29"), START, START.plusSeconds(1800), null)));
        assertTrue(ex.getMessage().contains("时段不一致"));
    }

    @Test
    @DisplayName("失败409：时段未结束结算被拒绝")
    void settleBeforeWindowEndIsConflict() {
        String offerId = newOffer();
        commit(offerId, USER, "10", "req-c-3");
        reading(offerId, USER, "BASELINE", "30", null);
        reading(offerId, USER, "ACTUAL", "23", null);
        mutableClock().setNow(END.minusSeconds(1));
        assertThrows(ConflictException.class,
                () -> billingService.settle(offerId, USER, new SettleRequest("req-s-3")));
        assertEquals(0, countSettlements(offerId));
    }

    @Test
    @DisplayName("承诺约束：上限50、必须为正、每用户每邀约仅一个有效承诺")
    void commitmentConstraints() {
        String offerId = newOffer();
        assertThrows(UnprocessableEntityException.class,
                () -> commit(offerId, USER, "50.1", "req-c-4a"));
        assertThrows(UnprocessableEntityException.class,
                () -> commit(offerId, USER, "0", "req-c-4b"));
        CommitmentResponse ok = commit(offerId, USER, "50", "req-c-4c");
        assertNotNull(ok.commitmentId());
        assertThrows(ConflictException.class,
                () -> commit(offerId, USER, "10", "req-c-4d"));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM commitment WHERE offer_id = ? AND user_id = ?",
                Integer.class, offerId, USER));
    }

    @Test
    @DisplayName("承诺requestId：同参重放原结果，异参409")
    void commitmentRequestIdReplay() {
        String offerId = newOffer();
        CommitmentResponse first = commit(offerId, USER, "10", "req-c-5");
        CommitmentResponse replay = commit(offerId, USER, "10", "req-c-5");
        assertEquals(first.commitmentId(), replay.commitmentId());
        assertThrows(ConflictException.class,
                () -> commit(offerId, USER, "11", "req-c-5"));
        assertThrows(ConflictException.class,
                () -> commit(offerId, "other-user", "10", "req-c-5"));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM commitment WHERE request_id = 'req-c-5'", Integer.class));
    }

    @Test
    @DisplayName("结算requestId：同参重放原结果，异参409；相同读数版本组合不得二次入账")
    void settlementRequestIdReplayAndVersionPairUnique() {
        String offerId = newOffer();
        commit(offerId, USER, "10", "req-c-6");
        reading(offerId, USER, "BASELINE", "30", null);
        reading(offerId, USER, "ACTUAL", "23", null);
        mutableClock().setNow(END);

        SettlementResponse first = billingService.settle(offerId, USER, new SettleRequest("req-s-6"));
        SettlementResponse replay = billingService.settle(offerId, USER, new SettleRequest("req-s-6"));
        assertEquals(first.settlementId(), replay.settlementId());

        // 同一 requestId 指向其他结算目标 → 409。
        assertThrows(ConflictException.class,
                () -> billingService.settle(offerId, "user-002", new SettleRequest("req-s-6")));
        // 换 requestId 但读数版本未变 → 409，且仍只有一笔。
        assertThrows(ConflictException.class,
                () -> billingService.settle(offerId, USER, new SettleRequest("req-s-6-other")));
        assertEquals(1, countSettlements(offerId));
    }

    @Test
    @DisplayName("读数更正：生成新版本校正记录，原读数不被覆盖；首次带缘由/更正无缘由均422")
    void readingCorrectionRules() {
        String offerId = newOffer();
        commit(offerId, USER, "10", "req-c-7");
        assertThrows(UnprocessableEntityException.class,
                () -> reading(offerId, USER, "BASELINE", "30", "首次不应有缘由"));
        ReadingResponse v1 = reading(offerId, USER, "BASELINE", "30", null);
        assertEquals(1, v1.version());
        assertThrows(UnprocessableEntityException.class,
                () -> reading(offerId, USER, "BASELINE", "29", null));
        ReadingResponse v2 = reading(offerId, USER, "BASELINE", "29", "表计倍率更正");
        assertEquals(2, v2.version());
        assertEquals("表计倍率更正", v2.correctionReason());
        // 原行仍在且值不变。
        BigDecimal oldValue = jdbcTemplate.queryForObject(
                "SELECT value_kwh FROM meter_reading WHERE id = ?", BigDecimal.class, v1.readingId());
        assertEquals(0, new BigDecimal("30.000").compareTo(oldValue));
    }

    @Test
    @DisplayName("更正后结算：追加负差额账目，不覆盖原结算；查询展示更正缘由与旧新版本，净额=160分")
    void adjustmentCreatesNegativeDeltaAndShowsVersions() {
        String offerId = newOffer();
        commit(offerId, USER, "10", "req-c-8");
        reading(offerId, USER, "BASELINE", "30", null);
        reading(offerId, USER, "ACTUAL", "23", null);
        mutableClock().setNow(END);
        SettlementResponse initial = billingService.settle(offerId, USER, new SettleRequest("req-s-8a"));
        assertEquals(560L, initial.amountFen());

        // 实际电量由 23 更正为 28：压降 2kWh，奖励 2.0kWh=160分，差额 -400 分。
        reading(offerId, USER, "ACTUAL", "28", "表计数据迟到后更正");
        SettlementResponse adjustment = billingService.settle(offerId, USER, new SettleRequest("req-s-8b"));
        assertEquals("ADJUSTMENT", adjustment.type());
        assertEquals(1, adjustment.baselineVersion());
        assertEquals(2, adjustment.actualVersion());
        assertEquals(1, adjustment.previousBaselineVersion(), "查询需展示旧基线版本");
        assertEquals(1, adjustment.previousActualVersion(), "查询需展示旧实际版本");
        assertEquals(-400L, adjustment.amountFen(), "差额可为负");
        assertEquals(160L, adjustment.totalAmountFen());
        assertEquals(initial.settlementId(), adjustment.correctedSettlementId());
        assertEquals("表计数据迟到后更正", adjustment.correctionReason());
        // 原结算行未被覆盖。
        assertEquals(560L, jdbcTemplate.queryForObject(
                "SELECT amount_fen FROM settlement WHERE id = ?", Long.class, initial.settlementId()));
        assertEquals(2, countSettlements(offerId));

        AccountSummaryResponse summary = billingService.getSummary(offerId, USER);
        assertEquals(160L, summary.netSettlementFen(), "净结算额=560-400");
        assertEquals(2, summary.actualVersion());
        assertEquals(0, new BigDecimal("28").compareTo(summary.actualKwh()));
        assertEquals(0, new BigDecimal("2.0").compareTo(summary.rewardKwh()));
    }

    @Test
    @DisplayName("并发承诺：两个请求同时到达，仅一个成功，数据库恰有一条承诺")
    void concurrentCommitmentsOnlyOneWins() throws Exception {
        String offerId = newOffer();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 任一线程都可能在唯一约束上落败，双方都收集结果后再断言。
            Future<Throwable> f1 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    commit(offerId, USER, "10", "req-c-9a");
                    return null;
                } catch (ConflictException ex) {
                    return ex;
                }
            });
            Future<Throwable> f2 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    commit(offerId, USER, "10", "req-c-9b");
                    return null;
                } catch (ConflictException ex) {
                    return ex;
                }
            });
            Throwable r1 = f1.get(30, TimeUnit.SECONDS);
            Throwable r2 = f2.get(30, TimeUnit.SECONDS);
            assertTrue(r1 == null ^ r2 == null, "并发承诺必须恰有一方成功、一方409");
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM commitment WHERE offer_id = ? AND user_id = ?",
                    Integer.class, offerId, USER));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发首次结算：同一requestId同时提交，仅产生一笔INITIAL，双方得到同一结果")
    void concurrentInitialSettlementSameRequestId() throws Exception {
        String offerId = newOffer();
        commit(offerId, USER, "10", "req-c-10");
        reading(offerId, USER, "BASELINE", "30", null);
        reading(offerId, USER, "ACTUAL", "23", null);
        mutableClock().setNow(END);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Long> f1 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return billingService.settle(offerId, USER, new SettleRequest("req-s-10"))
                        .settlementId();
            });
            Future<Long> f2 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return billingService.settle(offerId, USER, new SettleRequest("req-s-10"))
                        .settlementId();
            });
            assertEquals(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS),
                    "并发同参重放必须返回同一笔账目");
            assertEquals(1, countSettlements(offerId));
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM settlement WHERE type = 'INITIAL'", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("计量更正与首次结算竞争：结算只取同一已提交版本，绝不拼接旧基线与新实际")
    void correctionRacingWithInitialSettlementNeverMixesVersions() throws Exception {
        String offerId = newOffer();
        commit(offerId, USER, "10", "req-c-11");
        reading(offerId, USER, "BASELINE", "30", null);
        reading(offerId, USER, "ACTUAL", "23", null);
        mutableClock().setNow(END);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<SettlementResponse> settleFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return billingService.settle(offerId, USER, new SettleRequest("req-s-11"));
            });
            Future<?> correctionFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return reading(offerId, USER, "BASELINE", "29", "基线模型修订");
            });
            SettlementResponse initial = settleFuture.get(30, TimeUnit.SECONDS);
            correctionFuture.get(30, TimeUnit.SECONDS);

            // 唯一一笔 INITIAL：要么用旧基线v1=30（560分），要么用新基线v2=29（480分），
            // 二者都是同一已提交版本快照；不存在“读一半”的第三种状态。
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM settlement WHERE type = 'INITIAL'", Integer.class));
            assertEquals(1, initial.actualVersion());
            if (initial.baselineVersion() == 1) {
                // 结算先于更正：INITIAL=560，随后重算产生 -80 差额。
                assertEquals(0, new BigDecimal("30.000").compareTo(initial.baselineKwh()));
                assertEquals(560L, initial.amountFen());
                SettlementResponse adjustment = billingService.settle(
                        offerId, USER, new SettleRequest("req-s-11-adj"));
                assertEquals("ADJUSTMENT", adjustment.type());
                assertEquals(2, adjustment.baselineVersion());
                assertEquals(-80L, adjustment.amountFen(), "480-560=-80");
                assertEquals(480L, adjustment.totalAmountFen());
            } else {
                // 更正先于结算：INITIAL 直接基于新基线，只有一笔账目，重复版本组合拒绝。
                assertEquals(2, initial.baselineVersion());
                assertEquals(0, new BigDecimal("29.000").compareTo(initial.baselineKwh()));
                assertEquals(480L, initial.amountFen());
                assertThrows(ConflictException.class,
                        () -> billingService.settle(offerId, USER, new SettleRequest("req-s-11-dup")));
                assertEquals(1, countSettlements(offerId));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("无邀约查询返回404；无承诺不能登记读数")
    void notFoundAndPrerequisite() {
        assertThrows(NotFoundException.class,
                () -> billingService.getSummary("missing-offer", USER));
        String offerId = newOffer();
        assertThrows(UnprocessableEntityException.class,
                () -> reading(offerId, "user-without-commitment", "BASELINE", "30", null));
    }

    private int countSettlements(String offerId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM settlement WHERE offer_id = ?", Integer.class, offerId);
    }
}
