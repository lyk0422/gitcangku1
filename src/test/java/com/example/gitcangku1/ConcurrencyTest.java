package com.example.gitcangku1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.gitcangku1.domain.BatchStatus;
import com.example.gitcangku1.domain.MeasureType;
import com.example.gitcangku1.repo.BatchRepository;
import com.example.gitcangku1.repo.ReleaseRepository;
import com.example.gitcangku1.service.BatchService;
import com.example.gitcangku1.service.ReleaseRejectedException;
import com.example.gitcangku1.service.ReleaseService;
import com.example.gitcangku1.service.Views.ReleaseView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 并发测试：真实线程竞争，CountDownLatch 协调同时起跑，Future 带超时防死锁。 */
@SpringBootTest
class ConcurrencyTest {

    static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");

    @Autowired
    BatchService batchService;
    @Autowired
    ReleaseService releaseService;
    @Autowired
    BatchRepository batches;
    @Autowired
    ReleaseRepository releases;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_errata");
        jdbc.update("DELETE FROM release_item");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM recheck_adjudication");
        jdbc.update("DELETE FROM color_measurement");
        jdbc.update("DELETE FROM dye_batch");
    }

    private long readyBatch(int rolls) {
        long id = batchService.createBatch("B-" + UUID.randomUUID(), 50, 0, 0, rolls).id();
        for (int roll = 1; roll <= rolls; roll++) {
            batchService.recordMeasurement(id, "m-" + UUID.randomUUID(), roll,
                    MeasureType.ORIGINAL, 51, 1, 1, T0);
        }
        return id;
    }

    @Test
    void concurrentReleaseCreatesExactlyOneOrder() throws Exception {
        long id = readyBatch(3);
        int threads = 8;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<ReleaseView>>();
        for (int i = 0; i < threads; i++) {
            String reqId = "rel-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                return releaseService.release(id, reqId, null);
            }));
        }
        start.countDown();
        var ids = new ArrayList<Long>();
        for (var f : futures) {
            ids.add(f.get(30, TimeUnit.SECONDS).releaseId());
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, releases.countOrders(id));
        assertEquals(1, ids.stream().distinct().count());
        assertEquals(BatchStatus.RELEASED, batches.findById(id).orElseThrow().status());
        assertEquals(3, releases.findItems(ids.get(0)).size());
    }

    @Test
    void concurrentSameRequestIdReplayYieldsSingleOrder() throws Exception {
        long id = readyBatch(1);
        int threads = 4;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<ReleaseView>>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return releaseService.release(id, "rel-same", null);
            }));
        }
        start.countDown();
        var ids = new ArrayList<Long>();
        for (var f : futures) {
            ids.add(f.get(30, TimeUnit.SECONDS).releaseId());
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, releases.countOrders(id));
        assertEquals(1, ids.stream().distinct().count());
    }

    @Test
    void adjudicationRacingReleaseReadsConsistentAdoptedSet() throws Exception {
        long id = batchService.createBatch("B-" + UUID.randomUUID(), 50, 0, 0, 1).id();
        batchService.recordMeasurement(id, "m-o", 1, MeasureType.ORIGINAL, 53, 0, 0, T0);
        batchService.recordMeasurement(id, "m-r", 1, MeasureType.RECHECK, 51, 1, 1, T0);

        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Future<?> adjudicate = pool.submit(() -> {
            start.await();
            batchService.adjudicate(id, 1, MeasureType.RECHECK, "qc-wang");
            return null;
        });
        Future<Object> release = pool.submit(() -> {
            start.await();
            try {
                return releaseService.release(id, "rel-race", null);
            } catch (ReleaseRejectedException e) {
                return e;
            }
        });
        start.countDown();
        adjudicate.get(30, TimeUnit.SECONDS);
        Object outcome = release.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        if (outcome instanceof ReleaseView view) {
            // 放行成功：裁决必然已提交，冻结明细必须采纳复检且色差合格
            assertEquals("RECHECK", view.items().get(0).adoptedSource());
            assertTrue(view.items().get(0).deltaE() <= 2.0);
        } else {
            // 放行先读到未裁决状态而拒绝：不得产生放行单，裁决落库后可再次放行
            var rejected = (ReleaseRejectedException) outcome;
            assertEquals(java.util.List.of(1), rejected.rejectedRolls().get("UNDECIDED_RECHECK"));
            assertEquals(0, releases.countOrders(id));
            var retry = releaseService.release(id, "rel-race", null);
            assertEquals("RECHECK", retry.items().get(0).adoptedSource());
        }
        assertEquals(1, releases.countOrders(id));
    }

    @Test
    void concurrentSameMeasurementRequestIdInsertsOnce() throws Exception {
        long id = readyBatch(1);
        int threads = 4;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Long>>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return batchService.recordMeasurement(id, "m-dup", 1,
                        MeasureType.RECHECK, 50, 0, 0, T0).id();
            }));
        }
        start.countDown();
        var ids = new ArrayList<Long>();
        for (var f : futures) {
            ids.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, ids.stream().distinct().count());
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM color_measurement WHERE request_id='m-dup'", Long.class);
        assertEquals(1L, rows);
    }
}