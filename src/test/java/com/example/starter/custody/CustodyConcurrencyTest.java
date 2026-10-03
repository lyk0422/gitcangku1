package com.example.starter.custody;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.custody.CustodyDtos.ChildSpec;
import com.example.starter.custody.CustodyDtos.SourceRequest;
import com.example.starter.custody.CustodyDtos.SplitRequest;
import com.example.starter.custody.CustodyDtos.TransferRequest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 并发测试:同一叶子批次的拆分与移交竞争时只有一个成功,并发拆分保持重量守恒。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:timber-concurrency-test;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa"})
class CustodyConcurrencyTest {

    @Autowired
    private CustodyService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_key");
        jdbc.update("DELETE FROM transfer");
        jdbc.update("DELETE FROM batch");
    }

    @Test
    void splitAndTransferCompeteOnSameLeafAndOnlyOneWins() throws Exception {
        service.registerSource(new SourceRequest("VCH-C1", "松木", 100, "SRC-C1", "加工点A"));
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            AtomicInteger splitOk = new AtomicInteger();
            AtomicInteger transferOk = new AtomicInteger();
            Future<?> split = pool.submit(() -> {
                await(gate);
                try {
                    service.split("SRC-C1", new SplitRequest("req-cc-split",
                            List.of(new ChildSpec("CC-60", 60, null),
                                    new ChildSpec("CC-40", 40, null))));
                    splitOk.incrementAndGet();
                } catch (ApiException expected) {
                    // 竞争失败方收到409
                }
            });
            Future<?> transfer = pool.submit(() -> {
                await(gate);
                try {
                    service.createTransfer("SRC-C1", new TransferRequest("加工点A", "下游B"));
                    transferOk.incrementAndGet();
                } catch (ApiException expected) {
                    // 竞争失败方收到409
                }
            });
            gate.countDown();
            split.get(30, TimeUnit.SECONDS);
            transfer.get(30, TimeUnit.SECONDS);
            assertEquals(1, splitOk.get() + transferOk.get(), "拆分与移交只能有一个成功");
            if (splitOk.get() == 1) {
                Integer sum = jdbc.queryForObject(
                        "SELECT COALESCE(SUM(weight_kg),0) FROM batch WHERE parent_id IS NOT NULL",
                        Integer.class);
                assertEquals(100, sum, "并发拆分必须守恒");
            } else {
                assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM transfer WHERE status = 'PENDING'", Integer.class));
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentSplitsOnSameLeafOnlyOneSucceedsAndWeightIsConserved() throws Exception {
        service.registerSource(new SourceRequest("VCH-C2", "松木", 100, "SRC-C2", "加工点A"));
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger tag = new AtomicInteger();
            Runnable attempt = () -> {
                await(gate);
                try {
                    String suffix = tag.incrementAndGet() == 1 ? "A" : "B";
                    service.split("SRC-C2", new SplitRequest("req-cs-" + suffix,
                            List.of(new ChildSpec("CS-60-" + suffix, 60, null),
                                    new ChildSpec("CS-40-" + suffix, 40, null))));
                    successes.incrementAndGet();
                } catch (ApiException expected) {
                    // 竞争失败方收到409
                }
            };
            Future<?> first = pool.submit(attempt);
            Future<?> second = pool.submit(attempt);
            gate.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
            assertEquals(1, successes.get(), "并发拆分同一叶子批次只能成功一次");
            Integer sum = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(weight_kg),0) FROM batch WHERE parent_id IS NOT NULL",
                    Integer.class);
            assertEquals(100, sum, "失败事务不能留下部分子批次,重量必须守恒");
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM batch WHERE parent_id IS NOT NULL", Integer.class));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch gate) {
        try {
            gate.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}