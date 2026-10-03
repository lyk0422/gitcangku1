package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.service.ApiException;
import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 并发领用与召回的真实 H2 测试：行锁串行化，扣减不超剩余量。 */
class DispenseConcurrencyTest extends AbstractReagentTest {

    private static final java.time.Instant T0 = TestClockConfig.T0;

    /** 10 mL 瓶先领 6 mL，两次并发 3 mL 只能成功一次，最终剩余 1 mL。 */
    @Test
    void concurrentDispensesNeverExceedRemaining() throws Exception {
        long id = receiveBottle("LOT-C1", "10.0", T0.plus(60, ChronoUnit.DAYS));
        service.open(id);
        service.dispense(id, "r-first", new BigDecimal("6.0"));

        var results = runConcurrently(
                () -> service.dispense(id, "c1", new BigDecimal("3.0")),
                () -> service.dispense(id, "c2", new BigDecimal("3.0")));

        long successes = results.stream().filter(r -> r instanceof com.example.starter.web.dto.ApiDtos.DispenseView).count();
        long conflicts = results.stream().filter(this::isUnprocessable).count();
        assertEquals(1, successes, "并发两笔 3 mL 只能成功一笔");
        assertEquals(1, conflicts, "另一笔必须因剩余量不足被拒绝");

        var detail = service.detail(id);
        assertEquals(0, new BigDecimal("1.0").compareTo(detail.bottle().remainingMl()),
                "最终剩余量必须为 1 mL");
        assertEquals(2, detail.dispenses().size(), "成功领用流水为 6 mL 与 3 mL 两笔");
    }

    /** 召回与领用并发：领用只依据同一已提交状态完成，召回生效后不能继续扣量。 */
    @Test
    void concurrentRecallAndDispenseStayConsistent() throws Exception {
        long id = receiveBottle("LOT-C2", "10.0", T0.plus(60, ChronoUnit.DAYS));
        service.open(id);
        service.dispense(id, "r-pre", new BigDecimal("6.0"));

        var results = runConcurrently(
                () -> service.recall("LOT-C2", "紧急召回"),
                () -> service.dispense(id, "c-race", new BigDecimal("3.0")));

        var detail = service.detail(id);
        assertTrue(detail.bottle().quarantined(), "召回必须生效");
        boolean dispensed = results.stream().anyMatch(r -> r instanceof com.example.starter.web.dto.ApiDtos.DispenseView);
        if (dispensed) {
            // 领用在召回提交前完成：扣减到 1 mL，历史保留
            assertEquals(0, new BigDecimal("1.0").compareTo(detail.bottle().remainingMl()));
            assertEquals(2, detail.dispenses().size());
        } else {
            // 召回先提交：领用被拒绝，剩余量保持 4 mL，无部分扣减
            assertEquals(0, new BigDecimal("4.0").compareTo(detail.bottle().remainingMl()));
            assertEquals(1, detail.dispenses().size());
        }
        // 召回生效后继续领用必须被拒绝
        var ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.dispense(id, "c-after", new BigDecimal("1.0")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
    }

    private boolean isUnprocessable(Object result) {
        return result instanceof ApiException e && e.status() == HttpStatus.UNPROCESSABLE_ENTITY;
    }

    /** 两个任务同一起跑线并发执行，返回结果或捕获的异常，整体超时 30 秒。 */
    private List<Object> runConcurrently(Callable<Object> first, Callable<Object> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<Object> wrapFirst = () -> { ready.countDown(); start.await(10, TimeUnit.SECONDS); return first.call(); };
        Callable<Object> wrapSecond = () -> { ready.countDown(); start.await(10, TimeUnit.SECONDS); return second.call(); };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var f1 = pool.submit(() -> capture(wrapFirst));
            var f2 = pool.submit(() -> capture(wrapSecond));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "并发线程未就绪");
            start.countDown();
            return List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程池未及时退出");
        }
    }

    private Object capture(Callable<Object> task) {
        try {
            return task.call();
        } catch (ApiException e) {
            return e;
        } catch (Exception e) {
            return assertInstanceOf(ApiException.class, e, "只应出现业务异常");
        }
    }
}