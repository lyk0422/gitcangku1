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
 * 更正附录并发与幂等边界测试（真实 H2 内存库）：两个更正争用同一前置附录版本时只能一个提交，
 * 另一个收到 409；同 requestId 并发同参只产生一份附录；失败不占附录版本号。
 */
@SpringBootTest
class CorrigendumConcurrencyTest {

    @Autowired
    private CorrigendumService corrigendumService;

    @Autowired
    private ObservationService observationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM observation_corrigendum");
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
     * 构造已解决簇：v1=(站点A,10,备注) → v2 地点改站点B → 裁决选候选站点C，生成 v3=(站点C,10,备注)。
     */
    private void setupResolvedCluster(String observationId) {
        observationService.create(new CreateObservationRequest(
                "req-create-" + observationId, observationId, "站点A", "10", "备注"));
        observationService.merge(observationId,
                new MergeObservationRequest("req-merge-" + observationId, 1, "站点B", "10", "备注"));
        observationService.resolveConflict(observationId, new ResolveConflictRequest(
                "req-resolve-" + observationId, "res-" + observationId, 1, 2,
                "站点C", "10", "备注", Map.of("location", "CANDIDATE"), "reviewer-a"));
    }

    private CreateCorrigendumRequest corrigendum(String requestId, int previousVersion,
                                                 String field, String oldValue, String newValue,
                                                 String operator) {
        return new CreateCorrigendumRequest(requestId, previousVersion,
                List.of(new FieldChangeRequest(field, oldValue, newValue)), operator);
    }

    private String submitResult(String observationId, CreateCorrigendumRequest request) {
        try {
            CorrigendumService.CorrigendumOutcome outcome =
                    corrigendumService.submitCorrigendum(observationId, request);
            return "ok-v" + outcome.body().corrigendumVersion();
        } catch (ApiException e) {
            return String.valueOf(e.status().value());
        }
    }

    @Test
    void twoCorrectionsContendingSamePreviousVersionOnlyOneCommits() throws Exception {
        setupResolvedCluster("obs-cc1");

        // 两个记录员同时基于前置附录版本 0：一个改读数 10→11，一个改备注
        List<Callable<String>> tasks = List.of(
                () -> submitResult("obs-cc1",
                        corrigendum("req-cc1-a", 0, "reading", "10", "11", "recorder-a")),
                () -> submitResult("obs-cc1",
                        corrigendum("req-cc1-b", 0, "note", "备注", "并发备注", "recorder-b")));
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok-v1", "409");

        // 只有一份附录落库，版本号为 1，失败方不占版本号
        Integer appendixRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_corrigendum WHERE observation_id = 'obs-cc1'",
                Integer.class);
        assertThat(appendixRows).isEqualTo(1);
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT CAST(corrigendum_version AS VARCHAR) FROM observation_corrigendum "
                        + "WHERE observation_id = 'obs-cc1' ORDER BY corrigendum_version", String.class);
        assertThat(versions).containsExactly("1");

        // V0 原始值 10 保持只读；V1 为获胜更正后的有效值；失败方基于最新版本 1 重试成功为 V2
        CorrigendumVersionView v0 = corrigendumService.getVersionView("obs-cc1", 0);
        assertThat(v0.reading()).isEqualTo("10");
        CorrigendumVersionView v1 = corrigendumService.getVersionView("obs-cc1", 1);
        if (v1.reading().equals("11")) {
            assertThat(v1.note()).isEqualTo("备注");
            CorrigendumService.CorrigendumOutcome retry = corrigendumService.submitCorrigendum("obs-cc1",
                    corrigendum("req-cc1-b", 1, "note", "备注", "并发备注", "recorder-b"));
            assertThat(retry.status()).isEqualTo(201);
            assertThat(retry.body().corrigendumVersion()).isEqualTo(2);
            assertThat(retry.body().reading()).isEqualTo("11");
            assertThat(retry.body().note()).isEqualTo("并发备注");
        } else {
            assertThat(v1.note()).isEqualTo("并发备注");
            assertThat(v1.reading()).isEqualTo("10");
            CorrigendumService.CorrigendumOutcome retry = corrigendumService.submitCorrigendum("obs-cc1",
                    corrigendum("req-cc1-a", 1, "reading", "10", "11", "recorder-a"));
            assertThat(retry.status()).isEqualTo(201);
            assertThat(retry.body().corrigendumVersion()).isEqualTo(2);
            assertThat(retry.body().reading()).isEqualTo("11");
        }
        assertThat(corrigendumService.getVersionView("obs-cc1", 0).reading()).isEqualTo("10");
    }

    @Test
    void concurrentSameRequestIdWithSameParamsCreatesSingleAppendix() throws Exception {
        setupResolvedCluster("obs-cc2");

        List<Callable<CorrigendumService.CorrigendumOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> corrigendumService.submitCorrigendum("obs-cc2",
                    corrigendum("req-cc2-same", 0, "reading", "10", "11", "recorder-a")));
        }
        List<CorrigendumService.CorrigendumOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).hasSize(4);
        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes).allMatch(outcome -> outcome.body().corrigendumVersion() == 1);
        Integer appendixRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_corrigendum WHERE observation_id = 'obs-cc2'",
                Integer.class);
        assertThat(appendixRows).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-cc2-same'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentCorrectionsBasedOnZeroAndOneEitherSerializeOrConflict() throws Exception {
        setupResolvedCluster("obs-cc3");

        // 第一个更正基于版本 0，第二个更正基于版本 1（其旧值 11 只有在 V1 落库后才成立）：
        // 两者同时进入并由行锁串行化——基于 0 的必然成为 V1；基于 1 的若先拿锁则 409（V1 尚不存在），
        // 若后拿锁则前置版本已就绪，合法成为 V2。
        List<Callable<String>> tasks = List.of(
                () -> submitResult("obs-cc3",
                        corrigendum("req-cc3-a", 0, "reading", "10", "11", "recorder-a")),
                () -> submitResult("obs-cc3",
                        corrigendum("req-cc3-b", 1, "reading", "11", "12", "recorder-b")));
        List<String> results = runConcurrently(tasks);

        assertThat(results).contains("ok-v1");
        assertThat(results).anyMatch(result -> result.equals("409") || result.equals("ok-v2"));

        // 若第二个更正当时因前置版本不存在而 409，失败不占键；用同一 requestId 补齐为 V2；
        // 若它已成功为 V2，同参重放返回同一结果。
        CorrigendumService.CorrigendumOutcome retry = corrigendumService.submitCorrigendum("obs-cc3",
                corrigendum("req-cc3-b", 1, "reading", "11", "12", "recorder-b"));
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.body().corrigendumVersion()).isEqualTo(2);

        Integer appendixRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_corrigendum WHERE observation_id = 'obs-cc3'",
                Integer.class);
        assertThat(appendixRows).isEqualTo(2);
        assertThat(corrigendumService.getVersionView("obs-cc3", 0).reading()).isEqualTo("10");
        assertThat(corrigendumService.getVersionView("obs-cc3", 1).reading()).isEqualTo("11");
        assertThat(corrigendumService.getVersionView("obs-cc3", 2).reading()).isEqualTo("12");
    }
}
