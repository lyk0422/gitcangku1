package com.example.starter.aging;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.aging.web.ApiException;
import com.example.starter.aging.web.ApiViews.ReleaseView;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 并发测试：并发放行最多形成一张放行记录；作废与放行竞争按提交时有效记录裁决。
 */
class ConcurrencyReleaseTest extends AbstractAgingTest {

    /** 两个线程同时放行同一批次同一 requestId：都成功且只形成一张放行记录。 */
    @Test
    void concurrentReleasesProduceSingleRecord() throws Exception {
        long batchId = newBatch("CONC-001");
        fillAllDays(batchId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<ReleaseView> task = () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return service.release(batchId, "conc001-rel");
        };
        Future<ReleaseView> first = pool.submit(task);
        Future<ReleaseView> second = pool.submit(task);
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        ReleaseView r1 = first.get(15, TimeUnit.SECONDS);
        ReleaseView r2 = second.get(15, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(r1.releaseId()).isEqualTo(r2.releaseId());
        assertThat(r1.snapshot()).hasSize(7);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_record WHERE batch_id = ?", Integer.class, batchId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot_item WHERE release_id = ?",
                Integer.class, r1.releaseId())).isEqualTo(7);
    }

    /** 作废与放行并发：结果必为两种一致状态之一，不允许部分放行或快照引用作废前不存在的记录。 */
    @Test
    void voidAndReleaseRaceIsConsistent() throws Exception {
        long batchId = newBatch("CONC-002");
        fillAllDays(batchId);
        long tempId = jdbc.queryForObject(
                "SELECT id FROM temperature_record WHERE batch_id = ? AND temp_date = '2026-01-03'",
                Long.class, batchId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> voidTask = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return service.voidTemperature(tempId, "并发作废", null, null, null);
        };
        Callable<Object> releaseTask = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return service.release(batchId, "conc002-rel");
        };
        Future<Object> voidFuture = pool.submit(voidTask);
        Future<Object> releaseFuture = pool.submit(releaseTask);
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        Object voidOutcome = outcomeOf(voidFuture);
        Object releaseOutcome = outcomeOf(releaseFuture);
        pool.shutdownNow();

        int releaseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_record WHERE batch_id = ?", Integer.class, batchId);
        if (releaseCount == 1) {
            // 放行先提交：快照固化作废前的有效记录，作废随后生效但不影响快照
            assertThat(releaseOutcome).isInstanceOf(ReleaseView.class);
            ReleaseView view = (ReleaseView) releaseOutcome;
            assertThat(view.snapshot()).hasSize(7);
            assertThat(view.snapshot().get(2).temperatureId()).isEqualTo(tempId);
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM temperature_record WHERE id = ?", String.class, tempId))
                    .isEqualTo("VOID");
        } else {
            // 作废先提交：放行按作废后的记录裁决，因第 3 天缺有效温度而拒绝
            assertThat(releaseCount).isZero();
            assertThat(voidOutcome).isNotInstanceOf(ApiException.class);
            assertThat(releaseOutcome).isInstanceOfSatisfying(ApiException.class,
                    ex -> assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM aging_batch WHERE id = ?", String.class, batchId))
                    .isEqualTo("AGING");
        }
    }

    private static Object outcomeOf(Future<Object> future) throws Exception {
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            return e.getCause();
        }
    }
}