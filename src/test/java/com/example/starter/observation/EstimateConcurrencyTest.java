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
 * 密度估计并发边界测试：真实并发提交，验证同键并发只占一键、
 * 估计与源观测合并互串行化核对版本、同 estimateId 异参只有一个成功。
 */
@SpringBootTest
class EstimateConcurrencyTest {

    @Autowired
    private ObservationService observationService;

    @Autowired
    private EstimateService estimateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM density_estimate_member");
        jdbcTemplate.update("DELETE FROM density_estimate_layer");
        jdbcTemplate.update("DELETE FROM density_estimate");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
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

    private void createObs(String id, String requestId, int count, int sampleArea) {
        observationService.create(new CreateObservationRequest(requestId, id, "站点A", "1.0", "备注",
                count, sampleArea));
    }

    private EstimateRequest estimateRequest(String requestId, String estimateId, int areaA, int areaB) {
        return new EstimateRequest(requestId, estimateId, "operator-a", List.of(
                new EstimateRequest.LayerRequest(areaA, List.of(
                        new EstimateRequest.MemberRequest("obs-a", 1))),
                new EstimateRequest.LayerRequest(areaB, List.of(
                        new EstimateRequest.MemberRequest("obs-b", 1)))));
    }

    private long estimateRows() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM density_estimate", Long.class);
        return count == null ? 0 : count;
    }

    @Test
    void concurrentSameRequestIdGeneratesSingleEstimate() throws Exception {
        createObs("obs-a", "req-p-c1", 1, 3);
        createObs("obs-b", "req-p-c2", 2, 3);

        // 同一 requestId、相同参数并发 4 次：全部返回同一成果，只生成一条成果与一条去重记录
        List<Callable<EstimateService.EstimateOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> estimateService.create(estimateRequest("req-p1", "est-p1", 100, 200)));
        }
        List<EstimateService.EstimateOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes).allMatch(outcome -> "166.667".equals(outcome.body().total()));
        assertThat(outcomes).allMatch(outcome -> "500/3".equals(outcome.body().totalExact()));
        assertThat(estimateRows()).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-p1'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
        Integer memberRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM density_estimate_member WHERE estimate_id = 'est-p1'", Integer.class);
        assertThat(memberRows).isEqualTo(2);
    }

    @Test
    void concurrentEstimateAndMergeOnSourceSerializeOnVersion() throws Exception {
        createObs("obs-a", "req-q-c1", 2, 4);
        createObs("obs-b", "req-q-c2", 1, 1);

        // 估计（期望 obs-a 当前为 v1）与 obs-a 的合并并发：行锁串行化后必居其一
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        EstimateService.EstimateOutcome outcome = estimateService.create(
                                estimateRequest("req-q1", "est-q1", 50, 10));
                        return "estimate-" + outcome.status();
                    } catch (ApiException e) {
                        return "estimate-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.merge("obs-a", new MergeObservationRequest(
                                "req-q2", 1, "站点A", "1.0", "备注", 5, 4));
                        return "merge-200";
                    } catch (ApiException e) {
                        return "merge-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 合并必然成功（v1 → v2，计数 2 → 5）
        assertThat(results).contains("merge-200");
        ObservationResponse current = observationService.getCurrent("obs-a");
        assertThat(current.version()).isEqualTo(2);
        assertThat(current.count()).isEqualTo(5);

        if (results.contains("estimate-201")) {
            // 估计先拿到行锁：冻结 v1（计数 2），总量 2/4×50 + 1×10 = 35
            EstimateResponse estimate = estimateService.getEstimate("est-q1");
            assertThat(estimate.total()).isEqualTo("35.000");
            EstimateResponse.Layer layer = estimate.layers().stream()
                    .filter(item -> item.totalArea() == 50)
                    .findFirst()
                    .orElseThrow();
            assertThat(layer.members().get(0).version()).isEqualTo(1);
            assertThat(layer.members().get(0).count()).isEqualTo(2);
        } else {
            // 合并先提交：估计看到 v2，期望 v1 不匹配，旧版本 409，不生成成果
            assertThat(results).contains("estimate-409");
            assertThat(estimateRows()).isZero();
        }
    }

    @Test
    void concurrentSameEstimateIdWithDifferentParamsYieldsSingleEstimate() throws Exception {
        createObs("obs-a", "req-r-c1", 1, 3);
        createObs("obs-b", "req-r-c2", 2, 3);

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        estimateService.create(estimateRequest("req-r1", "est-r1", 100, 200));
                        return "first-201";
                    } catch (ApiException e) {
                        return "first-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        estimateService.create(estimateRequest("req-r2", "est-r1", 150, 200));
                        return "second-201";
                    } catch (ApiException e) {
                        return "second-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 同 estimateId 异参并发：恰一个 201，另一个 409，不泄漏唯一键异常
        assertThat(results).hasSize(2);
        assertThat(results.stream().filter(result -> result.endsWith("201")).count()).isEqualTo(1);
        assertThat(results.stream().filter(result -> result.endsWith("409")).count()).isEqualTo(1);
        assertThat(estimateRows()).isEqualTo(1);
    }
}
