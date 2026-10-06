package com.example.starter.observation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * 观测点并发与幂等边界测试：真实并发提交（真实 H2 内存库），验证确认与合并、删除在行锁下
 * 原子核对两份完整版本、同键并发重放只占一键且返回首次结果、并发合并整组冲突不产生部分拼装。
 */
@SpringBootTest
class PositionedObservationConcurrencyTest {

    @Autowired
    private PositionedObservationService positionedObservationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM observation_confirmation");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM positioned_observation_version");
        jdbcTemplate.update("DELETE FROM positioned_observation_current");
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<T>> synchronizedTasks = tasks.stream()
                .<Callable<T>>map(task -> () -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return task.call();
                })
                .toList();
        try {
            List<Future<T>> futures = synchronizedTasks.stream().map(executor::submit).toList();
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("workers not ready in time");
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private void createPoint(String id, String requestId, String x, String y) {
        positionedObservationService.create(new CreatePositionedObservationRequest(
                requestId, id, x, y, "1.0", "0", "1.0", "op-setup"));
    }

    private int confirmationCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_confirmation", Integer.class);
        return count == null ? 0 : count;
    }

    private int requestLogCount(String requestId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        return count == null ? 0 : count;
    }

    @Test
    void concurrentConfirmAndMergeAreAtomic() throws Exception {
        createPoint("pc-1", "req-pc1", "0", "0");
        createPoint("pc-2", "req-pc2", "4", "0");

        // 确认期望版本 1；并发合并把 pc-1 移到新版本：两者在行锁下串行，必居其一的顺序
        List<Callable<Object>> tasks = List.of(
                () -> {
                    try {
                        return positionedObservationService.confirm(new ConfirmConsistencyRequest(
                                "req-pc3", "pc-1", 1, "pc-2", 1, "op-conf"));
                    } catch (ApiException e) {
                        return e;
                    }
                },
                () -> positionedObservationService.merge("pc-1", new MergePositionedObservationRequest(
                        "req-pc4", 1, "10", "0", "1.0", "0", "1.0", "op-merge")));
        List<Object> outcomes = runConcurrently(tasks);

        PositionedObservationService.PositionedWriteOutcome merge =
                (PositionedObservationService.PositionedWriteOutcome) outcomes.get(1);

        // 合并总是成功（基于 v1 的合法改动）
        assertThat(merge.status()).isEqualTo(200);
        assertThat(merge.body().version()).isEqualTo(2);
        // 确认要么先于合并成功（冻结版本 1），要么因版本过旧 409；绝不出现 500 或半提交
        Object confirmResult = outcomes.get(0);
        if (confirmResult instanceof PositionedObservationService.ConfirmOutcome confirm) {
            assertThat(confirm.status()).isEqualTo(201);
            assertThat(confirm.body().firstVersion()).isEqualTo(1);
            assertThat(confirm.body().first().x()).isEqualTo("0.000");
            assertThat(confirmationCount()).isEqualTo(1);
            // 冻结值不随后续合并改变
            ConfirmationRecord stored = positionedObservationService.getConfirmation(
                    confirm.body().confirmationId());
            assertThat(stored.first().x().toPlainString()).isEqualTo("0.000");
            assertThat(stored.firstVersion()).isEqualTo(1);
        } else {
            assertThat(((ApiException) confirmResult).status().value()).isEqualTo(409);
            assertThat(confirmationCount()).isZero();
        }
        // 最终当前版本为合并后的版本 2
        assertThat(positionedObservationService.getCurrent("pc-1").version()).isEqualTo(2);
    }

    @Test
    void concurrentSameRequestIdConfirmReplaysSingleResult() throws Exception {
        createPoint("pc-3", "req-pr1", "0", "0");
        createPoint("pc-4", "req-pr2", "4", "0");

        // 同一 requestId、同样参数并发提交：都由主键串行化，返回同一首次结果
        List<Callable<PositionedObservationService.ConfirmOutcome>> tasks = List.of(
                () -> positionedObservationService.confirm(new ConfirmConsistencyRequest(
                        "req-pr3", "pc-3", 1, "pc-4", 1, "op-conf")),
                () -> positionedObservationService.confirm(new ConfirmConsistencyRequest(
                        "req-pr3", "pc-3", 1, "pc-4", 1, "op-conf")));
        List<PositionedObservationService.ConfirmOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes.get(0).body().confirmationId())
                .isEqualTo(outcomes.get(1).body().confirmationId());
        assertThat(confirmationCount()).isEqualTo(1);
        assertThat(requestLogCount("req-pr3")).isEqualTo(1);
    }

    @Test
    void concurrentConfirmsWithDifferentParamsConflict() throws Exception {
        createPoint("pc-5", "req-pd1", "0", "0");
        createPoint("pc-6", "req-pd2", "4", "0");

        // 同一 requestId、不同参数并发：一个成功，另一个 409，不报唯一键 500
        List<Callable<Integer>> tasks = List.of(
                () -> {
                    try {
                        return positionedObservationService.confirm(new ConfirmConsistencyRequest(
                                "req-pd3", "pc-5", 1, "pc-6", 1, "op-a")).status();
                    } catch (ApiException e) {
                        return e.status().value();
                    }
                },
                () -> {
                    try {
                        return positionedObservationService.confirm(new ConfirmConsistencyRequest(
                                "req-pd3", "pc-5", 1, "pc-6", 1, "op-b")).status();
                    } catch (ApiException e) {
                        return e.status().value();
                    }
                });
        List<Integer> statuses = runConcurrently(tasks);

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(confirmationCount()).isEqualTo(1);
        assertThat(requestLogCount("req-pd3")).isEqualTo(1);
    }

    @Test
    void concurrentMergesChangingGroupDifferentlyConflictAsWhole() throws Exception {
        createPoint("pc-7", "req-pm1", "0", "0");

        // 两个离线端都基于 v1：一个只改 x，一个只改 a；字段组整体冲突，后到者 409，不逐字段拼装
        List<Callable<Integer>> tasks = List.of(
                () -> {
                    try {
                        return positionedObservationService.merge("pc-7",
                                new MergePositionedObservationRequest(
                                        "req-pm2", 1, "5", "0", "1.0", "0", "1.0", "op-x")).status();
                    } catch (ApiException e) {
                        return e.status().value();
                    }
                },
                () -> {
                    try {
                        return positionedObservationService.merge("pc-7",
                                new MergePositionedObservationRequest(
                                        "req-pm3", 1, "0", "0", "2.0", "0", "1.0", "op-a")).status();
                    } catch (ApiException e) {
                        return e.status().value();
                    }
                });
        List<Integer> statuses = runConcurrently(tasks);

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        PositionedObservationResponse current = positionedObservationService.getCurrent("pc-7");
        // 只有先到者的整组改动生效，版本只前进一次，另一方的改动未被拼装进来
        assertThat(current.version()).isEqualTo(2);
        if ("5.000".equals(current.x())) {
            assertThat(current.a()).isEqualTo("1.000");
        } else {
            assertThat(current.x()).isEqualTo("0.000");
            assertThat(current.a()).isEqualTo("2.000");
        }
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM positioned_observation_version WHERE observation_id = 'pc-7'",
                Integer.class);
        assertThat(versionRows).isEqualTo(2);
    }

    @Test
    void concurrentConfirmAndDeleteNeverConfirmsTombstone() throws Exception {
        createPoint("pc-8", "req-px1", "0", "0");
        createPoint("pc-9", "req-px2", "4", "0");

        // 确认与墓碑删除竞争同一观测点：串行后确认要么先于删除成功，要么遇到墓碑 422
        List<Callable<Integer>> tasks = List.of(
                () -> {
                    try {
                        return positionedObservationService.confirm(new ConfirmConsistencyRequest(
                                "req-px3", "pc-8", 1, "pc-9", 1, "op-conf")).status();
                    } catch (ApiException e) {
                        return e.status().value();
                    }
                },
                () -> {
                    try {
                        return positionedObservationService.delete("pc-9",
                                new DeletePositionedObservationRequest("req-px4", 1, "op-del")).status();
                    } catch (ApiException e) {
                        return e.status().value();
                    }
                });
        List<Integer> statuses = runConcurrently(tasks);

        assertThat(statuses.get(1)).isEqualTo(200);
        assertThat(statuses.get(0)).isIn(201, 422);
        PositionedObservationResponse deleted = positionedObservationService.getCurrent("pc-9");
        assertThat(deleted.deleted()).isTrue();
        if (statuses.get(0) == 201) {
            assertThat(confirmationCount()).isEqualTo(1);
        } else {
            assertThat(confirmationCount()).isZero();
        }
    }
}
