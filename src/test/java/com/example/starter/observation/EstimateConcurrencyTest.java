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
 * 分层密度估计成果并发边界测试：真实并发提交，验证同键并发只占一键、
 * 同成果标识并发只有一个成功、成果生成与观测合并基于行锁协调源版本。
 */
@SpringBootTest
class EstimateConcurrencyTest {

    @Autowired
    private EstimateService estimateService;

    @Autowired
    private ObservationService observationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM density_estimate_member");
        jdbcTemplate.update("DELETE FROM density_estimate_layer");
        jdbcTemplate.update("DELETE FROM density_estimate");
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

    private void createObs(String requestId, String obsId, int count, int area) {
        observationService.create(new CreateObservationRequest(
                requestId, obsId, "样方", "1.0", "备注", count, area));
    }

    private CreateEstimateRequest estimateRequest(String requestId, String estimateId) {
        return new CreateEstimateRequest(requestId, estimateId, "operator-a", List.of(
                new EstimateLayerRequest(100, List.of(new EstimateMemberRequest("obs-1", 1))),
                new EstimateLayerRequest(200, List.of(new EstimateMemberRequest("obs-2", 1)))));
    }

    private int estimateRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM density_estimate", Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    void concurrentSameRequestIdReplaysSingleResult() throws Exception {
        createObs("req-o1", "obs-1", 1, 3);
        createObs("req-o2", "obs-2", 2, 3);

        // 同一 requestId、相同参数并发 4 次：全部返回同一结果，只生成一个成果
        List<Callable<EstimateService.EstimateOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> estimateService.create(estimateRequest("req-same", "est-c1")));
        }
        List<EstimateService.EstimateOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes).allMatch(outcome -> "est-c1".equals(outcome.body().estimateId()));
        assertThat(outcomes).allMatch(outcome -> "500/3".equals(outcome.body().resultExact()));
        assertThat(estimateRows()).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-same'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentSameEstimateIdDifferentRequestIdsYieldSingleEstimate() throws Exception {
        createObs("req-o1", "obs-1", 1, 3);
        createObs("req-o2", "obs-2", 2, 3);

        // 不同 requestId、同一 estimateId 并发 3 次：恰好一个成功，其余 409，不泄漏唯一键异常
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final String requestId = "req-d" + i;
            tasks.add(() -> {
                try {
                    estimateService.create(estimateRequest(requestId, "est-c2"));
                    return "ok";
                } catch (ApiException e) {
                    return String.valueOf(e.status().value());
                }
            });
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).filteredOn("ok"::equals).hasSize(1);
        assertThat(results).filteredOn("409"::equals).hasSize(2);
        assertThat(estimateRows()).isEqualTo(1);
        // 失败方事务回滚不占键：只有成功方的 requestId 落库
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id IN ('req-d0','req-d1','req-d2')",
                Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentEstimateAndMergeCoordinateOnSourceVersion() throws Exception {
        createObs("req-o1", "obs-1", 1, 3);
        createObs("req-o2", "obs-2", 2, 3);

        // 成果生成引用 obs-1@v1，与推进 obs-1 到 v2 的合并并发：行锁串行化后二者必居其一
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        estimateService.create(estimateRequest("req-e1", "est-c3"));
                        return "estimate-201";
                    } catch (ApiException e) {
                        return "estimate-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.merge("obs-1", new MergeObservationRequest(
                                "req-m1", 1, "样方", "1.0", "备注", 5, 3));
                        return "merge-200";
                    } catch (ApiException e) {
                        return "merge-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 合并不受成果影响，始终成功；成果要么先生成（冻结 v1），要么因版本前进 409
        assertThat(results).contains("merge-200");
        assertThat(results).anyMatch(r -> r.equals("estimate-201") || r.equals("estimate-409"));
        ObservationResponse current = observationService.getCurrent("obs-1");
        assertThat(current.version()).isEqualTo(2);
        assertThat(current.individualCount()).isEqualTo(5);
        if (results.contains("estimate-201")) {
            // 成果先于合并提交：冻结的是 v1 的计数 1，后续合并不倒改成果
            assertThat(estimateRows()).isEqualTo(1);
            EstimateResponse frozen = estimateService.get("est-c3");
            assertThat(frozen.layers().get(0).members().get(0).version()).isEqualTo(1);
            assertThat(frozen.layers().get(0).members().get(0).individualCount()).isEqualTo(1);
            assertThat(frozen.resultExact()).isEqualTo("500/3");
        } else {
            // 合并先提交：成果因源版本不一致 409，整次不生成
            assertThat(estimateRows()).isZero();
        }
    }
}
