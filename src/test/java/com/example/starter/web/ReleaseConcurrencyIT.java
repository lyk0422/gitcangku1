package com.example.starter.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.common.ApiException;
import com.example.starter.reading.ReadingRepository;
import com.example.starter.reading.ReadingService;
import com.example.starter.release.ReleaseRepository;
import com.example.starter.release.ReleaseService;
import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 并发集成测试：并发放行只产生一条记录；最后一个测温点与放行竞争时，
 * 放行要么包含该点并完成全轨迹校核，要么按旧上限明确拒绝。真实 H2 行锁串行化。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:kiln_test_conc;MODE=MySQL;DB_CLOSE_DELAY=-1")
class ReleaseConcurrencyIT extends KilnApiIT {

    @Autowired
    ReleaseService releaseService;
    @Autowired
    ReadingService readingService;
    @Autowired
    ReleaseRepository releaseRepository;
    @Autowired
    ReadingRepository readingRepository;

    @Test
    void concurrentReleasesProduceSingleRecord() throws Exception {
        long programId = createProgram("GLAZE-J", 1);
        long batchId = createBatch("B-CONC-1", programId);
        upload(batchId, "req-c1", fullTrajectory()).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    releaseService.release(batchId, "qc-wang");
                    return "OK";
                } catch (ApiException e) {
                    return String.valueOf(e.status().value());
                }
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> f : futures) {
            results.add(f.get(15, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertEquals(1, results.stream().filter("OK"::equals).count(),
                "并发放行只能成功一次: " + results);
        assertEquals(threads - 1, results.stream().filter("409"::equals).count(),
                "其余放行必须409: " + results);
        assertEquals(1, releaseRepository.countByBatch(batchId));
    }

    @Test
    void lastReadingRacingReleaseIsConsistent() throws Exception {
        long programId = createProgram("GLAZE-K", 1);
        long batchId = createBatch("B-RACE-1", programId);
        int[][] partial = Arrays.copyOf(fullTrajectory(), 8);
        upload(batchId, "req-race-0", partial).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<String> uploadFuture = pool.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                readingService.upload(batchId, "req-race-1", List.of(
                        new ReadingService.ReadingInput(
                                T0.plus(40, ChronoUnit.MINUTES), new BigDecimal("800"))));
                return "UPLOAD_OK";
            } catch (ApiException e) {
                return "UPLOAD_" + e.status().value();
            }
        });
        Future<String> releaseFuture = pool.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                releaseService.release(batchId, "qc-wang");
                return "RELEASE_OK";
            } catch (ApiException e) {
                return "RELEASE_" + e.status().value();
            }
        });
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        String uploadResult = uploadFuture.get(15, TimeUnit.SECONDS);
        String releaseResult = releaseFuture.get(15, TimeUnit.SECONDS);
        pool.shutdownNow();

        if ("RELEASE_OK".equals(releaseResult)) {
            // 放行成功必须已包含最后测温点，不存在放行后出现的未审点
            assertEquals("UPLOAD_OK", uploadResult);
            ReleaseRepository.ReleaseRecord record =
                    releaseRepository.findByBatch(batchId).orElseThrow();
            assertEquals(readingRepository.maxIdByBatch(batchId), record.readingMaxId());
            assertEquals(9, record.readingCount());
        } else {
            // 按旧上限明确拒绝：批次未放行，补录点入库后重新检查通过
            assertEquals("RELEASE_422", releaseResult);
            assertEquals("UPLOAD_OK", uploadResult);
            assertEquals(0, releaseRepository.countByBatch(batchId));
            releaseService.release(batchId, "qc-wang");
            assertEquals(1, releaseRepository.countByBatch(batchId));
        }
    }
}