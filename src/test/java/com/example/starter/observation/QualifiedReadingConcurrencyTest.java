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
 * 带限定符读数的并发边界测试：真实并发提交，验证读数组（限定符+数值）在并发下
 * 始终整体落库为某一侧提交过的完整组合，绝不拼成无人提交过的组合；
 * 裁决与合并、删除竞争沿用现有版本边界，失败不新增历史版本。
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

    /**
     * 题干合成场景：基线 v1 = EXACT 5，服务端前进到 v2 = EXACT 6。
     */
    private void setupBaselineExact5ServerExact6(String observationId, String tag) {
        observationService.create(new CreateObservationRequest(
                "req-" + tag + "-create", observationId, "站点A", "5", "EXACT", "备注"));
        observationService.merge(observationId, new MergeObservationRequest(
                "req-" + tag + "-server", 1, "站点A", "6", "EXACT", "备注"));
    }

    @Test
    void concurrentQualifierMergeAndValueMergeYieldSingleCompleteGroup() throws Exception {
        // 基线 v1 = EXACT 5；两个离线端都基于 v1：一个改限定符为 LT 5，一个改数值为 EXACT 7。
        // 先提交者成功；后到者基于最新当前版本重判，构成“一侧改限定符、另一侧改数值”，必须 422。
        observationService.create(new CreateObservationRequest(
                "req-qc1-create", "obs-qc1", "站点A", "5", "EXACT", "备注"));
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        observationService.merge("obs-qc1", new MergeObservationRequest(
                                "req-qc1-lt", 1, "站点A", "5", "LT", "备注"));
                        return "lt-ok";
                    } catch (ApiException e) {
                        return "lt-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.merge("obs-qc1", new MergeObservationRequest(
                                "req-qc1-exact7", 1, "站点A", "7", "EXACT", "备注"));
                        return "exact7-ok";
                    } catch (ApiException e) {
                        return "exact7-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        ObservationResponse current = observationService.getCurrent("obs-qc1");
        if (results.contains("lt-ok")) {
            assertThat(results).containsExactlyInAnyOrder("lt-ok", "exact7-422");
            assertThat(current.readingQualifier()).isEqualTo("LT");
            assertThat(current.reading()).isEqualTo("5");
        } else {
            assertThat(results).containsExactlyInAnyOrder("exact7-ok", "lt-422");
            assertThat(current.readingQualifier()).isEqualTo("EXACT");
            assertThat(current.reading()).isEqualTo("7");
        }
        // 最终版本只有一个前进：失败方不新增历史版本
        assertThat(current.version()).isEqualTo(2);
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-qc1'", Integer.class);
        assertThat(versionRows).isEqualTo(2);
    }

    @Test
    void concurrentResolvesPickCompleteSidesOnly() throws Exception {
        setupBaselineExact5ServerExact6("obs-qc2", "qc2");
        // 本地基于 v1 提交 LT 5：422 冲突
        try {
            observationService.merge("obs-qc2", new MergeObservationRequest(
                    "req-qc2-offline", 1, "站点A", "5", "LT", "备注"));
            throw new IllegalStateException("expected 422");
        } catch (ApiException e) {
            assertThat(e.status().value()).isEqualTo(422);
        }

        // 两个裁决并发：一个选本地（LT 5），一个选服务端（EXACT 6），都期望当前为 v2。
        // 恰好一个成功；无论谁赢，结果必须是完整一侧，绝不能拼成 LT 6。
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        observationService.resolveConflict("obs-qc2", new ResolveConflictRequest(
                                "req-qc2-local", "res-qc2-local", 1, 2, "站点A", "5", "LT", "备注",
                                Map.of("reading", "CANDIDATE"), "operator-a"));
                        return "local-ok";
                    } catch (ApiException e) {
                        return "local-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.resolveConflict("obs-qc2", new ResolveConflictRequest(
                                "req-qc2-server", "res-qc2-server", 1, 2, "站点A", "5", "LT", "备注",
                                Map.of("reading", "CURRENT"), "operator-b"));
                        return "server-ok";
                    } catch (ApiException e) {
                        return "server-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        ObservationResponse current = observationService.getCurrent("obs-qc2");
        if (results.contains("local-ok")) {
            assertThat(results).containsExactlyInAnyOrder("local-ok", "server-409");
            // 选本地：必须是 LT 5，不能成为 LT 6
            assertThat(current.readingQualifier()).isEqualTo("LT");
            assertThat(current.reading()).isEqualTo("5");
            assertThat(current.version()).isEqualTo(3);
        } else {
            assertThat(results).containsExactlyInAnyOrder("server-ok", "local-409");
            // 选服务端：完整 EXACT 6，内容无变化不加版本
            assertThat(current.readingQualifier()).isEqualTo("EXACT");
            assertThat(current.reading()).isEqualTo("6");
            assertThat(current.version()).isEqualTo(2);
        }
        Integer resolutionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conflict_resolution WHERE observation_id = 'obs-qc2'", Integer.class);
        assertThat(resolutionRows).isEqualTo(1);
    }

    @Test
    void concurrentResolveAndDeleteNeverMixesGroupOrResurrects() throws Exception {
        setupBaselineExact5ServerExact6("obs-qc3", "qc3");
        try {
            observationService.merge("obs-qc3", new MergeObservationRequest(
                    "req-qc3-offline", 1, "站点A", "5", "LT", "备注"));
            throw new IllegalStateException("expected 422");
        } catch (ApiException e) {
            assertThat(e.status().value()).isEqualTo(422);
        }

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        observationService.resolveConflict("obs-qc3", new ResolveConflictRequest(
                                "req-qc3-resolve", "res-qc3", 1, 2, "站点A", "5", "LT", "备注",
                                Map.of("reading", "CANDIDATE"), "operator-a"));
                        return "resolve-ok";
                    } catch (ApiException e) {
                        return "resolve-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.delete("obs-qc3", new DeleteObservationRequest("req-qc3-delete", 2));
                        return "delete-ok";
                    } catch (ApiException e) {
                        return "delete-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        ObservationResponse current = observationService.getCurrent("obs-qc3");
        assertThat(current.version()).isEqualTo(3);
        if (results.contains("resolve-ok")) {
            // 裁决先生效：v3 为完整 LT 5，删除因版本边界不匹配失败
            assertThat(results).containsExactlyInAnyOrder("resolve-ok", "delete-409");
            assertThat(current.deleted()).isFalse();
            assertThat(current.readingQualifier()).isEqualTo("LT");
            assertThat(current.reading()).isEqualTo("5");
        } else {
            // 删除先生效：墓碑 v3 不含读数字段，裁决 410 不复活
            assertThat(results).containsExactlyInAnyOrder("delete-ok", "resolve-410");
            assertThat(current.deleted()).isTrue();
            assertThat(current.reading()).isNull();
            assertThat(current.readingQualifier()).isNull();
        }
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-qc3'", Integer.class);
        assertThat(versionRows).isEqualTo(3);
    }

    @Test
    void concurrentSameRequestIdQualifiedMergeReplaysSingleResult() throws Exception {
        setupBaselineExact5ServerExact6("obs-qc4", "qc4");

        // 同一 requestId、相同带限定符参数并发 4 次：全部返回同一结果，只产生一个新版本
        List<Callable<ObservationService.WriteOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> observationService.merge("obs-qc4", new MergeObservationRequest(
                    "req-qc4-same", 2, "站点A", "6", "GT", "并发重放")));
        }
        List<ObservationService.WriteOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        assertThat(outcomes).allMatch(outcome -> outcome.body().version() == 3);
        assertThat(outcomes).allMatch(outcome -> "GT".equals(outcome.body().readingQualifier()));
        ObservationResponse current = observationService.getCurrent("obs-qc4");
        assertThat(current.version()).isEqualTo(3);
        assertThat(current.reading()).isEqualTo("6");
        assertThat(current.readingQualifier()).isEqualTo("GT");
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-qc4-same'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-qc4'", Integer.class);
        assertThat(versionRows).isEqualTo(3);
    }
}