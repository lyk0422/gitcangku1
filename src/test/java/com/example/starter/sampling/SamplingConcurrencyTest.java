package com.example.starter.sampling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 采样许可并发与幂等边界测试：真实并发提交，验证计数上限不超、
 * 同键重放只占一键、重叠许可创建串行化、批次并发重放只生效一次。
 */
@SpringBootTest
class SamplingConcurrencyTest {

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant T = Instant.parse("2026-09-15T00:00:00Z");

    @Autowired
    private SamplingService samplingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM sampling_observation_snapshot");
        jdbcTemplate.update("DELETE FROM sampling_rejection");
        jdbcTemplate.update("DELETE FROM sampling_observation");
        jdbcTemplate.update("DELETE FROM sampling_conflict_cluster");
        jdbcTemplate.update("DELETE FROM sampling_merge_batch");
        jdbcTemplate.update("DELETE FROM sampling_permit");
    }

    private <R> List<R> runConcurrently(List<Callable<R>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<R>> synchronizedTasks = tasks.stream()
                .<Callable<R>>map(task -> () -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return task.call();
                })
                .toList();
        try {
            List<Future<R>> futures = synchronizedTasks.stream().map(executor::submit).toList();
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("workers not ready in time");
            }
            start.countDown();
            List<R> results = new ArrayList<>();
            for (Future<R> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private void createPermit(String permitId, String deviceId, int maxObservations) {
        samplingService.createPermit(new CreatePermitRequest(permitId, deviceId,
                0, 10, 0, 10, FROM, TO, maxObservations, 1));
    }

    private SubmitObservationRequest submitRequest(String deviceId, int gridX, int gridY, String content) {
        return new SubmitObservationRequest(deviceId, 1, gridX, gridY, T, content);
    }

    @Test
    void concurrentSubmissionsNeverExceedPermitLimit() throws Exception {
        createPermit("permit-c1", "dev-1", 5);

        // 10 个并发提交竞争 5 个名额：恰好 5 个成功、5 个超限拒绝
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    samplingService.submitObservation(submitRequest("dev-1", index, index, "并发观测" + index));
                    return "created";
                } catch (SamplingException e) {
                    return e.status().value() + ":" + e.reason();
                }
            });
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).filteredOn("created"::equals).hasSize(5);
        assertThat(results).filteredOn("422:PERMIT_LIMIT_EXCEEDED"::equals).hasSize(5);
        Integer usedCount = jdbcTemplate.queryForObject(
                "SELECT used_count FROM sampling_permit WHERE permit_id = 'permit-c1'", Integer.class);
        assertThat(usedCount).isEqualTo(5);
        Integer observations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sampling_observation", Integer.class);
        assertThat(observations).isEqualTo(5);
        Integer rejections = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sampling_rejection WHERE reason = 'PERMIT_LIMIT_EXCEEDED'", Integer.class);
        assertThat(rejections).isEqualTo(5);
    }

    @Test
    void concurrentSameKeySubmissionsYieldSingleObservation() throws Exception {
        createPermit("permit-c2", "dev-1", 10);

        // 同一提交内容并发 8 次：permitKey 相同，全部返回同一结果，只接收一条
        List<Callable<SamplingService.SubmitOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> samplingService.submitObservation(submitRequest("dev-1", 3, 3, "同一并发观测")));
        }
        List<SamplingService.SubmitOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        String permitKey = outcomes.get(0).body().permitKey();
        assertThat(outcomes).allMatch(outcome -> outcome.body().permitKey().equals(permitKey));
        Integer observations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sampling_observation", Integer.class);
        assertThat(observations).isEqualTo(1);
        Integer usedCount = jdbcTemplate.queryForObject(
                "SELECT used_count FROM sampling_permit WHERE permit_id = 'permit-c2'", Integer.class);
        assertThat(usedCount).isEqualTo(1);
    }

    @Test
    void concurrentOverlappingPermitCreatesYieldSinglePermit() throws Exception {
        // 5 个并发创建同设备、网格与时间均相交的许可：恰好一个成功，其余 409
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    samplingService.createPermit(new CreatePermitRequest("permit-cx" + index, "dev-1",
                            0, 10, 0, 10, FROM, TO, 10, 1));
                    return "created";
                } catch (SamplingException e) {
                    return e.status().value() + ":" + e.reason();
                }
            });
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).filteredOn("created"::equals).hasSize(1);
        assertThat(results).filteredOn("409:PERMIT_OVERLAP"::equals).hasSize(4);
        Integer permits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sampling_permit", Integer.class);
        assertThat(permits).isEqualTo(1);
    }

    @Test
    void concurrentSameBatchMergesApplyExactlyOnce() throws Exception {
        createPermit("permit-c4", "dev-1", 10);

        // 同一批次并发提交 6 次：全部返回同一结果，观测与计数只生效一次
        BatchMergeRequest batch = new BatchMergeRequest("batch-c1", List.of(
                submitRequest("dev-1", 1, 1, "批次观测一"),
                submitRequest("dev-1", 2, 2, "批次观测二")));
        List<Callable<SamplingService.BatchMergeOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> samplingService.mergeBatch(batch));
        }
        List<SamplingService.BatchMergeOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        assertThat(outcomes).allMatch(outcome -> outcome.body().receivedCount() == 2);
        Integer batches = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sampling_merge_batch WHERE batch_id = 'batch-c1'", Integer.class);
        assertThat(batches).isEqualTo(1);
        Integer observations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sampling_observation", Integer.class);
        assertThat(observations).isEqualTo(2);
        Integer usedCount = jdbcTemplate.queryForObject(
                "SELECT used_count FROM sampling_permit WHERE permit_id = 'permit-c4'", Integer.class);
        assertThat(usedCount).isEqualTo(2);
    }

    @Test
    void concurrentDistinctBatchesRespectSharedPermitLimit() throws Exception {
        createPermit("permit-c5", "dev-1", 3);

        // 两个并发批次共享同一许可（上限 3）：每批 2 条，最终计数不超上限，恰有一批整体失败
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        samplingService.mergeBatch(new BatchMergeRequest("batch-a", List.of(
                                submitRequest("dev-1", 1, 1, "A1"),
                                submitRequest("dev-1", 2, 2, "A2"))));
                        return "ok";
                    } catch (SamplingException e) {
                        return e.status().value() + ":" + e.reason();
                    }
                },
                () -> {
                    try {
                        samplingService.mergeBatch(new BatchMergeRequest("batch-b", List.of(
                                submitRequest("dev-1", 3, 3, "B1"),
                                submitRequest("dev-1", 4, 4, "B2"))));
                        return "ok";
                    } catch (SamplingException e) {
                        return e.status().value() + ":" + e.reason();
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 按提交顺序串行裁决：一批成功（计数 2），另一批因完整最终计数 4 超限整体 422
        assertThat(results).containsExactlyInAnyOrder("ok", "422:PERMIT_LIMIT_EXCEEDED");
        Integer usedCount = jdbcTemplate.queryForObject(
                "SELECT used_count FROM sampling_permit WHERE permit_id = 'permit-c5'", Integer.class);
        assertThat(usedCount).isEqualTo(2);
        Integer observations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sampling_observation", Integer.class);
        assertThat(observations).isEqualTo(2);
    }
}
