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
 * 并发与幂等边界测试：真实并发提交，验证基于最新版本重新判定、
 * 墓碑不被复活、同键并发重放只占一键。
 */
@SpringBootTest
class ObservationConcurrencyTest {

    @Autowired
    private ObservationService observationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM quality_flag_review");
        jdbcTemplate.update("DELETE FROM quality_flag");
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

    private void createObservation(String id, String requestId) {
        observationService.create(new CreateObservationRequest(requestId, id, "站点A", "1.0", "初始备注"));
    }

    @Test
    void concurrentMergesOnDifferentFieldsBothApply() throws Exception {
        createObservation("obs-c1", "req-cc0");

        // 两个离线端都基于版本 1：一个改地点，一个改备注；互不冲突，应都生效
        List<Callable<ObservationService.WriteOutcome>> tasks = List.of(
                () -> observationService.merge("obs-c1",
                        new MergeObservationRequest("req-cc1", 1, "站点B", "1.0", "初始备注")),
                () -> observationService.merge("obs-c1",
                        new MergeObservationRequest("req-cc2", 1, "站点A", "1.0", "并发备注")));
        List<ObservationService.WriteOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        ObservationResponse current = observationService.getCurrent("obs-c1");
        // 后到者基于提交时的最新版本重新判定：两边修改都应保留，版本只加两次
        assertThat(current.version()).isEqualTo(3);
        assertThat(current.location()).isEqualTo("站点B");
        assertThat(current.note()).isEqualTo("并发备注");
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-c1'", Integer.class);
        assertThat(versionRows).isEqualTo(3);
    }

    @Test
    void concurrentMergeAndDeleteNeverResurrectsTombstone() throws Exception {
        createObservation("obs-c2", "req-cd0");

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        observationService.merge("obs-c2",
                                new MergeObservationRequest("req-cd1", 1, "站点B", "2.0", "合并备注"));
                        return "merge-ok";
                    } catch (ApiException e) {
                        return "merge-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.delete("obs-c2", new DeleteObservationRequest("req-cd2", 1));
                        return "delete-ok";
                    } catch (ApiException e) {
                        return "delete-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 串行化后恰好一个成功：先删则合并 410，先合并则删除 409（版本不匹配）
        assertThat(results).containsExactlyInAnyOrder(
                results.contains("merge-ok") ? "delete-409" : "merge-410",
                results.contains("merge-ok") ? "merge-ok" : "delete-ok");

        ObservationResponse current = observationService.getCurrent("obs-c2");
        assertThat(current.version()).isEqualTo(2);
        if (current.deleted()) {
            // 删除先生效：墓碑不得被并发合并复活
            assertThat(current.location()).isNull();
            ObservationResponse tombstone = observationService.getVersion("obs-c2", 2);
            assertThat(tombstone.deleted()).isTrue();
        } else {
            // 合并先生效：内容为新值，删除因版本不匹配被拒绝
            assertThat(current.location()).isEqualTo("站点B");
        }
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-c2'", Integer.class);
        assertThat(versionRows).isEqualTo(2);
    }

    @Test
    void concurrentSameRequestIdReplaysSingleResult() throws Exception {
        createObservation("obs-c3", "req-ci0");

        // 同一 requestId、相同参数并发提交 4 次：全部返回同一结果，只产生一个新版本
        List<Callable<ObservationService.WriteOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> observationService.merge("obs-c3",
                    new MergeObservationRequest("req-ci1", 1, "站点B", "2.0", "并发重放")));
        }
        List<ObservationService.WriteOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        assertThat(outcomes).allMatch(outcome -> outcome.body().version() == 2);
        ObservationResponse current = observationService.getCurrent("obs-c3");
        assertThat(current.version()).isEqualTo(2);
        assertThat(current.location()).isEqualTo("站点B");
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-ci1'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-c3'", Integer.class);
        assertThat(versionRows).isEqualTo(2);
    }

    @Test
    void concurrentCreatesOnSameObservationIdYieldSingleRecord() throws Exception {
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    observationService.create(new CreateObservationRequest(
                            "req-ck" + index, "obs-c4", "站点" + index, "1.0", "备注"));
                    return "ok";
                } catch (ApiException e) {
                    return String.valueOf(e.status().value());
                }
            });
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).filteredOn("ok"::equals).hasSize(1);
        assertThat(results).filteredOn("409"::equals).hasSize(2);
        ObservationResponse current = observationService.getCurrent("obs-c4");
        assertThat(current.version()).isEqualTo(1);
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-c4'", Integer.class);
        assertThat(versionRows).isEqualTo(1);
    }
}
