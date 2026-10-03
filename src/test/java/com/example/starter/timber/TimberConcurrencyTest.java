package com.example.starter.timber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.starter.timber.web.ApiException;
import com.example.starter.timber.web.TimberDtos.RegisterSourceRequest;
import com.example.starter.timber.web.TimberDtos.SplitRequest;
import com.example.starter.timber.web.TimberDtos.TransferRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 并发竞争测试：同一叶子批次的拆分与移交、拆分与拆分竞争时只有一个成功，
 * 且并发拆分保持重量守恒。两个线程用闩锁同时起跑，结果带超时获取。
 */
@SpringBootTest
class TimberConcurrencyTest {

    @Autowired
    private TimberService service;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM transfer_order");
        jdbc.update("DELETE FROM timber_batch");
        jdbc.update("DELETE FROM timber_source");
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    /**
     * 并发执行两个操作并等待结束，返回成功个数；业务冲突(ApiException)计为失败，
     * 其余异常向外抛出使测试失败。
     */
    private int raceAndCountSuccess(Runnable first, Runnable second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        Runnable task1 = () -> run(ready, start, first, success);
        Runnable task2 = () -> run(ready, start, second, success);
        Future<?> f1 = pool.submit(task1);
        Future<?> f2 = pool.submit(task2);
        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未就绪");
        start.countDown();
        f1.get(20, TimeUnit.SECONDS);
        f2.get(20, TimeUnit.SECONDS);
        return success.get();
    }

    private static void run(CountDownLatch ready, CountDownLatch start, Runnable action,
                            AtomicInteger success) {
        ready.countDown();
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        try {
            action.run();
            success.incrementAndGet();
        } catch (ApiException expected) {
            // 竞争失败方：业务冲突，属预期结果
        }
    }

    @Test
    void splitAndTransferRaceExactlyOneSucceeds() throws Exception {
        service.registerSource(new RegisterSourceRequest("V-C1", "红松", 100, "加工点A"));

        Runnable split = () -> service.split("BATCH-V-C1", new SplitRequest("S-C1",
                List.of(new SplitChildInput("B-C1-60", 60), new SplitChildInput("B-C1-40", 40))));
        Runnable transfer = () -> service.createTransfer("BATCH-V-C1",
                new TransferRequest("T-C1", "加工点A", "下游B"));

        int succeeded = raceAndCountSuccess(split, transfer);
        assertEquals(1, succeeded, "拆分与移交竞争必须恰好一个成功");

        long children = count("SELECT COUNT(*) FROM timber_batch WHERE parent_id IS NOT NULL");
        long pending = count("SELECT COUNT(*) FROM transfer_order WHERE status = 'PENDING'");
        boolean splitWon = children == 2 && pending == 0;
        boolean transferWon = children == 0 && pending == 1;
        assertTrue(splitWon || transferWon,
                "终态必须一致：children=" + children + ", pending=" + pending);
        if (splitWon) {
            assertEquals(100, sum("SELECT COALESCE(SUM(weight_kg),0) FROM timber_batch"
                    + " WHERE parent_id IS NOT NULL"));
        }
        assertEquals(1, count("SELECT COUNT(*) FROM request_log"), "失败事务不能留下请求键");
    }

    @Test
    void concurrentSplitsConserveWeightAndOnlyOneSucceeds() throws Exception {
        service.registerSource(new RegisterSourceRequest("V-C2", "红松", 100, "加工点A"));

        Runnable splitA = () -> service.split("BATCH-V-C2", new SplitRequest("S-C2-A",
                List.of(new SplitChildInput("B-C2-A60", 60), new SplitChildInput("B-C2-A40", 40))));
        Runnable splitB = () -> service.split("BATCH-V-C2", new SplitRequest("S-C2-B",
                List.of(new SplitChildInput("B-C2-B70", 70), new SplitChildInput("B-C2-B30", 30))));

        int succeeded = raceAndCountSuccess(splitA, splitB);
        assertEquals(1, succeeded, "并发拆分必须恰好一个成功");

        assertEquals(2, count("SELECT COUNT(*) FROM timber_batch WHERE parent_id IS NOT NULL"));
        assertEquals(100, sum("SELECT COALESCE(SUM(weight_kg),0) FROM timber_batch"
                + " WHERE parent_id IS NOT NULL"));
        assertEquals(1, count("SELECT COUNT(*) FROM request_log"), "失败事务不能留下请求键");
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private long sum(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}