package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.domain.ReagentBottle;
import com.example.starter.service.BizException;
import com.example.starter.service.ReagentService;
import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfig;
import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 并发领用测试：10 mL 瓶先领 6 mL，两个线程同时申请 3 mL，
 * 只能按提交顺序成功一笔，最终剩余 1 mL，失败请求不占幂等键。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class ReagentConcurrencyTest {

    @Autowired
    private ReagentService service;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM dispense_record");
        jdbc.update("DELETE FROM recall_record");
        jdbc.update("DELETE FROM reagent_bottle");
        clock.setInstant(TestClockConfig.T0);
    }

    @Test
    void concurrentDispensesSerializeOnCommittedState() throws Exception {
        ReagentBottle bottle = service.receive("B1", new BigDecimal("10.0"),
                TestClockConfig.T0.plus(100, ChronoUnit.DAYS));
        service.open(bottle.id());
        service.dispense(bottle.id(), "r0", new BigDecimal("6.0"));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> taskA = () -> dispenseAsync(bottle.id(), "rA", ready, start);
            Callable<String> taskB = () -> dispenseAsync(bottle.id(), "rB", ready, start);
            Future<String> futureA = pool.submit(taskA);
            Future<String> futureB = pool.submit(taskB);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<String> results = List.of(
                    futureA.get(15, TimeUnit.SECONDS),
                    futureB.get(15, TimeUnit.SECONDS));

            assertEquals(1, results.stream().filter("SUCCESS"::equals).count());
            assertEquals(1, results.stream().filter("INSUFFICIENT_REMAINING"::equals).count());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        var detail = service.detail(bottle.id());
        assertEquals(new BigDecimal("1.0"), detail.bottle().remainingMl());
        assertEquals(2, detail.dispenses().size());
        assertEquals(new BigDecimal("1.0"),
                detail.dispenses().get(1).remainingAfterMl());

        Integer loserKeys = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispense_record WHERE request_id IN ('rA','rB')",
                Integer.class);
        assertEquals(1, loserKeys);
    }

    private String dispenseAsync(long bottleId, String requestId,
                                 CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        try {
            service.dispense(bottleId, requestId, new BigDecimal("3.0"));
            return "SUCCESS";
        } catch (BizException ex) {
            return ex.code();
        }
    }
}