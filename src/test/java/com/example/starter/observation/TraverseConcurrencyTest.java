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
 * 与删除/合并并发时基于行锁串行化核对引用版本，不泄露唯一键异常。
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
        jdbcTemplate.update("DELETE FROM traverse_leg");
        jdbcTemplate.update("DELETE FROM traverse_result");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
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

    private void createWithDisplacement(String id, String requestId, String dx, String dy, String w) {
        observationService.create(new CreateObservationRequest(
                requestId, id, "站点" + id, "1.0", "备注", dx, dy, w));
    }

    private void createSyntheticObservations(String tag) {
        createWithDisplacement("obs-" + tag + "-1", "req-" + tag + "-s1", "1", "0", "100");
        createWithDisplacement("obs-" + tag + "-2", "req-" + tag + "-s2", "0", "1", "200");
        createWithDisplacement("obs-" + tag + "-3", "req-" + tag + "-s3", "-0.94", "-1", "300");
    }

    private GenerateTraverseRequest traverseRequest(String requestId, String tag) {
        return new GenerateTraverseRequest(requestId, "surveyor-a", List.of(
                new TraverseLegRef("obs-" + tag + "-1", 1),
                new TraverseLegRef("obs-" + tag + "-2", 1),
                new TraverseLegRef("obs-" + tag + "-3", 1)));
    }

    @Test
    void concurrentSameKeySameParamsGeneratesOnlyOnce() throws Exception {
        createSyntheticObservations("c1");

        List<Callable<TraverseService.TraverseOutcome>> tasks = List.of(
                () -> traverseService.generate(traverseRequest("req-cc1", "c1")),
                () -> traverseService.generate(traverseRequest("req-cc1", "c1")));
        List<TraverseService.TraverseOutcome> outcomes = runConcurrently(tasks);

        // 同键同参并发：都拿到首次完整响应（同成果标识），去重记录与成果各占一行
        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes.get(0).body().traverseId()).isEqualTo(outcomes.get(1).body().traverseId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-cc1'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM traverse_result", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM traverse_leg", Integer.class)).isEqualTo(3);
    }

    @Test
    void concurrentDistinctKeysBothGenerate() throws Exception {
        createSyntheticObservations("c2");

        List<Callable<TraverseService.TraverseOutcome>> tasks = List.of(
                () -> traverseService.generate(traverseRequest("req-cc2a", "c2")),
                () -> traverseService.generate(traverseRequest("req-cc2b", "c2")));
        List<TraverseService.TraverseOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes.get(0).body().traverseId()).isNotEqualTo(outcomes.get(1).body().traverseId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM traverse_result", Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentGenerateAndDeleteSerializeOnRowLocks() throws Exception {
        createSyntheticObservations("c3");

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        traverseService.generate(traverseRequest("req-cc3-g", "c3"));
                        return "generate-201";
                    } catch (ApiException e) {
                        return "generate-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.delete("obs-c3-1", new DeleteObservationRequest("req-cc3-d", 1));
                        return "delete-ok";
                    } catch (ApiException e) {
                        return "delete-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 删除只依赖当前版本（生成不改观测版本），始终成功；
        // 生成与删除串行化：先于删除则 201，后于删除则看到墓碑 422
        assertThat(results).contains("delete-ok");
        assertThat(results).anyMatch(r -> r.equals("generate-201") || r.equals("generate-422"));
        ObservationResponse current = observationService.getCurrent("obs-c3-1");
        assertThat(current.deleted()).isTrue();
        // 生成成功则成果完整落库；失败则不留任何成果与去重记录
        if (results.contains("generate-422")) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM traverse_result", Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-cc3-g'", Integer.class)).isZero();
        } else {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM traverse_result", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void concurrentGenerateAndMergeRechecksCurrentVersion() throws Exception {
        createSyntheticObservations("c4");

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        traverseService.generate(traverseRequest("req-cc4-g", "c4"));
                        return "generate-201";
                    } catch (ApiException e) {
                        return "generate-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.merge("obs-c4-1", new MergeObservationRequest(
                                "req-cc4-m", 1, "站点obs-c4-1", "1.0", "并发备注", "1", "0", "100"));
                        return "merge-ok";
                    } catch (ApiException e) {
                        return "merge-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 合并始终成功（生成不变更观测）；生成串行化核对：先于合并则 201，后于合并则版本非当前 409
        assertThat(results).contains("merge-ok");
        assertThat(results).anyMatch(r -> r.equals("generate-201") || r.equals("generate-409"));
        ObservationResponse current = observationService.getCurrent("obs-c4-1");
        assertThat(current.version()).isEqualTo(2);
        if (results.contains("generate-409")) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM traverse_result", Integer.class)).isZero();
        } else {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM traverse_result", Integer.class)).isEqualTo(1);
        }
    }
}
