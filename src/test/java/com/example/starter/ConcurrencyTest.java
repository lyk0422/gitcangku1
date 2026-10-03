package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.error.BizException;
import com.example.starter.service.DisputeService;
import com.example.starter.service.OpResult;
import com.example.starter.service.SettlementService;
import com.example.starter.service.TicketService;
import com.example.starter.web.dto.Requests.AdjudicateRequest;
import com.example.starter.web.dto.Requests.CreateTicketRequest;
import com.example.starter.web.dto.Requests.FreezeRequest;
import com.example.starter.web.dto.Requests.GrossRequest;
import com.example.starter.web.dto.Requests.ReweighSubmitRequest;
import com.example.starter.web.dto.Requests.TareRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** 并发边界：并发裁决只形成一个最终净重；复称提交与冻结互斥。 */
@SpringBootTest
@Import(TestClockConfig.class)
class ConcurrencyTest {

    private static final Instant T0 = TestClockConfig.T0;
    private static final Instant GROSS = T0.plus(Duration.ofMinutes(20));

    @Autowired
    TicketService tickets;
    @Autowired
    DisputeService disputes;
    @Autowired
    SettlementService settlements;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MutableClock clock;

    private ExecutorService pool;

    record Outcome<T>(T value, Throwable error) {
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM errata_request");
        jdbc.update("DELETE FROM settlement_freeze");
        jdbc.update("DELETE FROM dispute");
        jdbc.update("DELETE FROM reweigh_request");
        jdbc.update("DELETE FROM weigh_ticket");
        clock.set(T0);
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void shutdown() throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    private long grossedTicket(String p) {
        long id = tickets.createTicket(new CreateTicketRequest(p + "-c", "TO-SYN-0001", "SYN-TRUCK-01")).body().id();
        tickets.recordTare(id, new TareRequest(p + "-t", 8000, T0));
        clock.set(GROSS);
        tickets.recordGross(id, new GrossRequest(p + "-g", 12000, GROSS));
        return id;
    }

    /** 两个任务同一起跑线并发执行，各自限时15秒返回结果或异常。 */
    private <T> List<Outcome<T>> race(Callable<T> a, Callable<T> b) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<T> wrapA = () -> { ready.countDown(); start.await(10, TimeUnit.SECONDS); return a.call(); };
        Callable<T> wrapB = () -> { ready.countDown(); start.await(10, TimeUnit.SECONDS); return b.call(); };
        Future<T> fa = pool.submit(wrapA);
        Future<T> fb = pool.submit(wrapB);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        return List.of(outcome(fa), outcome(fb));
    }

    private static <T> Outcome<T> outcome(Future<T> f) {
        try {
            return new Outcome<>(f.get(15, TimeUnit.SECONDS), null);
        } catch (Exception e) {
            Throwable cause = e instanceof java.util.concurrent.ExecutionException ? e.getCause() : e;
            return new Outcome<>(null, cause);
        }
    }

    @Test
    void concurrentAdjudication_onlyOneFinalNetWeight() throws Exception {
        long ticketId = grossedTicket("cc1");
        clock.set(GROSS.plus(Duration.ofMinutes(5)));
        long reweighId = disputes.submitReweigh(ticketId,
                new ReweighSubmitRequest("cc1-rw", 12100, "司机异议")).body().id();
        long disputeId = jdbc.queryForObject("SELECT id FROM dispute WHERE ticket_id = " + ticketId, Long.class);

        List<Outcome<OpResult<?>>> results = race(
                () -> disputes.adjudicate(disputeId, new AdjudicateRequest(
                        "cc1-adj-a", "REWEIGH", reweighId, "采纳复称", "裁决员甲")),
                () -> disputes.adjudicate(disputeId, new AdjudicateRequest(
                        "cc1-adj-b", "ORIGINAL", null, "采纳原称量", "裁决员乙")));

        long ok = results.stream().filter(r -> r.error() == null).count();
        long conflicted = results.stream().filter(r -> r.error() instanceof BizException
                && ((BizException) r.error()).status() == HttpStatus.CONFLICT).count();
        assertEquals(1, ok, "并发裁决只有一个生效");
        assertEquals(1, conflicted, "另一个必须409");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispute WHERE ticket_id = " + ticketId + " AND status = 'RESOLVED'",
                Long.class).intValue());

        settlements.freeze(ticketId, new FreezeRequest("cc1-freeze", List.of(reweighId)));
        List<Integer> nets = jdbc.queryForList(
                "SELECT net_kg FROM settlement_freeze WHERE ticket_id = " + ticketId, Integer.class);
        assertEquals(1, nets.size(), "只形成一个最终净重");
        String adopted = jdbc.queryForObject(
                "SELECT adopted_source FROM dispute WHERE ticket_id = " + ticketId, String.class);
        assertEquals("REWEIGH".equals(adopted) ? 4100 : 4000, nets.get(0).intValue(),
                "冻结净重与裁决采纳来源一致");
    }

    @Test
    void concurrentFreeze_singleSnapshot() throws Exception {
        long ticketId = grossedTicket("cc2");
        List<Outcome<OpResult<?>>> results = race(
                () -> settlements.freeze(ticketId, new FreezeRequest("cc2-fa", List.of())),
                () -> settlements.freeze(ticketId, new FreezeRequest("cc2-fb", List.of())));
        long ok = results.stream().filter(r -> r.error() == null).count();
        assertEquals(1, ok);
        assertEquals(1, results.stream().filter(r -> r.error() instanceof BizException).count());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_freeze WHERE ticket_id = " + ticketId, Long.class).intValue());
        assertEquals(4000, jdbc.queryForObject(
                "SELECT net_kg FROM settlement_freeze WHERE ticket_id = " + ticketId, Integer.class).intValue());
    }

    @Test
    void reweighVsFreeze_mutuallyExclusive() throws Exception {
        long ticketId = grossedTicket("cc3");
        clock.set(GROSS.plus(Duration.ofMinutes(5)));
        List<Outcome<Object>> results = race(
                () -> disputes.submitReweigh(ticketId, new ReweighSubmitRequest("cc3-rw", 12100, "异议")),
                () -> settlements.freeze(ticketId, new FreezeRequest("cc3-freeze", List.of())));
        long ok = results.stream().filter(r -> r.error() == null).count();
        assertEquals(1, ok, "复称提交与净重冻结竞争时只有一个成功");
        assertEquals(1, results.stream().filter(r -> r.error() instanceof BizException).count());

        long reweighCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reweigh_request WHERE ticket_id = " + ticketId, Long.class);
        Long freezeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_freeze WHERE ticket_id = " + ticketId, Long.class);
        if (reweighCount == 1) {
            assertEquals(0, freezeCount, "复称先提交则冻结必须让位（版本集合不含新复称）");
            assertNotNull(jdbc.queryForObject(
                    "SELECT id FROM dispute WHERE ticket_id = " + ticketId + " AND status = 'OPEN'", Long.class));
        } else {
            assertEquals(1, freezeCount, "冻结先完成则复称被拒绝");
            assertTrue(jdbc.queryForList(
                    "SELECT id FROM dispute WHERE ticket_id = " + ticketId).isEmpty(), "不应产生争议");
        }
    }
}