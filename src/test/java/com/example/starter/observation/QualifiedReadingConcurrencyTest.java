package com.example.starter.observation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 带限定符读数的并发边界测试：真实并发提交，验证拆分变更竞争只产生提交过的完整组合、
 * 同键并发重放只占一键、解决与删除竞争不复活墓碑。
 */
@SpringBootTest
class QualifiedReadingConcurrencyTest {

    @Autowired
    private ObservationService observationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
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

    @Test
    void concurrentSplitChangesYieldSingleSubmittedGroup() throws Exception {
        observationService.create(new CreateObservationRequest(
                "req-qc0", "obs-q1", "站点A", "5", "EXACT", "备注"));

        // 并发：一端改数值 EXACT 6，另一端改限定符 LT 5，都基于 v1
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        observationService.merge("obs-q1", new MergeObservationRequest(
                                "req-qc1", 1, "站点A", "6", "EXACT", "备注"));
                        return "value-ok";
                    } catch (ApiException e) {
                        return "value-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.merge("obs-q1", new MergeObservationRequest(
                                "req-qc2", 1, "站点A", "5", "LT", "备注"));
                        return "qualifier-ok";
                    } catch (ApiException e) {
                        return "qualifier-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 串行化后恰好一个成功；后到者面对新当前版本构成拆分变更，422
        assertThat(results).containsExactlyInAnyOrder(
                results.contains("value-ok") ? "qualifier-422" : "value-422",
                results.contains("value-ok") ? "value-ok" : "qualifier-ok");

        // 最终内容必是某一侧提交过的完整组合，绝不拼成 LT 6
        ObservationResponse current = observationService.getCurrent("obs-q1");
        assertThat(current.version()).isEqualTo(2);
        if (results.contains("value-ok")) {
            assertThat(current.reading()).isEqualTo("6");
            assertThat(current.readingQualifier()).isEqualTo("EXACT");
        } else {
            assertThat(current.reading()).isEqualTo("5");
            assertThat(current.readingQualifier()).isEqualTo("LT");
        }
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-q1'", Integer.class);
        assertThat(versionRows).isEqualTo(2);
    }

    @Test
    void concurrentSameRequestIdWithQualifierReplaysSingleResult() throws Exception {
        observationService.create(new CreateObservationRequest(
                "req-qd0", "obs-q2", "站点A", "5", "EXACT", "备注"));

        // 同一 requestId、相同带限定符参数并发 4 次：全部返回同一结果，只占一键、只加一个版本
        List<Callable<ObservationService.WriteOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> observationService.merge("obs-q2",
                    new MergeObservationRequest("req-qd1", 1, "站点A", "0.5", "GT", "并发重放")));
        }
        List<ObservationService.WriteOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        assertThat(outcomes).allMatch(outcome -> outcome.body().version() == 2);
        assertThat(outcomes).allMatch(outcome -> "GT".equals(outcome.body().readingQualifier()));
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-qd1'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-q2'", Integer.class);
        assertThat(versionRows).isEqualTo(2);
    }

    @Test
    void concurrentResolveAndDeleteWithQualifierNeverResurrects() throws Exception {
        observationService.create(new CreateObservationRequest(
                "req-qe0", "obs-q3", "站点A", "5", "EXACT", "备注"));
        observationService.merge("obs-q3", new MergeObservationRequest(
                "req-qe1", 1, "站点A", "6", "EXACT", "备注"));
        // 离线端 LT 5 构成拆分变更（422），随后并发：裁决选本地 vs 删除
        try {
            observationService.merge("obs-q3", new MergeObservationRequest(
                    "req-qe2", 1, "站点A", "5", "LT", "备注"));
            throw new IllegalStateException("expected 422");
        } catch (ApiException e) {
            assertThat(e.status().value()).isEqualTo(422);
        }

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        observationService.resolveConflict("obs-q3", new ResolveConflictRequest(
                                "req-qe3", "res-q3", 1, 2, "站点A", "5", "LT", "备注",
                                Map.of("reading", "CANDIDATE"), "operator-a"));
                        return "resolve-ok";
                    } catch (ApiException e) {
                        return "resolve-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.delete("obs-q3", new DeleteObservationRequest("req-qe4", 2));
                        return "delete-ok";
                    } catch (ApiException e) {
                        return "delete-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        ObservationResponse current = observationService.getCurrent("obs-q3");
        if (results.contains("resolve-ok")) {
            // 解决先生效：整组 LT 5，删除因版本不匹配失败
            assertThat(results).containsExactlyInAnyOrder("resolve-ok", "delete-409");
            assertThat(current.deleted()).isFalse();
            assertThat(current.version()).isEqualTo(3);
            assertThat(current.reading()).isEqualTo("5");
            assertThat(current.readingQualifier()).isEqualTo("LT");
        } else {
            // 删除先生效：墓碑不得被并发解决复活
            assertThat(results).containsExactlyInAnyOrder("delete-ok", "resolve-410");
            assertThat(current.deleted()).isTrue();
            assertThat(current.version()).isEqualTo(3);
            assertThat(current.reading()).isNull();
            assertThat(current.readingQualifier()).isNull();
        }
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-q3'", Integer.class);
        assertThat(versionRows).isEqualTo(3);
    }
}