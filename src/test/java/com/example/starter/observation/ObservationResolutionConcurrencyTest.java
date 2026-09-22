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
 * 冲突解决的并发与双重幂等边界测试：真实并发提交，验证只有匹配实际当前版本者成功、
 * 墓碑不被复活、resolutionId 与 requestId 并发重放各占一键。
 */
@SpringBootTest
class ObservationResolutionConcurrencyTest {

    @Autowired
    private ObservationService observationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_resolution");
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

    private ResolveObservationRequest resolveRequest(String resolutionId, String requestId,
                                                     int baseVersion, int expectedCurrentVersion,
                                                     String location, Map<String, String> selections) {
        return new ResolveObservationRequest(resolutionId, requestId, baseVersion, expectedCurrentVersion,
                location, "1.0", "初始备注", selections, "巡检员甲");
    }

    private int resolutionCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_resolution", Integer.class);
        return count == null ? 0 : count;
    }

    private int versionCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    @Test
    void concurrentResolvesOnlyMatchingVersionSucceeds() throws Exception {
        createObservation("obs-rc1", "req-rc0");
        observationService.merge("obs-rc1", new MergeObservationRequest("req-rc1", 1, "站点B", "1.0", "初始备注"));

        // 两个解决请求都期望当前版本为 2：串行化后只有一个匹配实际当前版本
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        ObservationService.WriteOutcome<ResolutionResponse> outcome =
                                observationService.resolve("obs-rc1", resolveRequest(
                                        "res-a", "req-rc2", 1, 2, "站点C", Map.of("location", "CANDIDATE")));
                        return "ok-" + outcome.body().resultVersion();
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        ObservationService.WriteOutcome<ResolutionResponse> outcome =
                                observationService.resolve("obs-rc1", resolveRequest(
                                        "res-b", "req-rc3", 1, 2, "站点D", Map.of("location", "CANDIDATE")));
                        return "ok-" + outcome.body().resultVersion();
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok-3", "409");
        ObservationResponse current = observationService.getCurrent("obs-rc1");
        assertThat(current.version()).isEqualTo(3);
        assertThat(current.location()).isIn("站点C", "站点D");
        assertThat(versionCount("obs-rc1")).isEqualTo(3);
        assertThat(resolutionCount()).isEqualTo(1);
    }

    @Test
    void concurrentResolveAndDeleteNeverResurrectsTombstone() throws Exception {
        createObservation("obs-rc2", "req-rd0");
        observationService.merge("obs-rc2", new MergeObservationRequest("req-rd1", 1, "站点B", "1.0", "初始备注"));

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        observationService.resolve("obs-rc2", resolveRequest(
                                "res-del", "req-rd2", 1, 2, "站点C", Map.of("location", "CANDIDATE")));
                        return "resolve-ok";
                    } catch (ApiException e) {
                        return "resolve-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.delete("obs-rc2", new DeleteObservationRequest("req-rd3", 2));
                        return "delete-ok";
                    } catch (ApiException e) {
                        return "delete-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 串行化后恰好一个成功：先删则解决 409（版本前进），先解决则删除 409（版本不匹配）
        assertThat(results).containsExactlyInAnyOrder(
                results.contains("resolve-ok") ? "delete-409" : "resolve-409",
                results.contains("resolve-ok") ? "resolve-ok" : "delete-ok");

        ObservationResponse current = observationService.getCurrent("obs-rc2");
        assertThat(current.version()).isEqualTo(3);
        if (current.deleted()) {
            // 删除先生效：墓碑不得被并发解决复活
            assertThat(current.location()).isNull();
            assertThat(resolutionCount()).isZero();
        } else {
            // 解决先生效：内容为新值，删除因版本不匹配被拒绝
            assertThat(current.location()).isEqualTo("站点C");
            assertThat(resolutionCount()).isEqualTo(1);
        }
        assertThat(versionCount("obs-rc2")).isEqualTo(3);
    }

    @Test
    void concurrentSameResolutionIdSameParamsYieldsSingleRecord() throws Exception {
        createObservation("obs-rc3", "req-ri0");
        observationService.merge("obs-rc3", new MergeObservationRequest("req-ri1", 1, "站点B", "1.0", "初始备注"));

        // 同一 resolutionId、相同参数、不同 requestId 并发提交 3 次：
        // 全部返回原结果，只产生一条解决记录与一个新版本
        List<Callable<ObservationService.WriteOutcome<ResolutionResponse>>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int index = i;
            tasks.add(() -> observationService.resolve("obs-rc3", resolveRequest(
                    "res-dup", "req-ri2-" + index, 1, 2, "站点C", Map.of("location", "CANDIDATE"))));
        }
        List<ObservationService.WriteOutcome<ResolutionResponse>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        assertThat(outcomes).allMatch(outcome -> outcome.body().resultVersion() == 3);
        assertThat(outcomes).allMatch(outcome -> outcome.body().resolutionId().equals("res-dup"));
        ObservationResponse current = observationService.getCurrent("obs-rc3");
        assertThat(current.version()).isEqualTo(3);
        assertThat(current.location()).isEqualTo("站点C");
        assertThat(resolutionCount()).isEqualTo(1);
        assertThat(versionCount("obs-rc3")).isEqualTo(3);
    }

    @Test
    void concurrentSameRequestIdAndResolutionIdReplaysSingleResult() throws Exception {
        createObservation("obs-rc4", "req-rk0");
        observationService.merge("obs-rc4", new MergeObservationRequest("req-rk1", 1, "站点B", "1.0", "初始备注"));

        // 双重幂等：同一 requestId 与同一 resolutionId、相同参数并发提交 3 次
        List<Callable<ObservationService.WriteOutcome<ResolutionResponse>>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> observationService.resolve("obs-rc4", resolveRequest(
                    "res-dup2", "req-rk2", 1, 2, "站点C", Map.of("location", "CANDIDATE"))));
        }
        List<ObservationService.WriteOutcome<ResolutionResponse>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        assertThat(outcomes).allMatch(outcome -> outcome.body().resultVersion() == 3);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-rk2'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
        assertThat(resolutionCount()).isEqualTo(1);
        assertThat(versionCount("obs-rc4")).isEqualTo(3);
    }
}
