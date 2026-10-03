package com.example.starter.deacid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发状态测试：真实线程并发操作 H2，协调同时进入并设超时，核对最终数据。
 */
@SpringBootTest
class DeacidConcurrencyTest {

    @Autowired
    private DeacidService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        for (String table : List.of("seal_request", "enroll_request", "qc_rejection",
                "treatment_record", "page_occupancy", "batch_member", "deacid_batch", "archive_page")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void concurrentDoubleEnrollBlockedByUniqueConstraint() throws Exception {
        long pageId = service.registerPage("P-C01");
        long batchA = service.createBatch("B-C01");
        long batchB = service.createBatch("B-C02");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        Runnable task = () -> {
            long batch = Thread.currentThread().getName().endsWith("1") ? batchA : batchB;
            String requestId = "enr-c-" + Thread.currentThread().getName();
            ready.countDown();
            try {
                go.await(5, TimeUnit.SECONDS);
                service.enroll(batch, requestId, "P-C01");
                success.incrementAndGet();
            } catch (BusinessException e) {
                assertEquals(409, e.status().value());
                conflict.incrementAndGet();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        Future<?> f1 = pool.submit(task, "enroller-1");
        Future<?> f2 = pool.submit(task, "enroller-2");
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        f1.get(15, TimeUnit.SECONDS);
        f2.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, success.get(), "并发双入组只允许一个成功");
        assertEquals(1, conflict.get(), "失败方必须收到 409，可用原键重试");
        Integer occupancies = jdbc.queryForObject(
                "SELECT COUNT(*) FROM page_occupancy WHERE page_id = ?", Integer.class, pageId);
        assertEquals(1, occupancies, "唯一约束保证只有一条占用");
        Integer members = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_member WHERE page_id = ?", Integer.class, pageId);
        assertEquals(1, members, "同一页只能入组一个进行中批次");
    }

    @Test
    void sealAndReleaseRaceResolvesByCommitOrder() throws Exception {
        long p1 = service.registerPage("P-C10");
        long p2 = service.registerPage("P-C11");
        long batch = service.createBatch("B-C10");
        service.enroll(batch, "enr-c1", "P-C10");
        service.enroll(batch, "enr-c2", "P-C11");
        service.addTreatment(batch, p1, new BigDecimal("6.2"), new BigDecimal("7.0"), "RG-C10");
        service.addTreatment(batch, p2, new BigDecimal("6.3"), new BigDecimal("7.1"), "RG-C10");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger released = new AtomicInteger();
        AtomicInteger sealed = new AtomicInteger();
        Runnable sealTask = () -> {
            ready.countDown();
            try {
                go.await(5, TimeUnit.SECONDS);
                service.seal(batch, "seal-c1");
                sealed.incrementAndGet();
            } catch (BusinessException ignored) {
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        Runnable releaseTask = () -> {
            ready.countDown();
            try {
                go.await(5, TimeUnit.SECONDS);
                service.releasePage(batch, p2);
                released.incrementAndGet();
            } catch (BusinessException e) {
                assertEquals(409, e.status().value(), "封存先提交时归还必须 409");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        Future<?> f1 = pool.submit(sealTask);
        Future<?> f2 = pool.submit(releaseTask);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        f1.get(15, TimeUnit.SECONDS);
        f2.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        String status = jdbc.queryForObject(
                "SELECT status FROM deacid_batch WHERE id = ?", String.class, batch);
        boolean p2Member = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_member WHERE batch_id = ? AND page_id = ?",
                Integer.class, batch, p2) > 0;
        Integer p2Occupancy = jdbc.queryForObject(
                "SELECT COUNT(*) FROM page_occupancy WHERE page_id = ?", Integer.class, p2);

        if (released.get() == 1) {
            assertTrue(!p2Member, "归还先提交则 P-C11 必须移出批次");
            assertEquals("AVAILABLE", jdbc.queryForObject(
                    "SELECT status FROM archive_page WHERE id = ?", String.class, p2));
        } else {
            assertEquals("SEALED", status, "封存先提交则批次必须已封存");
            assertTrue(p2Member, "封存先提交则 P-C11 保留在批次内");
        }
        assertEquals(0, p2Occupancy, "无论谁先提交，不得既归还又保留进行中占用");
    }
}