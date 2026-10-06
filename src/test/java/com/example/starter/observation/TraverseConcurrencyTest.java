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
 * 闭合导线成果生成的并发与幂等边界测试：真实并发提交，验证同键重放只占一键、
 * 成果标识竞争不泄露唯一键异常、与并发合并竞争时按行锁串行化核对当前版本。
 */
@SpringBootTest
class TraverseConcurrencyTest {

    @Autowired
    private TraverseService traverseService;

    @Autowired
    private ObservationService observationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM traverse_result_edge");
        jdbcTemplate.update("DELETE FROM traverse_result");
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

    private void setupTriangle(String tag) {
        observationService.create(new CreateObservationRequest(
                "req-" + tag + "-c1", "obs-" + tag + "1", "站点1", "1.0", "备注", "1", "0", "1"));
        observationService.create(new CreateObservationRequest(
                "req-" + tag + "-c2", "obs-" + tag + "2", "站点2", "1.0", "备注", "0", "1", "1"));
        observationService.create(new CreateObservationRequest(
                "req-" + tag + "-c3", "obs-" + tag + "3", "站点3", "1.0", "备注", "-0.94", "-1", "1"));
    }

    private GenerateTraverseRequest traverseRequest(String requestId, String resultId, String tag) {
        return new GenerateTraverseRequest(requestId, resultId, "surveyor-a", List.of(
                new TraverseEdgeRef("obs-" + tag + "1", 1),
                new TraverseEdgeRef("obs-" + tag + "2", 1),
                new TraverseEdgeRef("obs-" + tag + "3", 1)));
    }

    private int traverseCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM traverse_result", Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    void concurrentSameRequestIdReplaysSingleResult() throws Exception {
        setupTriangle("tc1");

        // 同一 requestId、相同参数并发 3 次：全部返回同一成果，只占一键、只生成一个成果
        List<Callable<TraverseService.TraverseOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> traverseService.generate(traverseRequest("req-tc1-g", "trv-tc1", "tc1")));
        }
        List<TraverseService.TraverseOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes).allMatch(outcome -> "trv-tc1".equals(outcome.body().resultId()));
        assertThat(outcomes).allMatch(outcome -> "0.06".equals(outcome.body().closureDx()));
        assertThat(traverseCount()).isEqualTo(1);
        Integer edgeRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM traverse_result_edge WHERE result_id = 'trv-tc1'", Integer.class);
        assertThat(edgeRows).isEqualTo(3);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-tc1-g'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentSameResultIdYieldsSingleResult() throws Exception {
        setupTriangle("tc2");

        // 不同 requestId、同一 resultId 并发：恰好一个成功，另一个 409，不泄露唯一键异常
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        traverseService.generate(traverseRequest("req-tc2-a", "trv-tc2", "tc2"));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        traverseService.generate(traverseRequest("req-tc2-b", "trv-tc2", "tc2"));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok", "409");
        assertThat(traverseCount()).isEqualTo(1);
        // 失败方回滚不占 requestId；成功方落库一条
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id IN ('req-tc2-a','req-tc2-b')",
                Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentGenerateAndMergeSerializeOnCurrentVersion() throws Exception {
        setupTriangle("tc3");

        // 成果生成与针对被引用观测的合并并发：行锁串行化后，生成要么先核对通过（201），
        // 要么在合并提交后发现版本非当前（409）；合并始终成功
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        traverseService.generate(traverseRequest("req-tc3-g", "trv-tc3", "tc3"));
                        return "generate-201";
                    } catch (ApiException e) {
                        return "generate-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.merge("obs-tc31", new MergeObservationRequest(
                                "req-tc3-m", 1, "站点1", "1.0", "并发备注", "1", "0", "1"));
                        return "merge-ok";
                    } catch (ApiException e) {
                        return "merge-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).contains("merge-ok");
        assertThat(results).anyMatch(r -> r.equals("generate-201") || r.equals("generate-409"));
        ObservationResponse current = observationService.getCurrent("obs-tc31");
        assertThat(current.version()).isEqualTo(2);
        assertThat(current.note()).isEqualTo("并发备注");
        if (results.contains("generate-201")) {
            // 生成先完成：成果冻结在版本 1，后续合并不倒改成果
            assertThat(traverseCount()).isEqualTo(1);
            Integer frozenVersion = jdbcTemplate.queryForObject(
                    "SELECT observation_version FROM traverse_result_edge "
                            + "WHERE result_id = 'trv-tc3' AND observation_id = 'obs-tc31'", Integer.class);
            assertThat(frozenVersion).isEqualTo(1);
        } else {
            // 合并先提交：版本非当前，生成 409 且不产生任何成果
            assertThat(results).contains("generate-409");
            assertThat(traverseCount()).isZero();
        }
    }
}
