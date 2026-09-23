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
 * 冲突解决并发边界测试：真实并发提交，验证只有匹配实际当前版本者成功、
 * 解决与删除竞争不覆盖新版本或复活墓碑、同 resolutionId 并发重放只生成一条记录。
 */
@SpringBootTest
class ResolutionConcurrencyTest {

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

    private void setupLocationConflict(String observationId, String tag) {
        observationService.create(new CreateObservationRequest(
                "req-" + tag + "-0", observationId, "survey-1", "站点A", "1.0", "备注"));
        observationService.merge(observationId, new MergeObservationRequest(
                "req-" + tag + "-1", 1, "站点B", "1.0", "备注"));
        // 离线端基于 v1 改站点C：与服务端站点B 冲突，普通合并失败不占键
        try {
            observationService.merge(observationId, new MergeObservationRequest(
                    "req-" + tag + "-2", 1, "站点C", "1.0", "备注"));
            throw new IllegalStateException("expected merge conflict");
        } catch (ApiException e) {
            assertThat(e.status().value()).isEqualTo(409);
        }
    }

    private ResolveConflictRequest resolveRequest(String requestId, String resolutionId,
                                                  int expectedCurrentVersion) {
        return new ResolveConflictRequest(requestId, resolutionId, 1, expectedCurrentVersion,
                "站点C", "1.0", "备注", Map.of("location", "CANDIDATE"), "operator-a");
    }

    @Test
    void concurrentResolvesOnlyVersionMatcherSucceeds() throws Exception {
        setupLocationConflict("obs-x1", "x1");

        // 两个解决请求都期望当前为 v2：先提交者推进到 v3，后到者版本不匹配 409，不得覆盖新版本
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        ObservationService.ResolveOutcome outcome = observationService.resolveConflict(
                                "obs-x1", resolveRequest("req-x1-a", "res-x1-a", 2));
                        return "ok-" + outcome.body().version();
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        ObservationService.ResolveOutcome outcome = observationService.resolveConflict(
                                "obs-x1", resolveRequest("req-x1-b", "res-x1-b", 2));
                        return "ok-" + outcome.body().version();
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok-3", "409");
        ObservationResponse current = observationService.getCurrent("obs-x1");
        assertThat(current.version()).isEqualTo(3);
        assertThat(current.location()).isEqualTo("站点C");
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-x1'", Integer.class);
        assertThat(versionRows).isEqualTo(3);
        Integer resolutionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conflict_resolution WHERE observation_id = 'obs-x1'", Integer.class);
        assertThat(resolutionRows).isEqualTo(1);
        // 两个不同 requestId 中只有成功方占位落库；失败事务回滚，不占 requestId
        Integer totalRequestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id IN ('req-x1-a','req-x1-b')", Integer.class);
        assertThat(totalRequestRows).isEqualTo(1);
        // 落库的解决记录与成功请求一一对应
        String storedResolutionId = jdbcTemplate.queryForObject(
                "SELECT resolution_id FROM conflict_resolution WHERE observation_id = 'obs-x1'", String.class);
        String storedRequestId = jdbcTemplate.queryForObject(
                "SELECT request_id FROM conflict_resolution WHERE observation_id = 'obs-x1'", String.class);
        assertThat(storedResolutionId).startsWith("res-x1-");
        assertThat(storedRequestId).isEqualTo("req-" + storedResolutionId.substring("res-".length()));
    }

    @Test
    void concurrentResolveAndDeleteNeverOverwritesOrResurrects() throws Exception {
        setupLocationConflict("obs-x2", "x2");

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        observationService.resolveConflict(
                                "obs-x2", resolveRequest("req-x2-r", "res-x2", 2));
                        return "resolve-ok";
                    } catch (ApiException e) {
                        return "resolve-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.delete("obs-x2", new DeleteObservationRequest("req-x2-d", 2));
                        return "delete-ok";
                    } catch (ApiException e) {
                        return "delete-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        ObservationResponse current = observationService.getCurrent("obs-x2");
        if (results.contains("resolve-ok")) {
            // 解决先生效：内容为站点C/v3，删除因版本不匹配失败
            assertThat(results).containsExactlyInAnyOrder("resolve-ok", "delete-409");
            assertThat(current.deleted()).isFalse();
            assertThat(current.version()).isEqualTo(3);
            assertThat(current.location()).isEqualTo("站点C");
        } else {
            // 删除先生效：墓碑 v3 不得被并发解决复活，解决返回 410
            assertThat(results).containsExactlyInAnyOrder("delete-ok", "resolve-410");
            assertThat(current.deleted()).isTrue();
            assertThat(current.version()).isEqualTo(3);
        }
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-x2'", Integer.class);
        assertThat(versionRows).isEqualTo(3);
    }

    @Test
    void concurrentSameResolutionIdWithSameParamsReplaysSingleRecord() throws Exception {
        setupLocationConflict("obs-x3", "x3");

        // 同一 resolutionId、相同参数、不同 requestId 并发 3 次：全部返回同一结果，只生成一个新版本与一张解决记录
        List<Callable<ObservationService.ResolveOutcome>> tasks = new ArrayList<>();
        tasks.add(() -> observationService.resolveConflict(
                "obs-x3", resolveRequest("req-x3-a", "res-x3", 2)));
        tasks.add(() -> observationService.resolveConflict(
                "obs-x3", resolveRequest("req-x3-b", "res-x3", 2)));
        tasks.add(() -> observationService.resolveConflict(
                "obs-x3", resolveRequest("req-x3-c", "res-x3", 2)));
        List<ObservationService.ResolveOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        assertThat(outcomes).allMatch(outcome -> outcome.body().version() == 3);
        assertThat(outcomes).allMatch(outcome -> "res-x3".equals(outcome.body().resolutionId()));
        ObservationResponse current = observationService.getCurrent("obs-x3");
        assertThat(current.version()).isEqualTo(3);
        assertThat(current.location()).isEqualTo("站点C");
        Integer resolutionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conflict_resolution WHERE resolution_id = 'res-x3'", Integer.class);
        assertThat(resolutionRows).isEqualTo(1);
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-x3'", Integer.class);
        assertThat(versionRows).isEqualTo(3);
        // 三个不同 requestId 各有一条成功重放记录
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id IN ('req-x3-a','req-x3-b','req-x3-c')",
                Integer.class);
        assertThat(requestRows).isEqualTo(3);
    }

    @Test
    void concurrentSameRequestIdResolvesReplaysSingleResult() throws Exception {
        setupLocationConflict("obs-x4", "x4");

        // 同一 requestId + 同一 resolutionId + 相同参数并发 3 次：只占一键、只生成一个新版本
        List<Callable<ObservationService.ResolveOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> observationService.resolveConflict(
                    "obs-x4", resolveRequest("req-x4-same", "res-x4", 2)));
        }
        List<ObservationService.ResolveOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        assertThat(outcomes).allMatch(outcome -> outcome.body().version() == 3);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-x4-same'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
        Integer resolutionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conflict_resolution WHERE resolution_id = 'res-x4'", Integer.class);
        assertThat(resolutionRows).isEqualTo(1);
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-x4'", Integer.class);
        assertThat(versionRows).isEqualTo(3);
    }
}
