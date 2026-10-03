package com.example.starter.deacid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发状态测试：并发双入组被唯一约束阻止；调拨与封存并发按事务提交顺序确定归属。
 */
@SpringBootTest
class DeacidConcurrencyTest {

    @Autowired
    private DeacidService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM seal_request");
        jdbc.update("DELETE FROM treatment_record");
        jdbc.update("DELETE FROM page_occupancy");
        jdbc.update("DELETE FROM batch_page");
        jdbc.update("DELETE FROM deacid_batch");
        jdbc.update("DELETE FROM archive_page");
    }

    @Test
    void 并发双入组_唯一约束只允许一个进行中批次() throws Exception {
        service.registerPage("CN-C1");
        Contracts.BatchResponse batchA = service.createBatch("B-CA");
        Contracts.BatchResponse batchB = service.createBatch("B-CB");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<Object> enrollA = () -> runWhen(ready, start,
                    () -> service.enroll(batchA.batchId(), "req-a", "CN-C1"));
            Callable<Object> enrollB = () -> runWhen(ready, start,
                    () -> service.enroll(batchB.batchId(), "req-b", "CN-C1"));
            Future<Object> fa = pool.submit(enrollA);
            Future<Object> fb = pool.submit(enrollB);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            Object ra = fa.get(15, TimeUnit.SECONDS);
            Object rb = fb.get(15, TimeUnit.SECONDS);

            int successes = (ra instanceof Contracts.EnrollResponse ? 1 : 0)
                    + (rb instanceof Contracts.EnrollResponse ? 1 : 0);
            assertEquals(1, successes, "同一页同一时刻只能入组一个进行中批次");
            assertTrue(ra instanceof ApiException || rb instanceof ApiException);
            ApiException failure = (ApiException) (ra instanceof ApiException ? ra : rb);
            assertEquals("PAGE_OCCUPIED", failure.code());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM page_occupancy", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM batch_page WHERE status = 'ACTIVE'", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 调拨与封存并发_按事务提交顺序确定归属() throws Exception {
        Contracts.PageResponse page = service.registerPage("CN-C2");
        Contracts.BatchResponse batch = service.createBatch("B-CC");
        service.enroll(batch.batchId(), "req-e", "CN-C2");
        service.appendTreatment(batch.batchId(), page.pageId(),
                new Contracts.TreatmentRequest(new BigDecimal("6.8"), new BigDecimal("7.0"), "RG-C"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<Object> release = () -> runWhen(ready, start,
                    () -> service.release(batch.batchId(), page.pageId()));
            Callable<Object> seal = () -> runWhen(ready, start,
                    () -> service.seal(batch.batchId(), "req-s"));
            Future<Object> fr = pool.submit(release);
            Future<Object> fs = pool.submit(seal);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            Object releaseResult = fr.get(15, TimeUnit.SECONDS);
            Object sealResult = fs.get(15, TimeUnit.SECONDS);

            String batchStatus = jdbc.queryForObject(
                    "SELECT status FROM deacid_batch WHERE id = ?", String.class, batch.batchId());
            int occupancy = jdbc.queryForObject("SELECT COUNT(*) FROM page_occupancy", Integer.class);
            String pageStatus = jdbc.queryForObject(
                    "SELECT status FROM batch_page WHERE batch_id = ?", String.class, batch.batchId());

            if (sealResult instanceof DeacidService.SealOutcome.Sealed) {
                assertEquals("SEALED", batchStatus);
                assertEquals(1, occupancy, "封存先提交：占用保留，归还必须失败");
                assertEquals("ACTIVE", pageStatus);
                ApiException releaseFailure = assertInstanceOf(ApiException.class, releaseResult);
                assertEquals("BATCH_NOT_OPEN", releaseFailure.code());
            } else {
                assertInstanceOf(Contracts.ReleaseResponse.class, releaseResult);
                assertEquals("OPEN", batchStatus, "归还先提交：批次未封存");
                assertEquals(0, occupancy, "归还后不得保留进行中占用");
                assertEquals("RELEASED", pageStatus);
                DeacidService.SealOutcome.Rejected rejected =
                        assertInstanceOf(DeacidService.SealOutcome.Rejected.class, sealResult);
                assertEquals(List.of("NO_ACTIVE_PAGES"), rejected.problems().get(0).reasons());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Object runWhen(CountDownLatch ready, CountDownLatch start, Callable<Object> action) {
        ready.countDown();
        try {
            start.await(10, TimeUnit.SECONDS);
            return action.call();
        } catch (ApiException e) {
            return e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}