package com.example.starter.kiln;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 * 并发场景测试：并发放行只产生一条记录；末点上传与放行竞争时结果必须自洽。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:kilntest_conc;MODE=MySQL;DB_CLOSE_DELAY=-1")
@Import(TestKilnConfig.class)
class KilnConcurrencyTest {

    @Autowired
    private KilnService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        KilnTestSupport.clean(jdbc);
    }

    private long newBatchWithFullTrajectory() {
        long programId = service.createProgram(KilnTestSupport.standardProgram());
        long batchId = service.createBatch(new KilnDtos.CreateBatchRequest("B-" + programId, programId));
        service.uploadPoints(batchId, KilnTestSupport.upload("up-1", KilnTestSupport.fullTrajectory()));
        return batchId;
    }

    @Test
    @Timeout(30)
    void concurrentReleaseProducesSingleRecord() throws Exception {
        long batchId = newBatchWithFullTrajectory();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String inspector = "qc-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                    KilnDtos.ReleaseResult result = service.release(batchId,
                            new KilnDtos.ReleaseRequest("RELEASE", inspector));
                    if (result.replayed()) {
                        replayed.incrementAndGet();
                    } else {
                        created.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        for (Future<?> future : futures) {
            future.get(20, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertEquals(1, created.get());
        assertEquals(threads - 1, replayed.get());
        Integer records = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kiln_release_record WHERE batch_id = ?", Integer.class, batchId);
        assertEquals(1, records);
    }

    @Test
    @Timeout(60)
    void lastPointUploadRacingReleaseStaysConsistent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            long programId = service.createProgram(KilnTestSupport.standardProgram());
            for (int round = 0; round < 10; round++) {
                long batchId = service.createBatch(
                        new KilnDtos.CreateBatchRequest("RACE-" + round, programId));
                // 轨迹缺终点边界点，最后一个点与放行竞争
                List<KilnDtos.PointInput> points = new ArrayList<>(KilnTestSupport.fullTrajectory());
                KilnDtos.PointInput lastPoint = points.remove(points.size() - 1);
                service.uploadPoints(batchId, KilnTestSupport.upload("up-base", points));

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                Future<KilnDtos.ReleaseResult> releaseFuture = pool.submit(() -> {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    try {
                        return service.release(batchId, new KilnDtos.ReleaseRequest("RELEASE", "qc-race"));
                    } catch (KilnException e) {
                        return null;
                    }
                });
                Future<Boolean> uploadFuture = pool.submit(() -> {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    try {
                        service.uploadPoints(batchId, KilnTestSupport.upload("up-last", List.of(lastPoint)));
                        return true;
                    } catch (KilnException e) {
                        return false;
                    }
                });
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                go.countDown();
                KilnDtos.ReleaseResult release = releaseFuture.get(20, TimeUnit.SECONDS);
                boolean uploaded = uploadFuture.get(20, TimeUnit.SECONDS);

                Integer pointCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM kiln_temp_point WHERE batch_id = ?", Integer.class, batchId);
                if (release != null) {
                    // 放行成功必须包含最后一个点并完成全轨迹校核
                    assertTrue(uploaded, "第 " + round + " 轮：放行成功时末点必须已入库");
                    assertEquals(37, release.pointsLimit());
                    assertEquals(37, pointCount);
                } else {
                    // 放行按旧上限拒绝（缺终点边界），且不得产生任何放行记录
                    assertTrue(pointCount == 36 || pointCount == 37);
                    Integer records = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM kiln_release_record WHERE batch_id = ?",
                            Integer.class, batchId);
                    assertEquals(0, records);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}