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
 * 更正附录并发边界测试：真实并发提交，验证两个更正争同一前置版本只成功一个、
 * 同 requestId/同 appendixId 并发重放只生成一条附录，失败不占版本号。
 */
@SpringBootTest
class CorrigendumConcurrencyTest {

    @Autowired
    private ObservationService observationService;

    @Autowired
    private CorrigendumService corrigendumService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM corrigendum_appendix");
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
     * 构造已解决簇：原始读数 10，地点冲突裁决选 CURRENT（站点B），裁决后当前为 v2。
     */
    private String setupResolvedCluster(String observationId, String tag) {
        observationService.create(new CreateObservationRequest(
                "req-" + tag + "-0", observationId, "站点A", "10", "备注"));
        observationService.merge(observationId, new MergeObservationRequest(
                "req-" + tag + "-1", 1, "站点B", "10", "备注"));
        try {
            observationService.merge(observationId, new MergeObservationRequest(
                    "req-" + tag + "-2", 1, "站点C", "10", "备注"));
            throw new IllegalStateException("expected merge conflict");
        } catch (ApiException e) {
            assertThat(e.status().value()).isEqualTo(409);
        }
        ObservationService.ResolveOutcome outcome = observationService.resolveConflict(
                observationId, new ResolveConflictRequest(
                        "req-" + tag + "-3", "res-" + tag, 1, 2,
                        "站点C", "10", "备注", Map.of("location", "CURRENT"), "reviewer-a"));
        assertThat(outcome.status()).isEqualTo(200);
        return "res-" + tag;
    }

    private CreateAppendixRequest appendixRequest(String requestId, String appendixId,
                                                  String resolutionId, int expectedVersion,
                                                  String fieldName, String newValue) {
        return new CreateAppendixRequest(requestId, appendixId, resolutionId, expectedVersion,
                fieldName, newValue, "recorder-a");
    }

    @Test
    void concurrentAppendicesCompetingForSamePredecessorOnlyOneSucceeds() throws Exception {
        String resolutionId = setupResolvedCluster("obs-c1", "c1");

        // 两个不同更正都以 V0 为前置：一个成功 V1，另一个收到版本冲突，失败不占版本号
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        CorrigendumService.CorrigendumOutcome outcome = corrigendumService.create(
                                "obs-c1", appendixRequest("req-c1-a", "ap-c1-a", resolutionId, 0,
                                        "reading", "11"));
                        return "ok-" + outcome.body().appendixVersion()
                                + "-" + outcome.body().fieldName();
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        CorrigendumService.CorrigendumOutcome outcome = corrigendumService.create(
                                "obs-c1", appendixRequest("req-c1-b", "ap-c1-b", resolutionId, 0,
                                        "note", "复核备注"));
                        return "ok-" + outcome.body().appendixVersion()
                                + "-" + outcome.body().fieldName();
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).hasSize(2);
        long okCount = results.stream().filter(r -> r.startsWith("ok-")).count();
        assertThat(okCount).isEqualTo(1);
        assertThat(results).contains("409");
        assertThat(results.stream().filter(r -> r.startsWith("ok-")).findFirst().orElseThrow())
                .isIn("ok-1-reading", "ok-1-note");

        // 只生成一条附录 V1；成功方字段生效，失败方字段未写入
        Integer appendixRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM corrigendum_appendix WHERE observation_id = 'obs-c1'", Integer.class);
        assertThat(appendixRows).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT appendix_version, field_name, old_value, new_value "
                        + "FROM corrigendum_appendix WHERE observation_id = 'obs-c1'");
        assertThat(row.get("appendix_version")).isEqualTo(1);
        // 旧值随获胜字段而定：读数获胜旧值为 10，备注获胜旧值为裁决生效备注
        if ("reading".equals(row.get("field_name"))) {
            assertThat(row.get("old_value")).isEqualTo("10");
            assertThat(row.get("new_value")).isEqualTo("11");
        } else {
            assertThat(row.get("field_name")).isEqualTo("note");
            assertThat(row.get("old_value")).isEqualTo("备注");
            assertThat(row.get("new_value")).isEqualTo("复核备注");
        }
        // 两个不同 requestId 中只有成功方占位落库；失败事务回滚不占 requestId
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id IN ('req-c1-a','req-c1-b')",
                Integer.class);
        assertThat(requestRows).isEqualTo(1);

        // 失败方用自己的 requestId 以前置 V1 重试：成功成为 V2，说明失败确实没有占用版本号
        String winnerField = (String) row.get("field_name");
        if ("reading".equals(winnerField)) {
            CorrigendumService.CorrigendumOutcome retry = corrigendumService.create(
                    "obs-c1", appendixRequest("req-c1-b", "ap-c1-b", resolutionId, 1,
                            "note", "复核备注"));
            assertThat(retry.status()).isEqualTo(201);
            assertThat(retry.body().appendixVersion()).isEqualTo(2);
        } else {
            CorrigendumService.CorrigendumOutcome retry = corrigendumService.create(
                    "obs-c1", appendixRequest("req-c1-a", "ap-c1-a", resolutionId, 1,
                            "reading", "11"));
            assertThat(retry.status()).isEqualTo(201);
            assertThat(retry.body().appendixVersion()).isEqualTo(2);
        }
        VersionedObservationResponse current = corrigendumService.getCurrentVersioned("obs-c1");
        assertThat(current.version()).isEqualTo(2);
        assertThat(current.effectiveReading()).isEqualTo("11");
        assertThat(current.effectiveNote()).isEqualTo("复核备注");
        // 原始值仍可见
        assertThat(current.originalReading()).isEqualTo("10");
    }

    @Test
    void concurrentSameAppendixIdWithSameParamsReplaysSingleAppendix() throws Exception {
        String resolutionId = setupResolvedCluster("obs-c2", "c2");

        // 同一 appendixId、相同参数、不同 requestId 并发 3 次：全部返回同一结果，只生成一条附录
        List<Callable<CorrigendumService.CorrigendumOutcome>> tasks = new ArrayList<>();
        tasks.add(() -> corrigendumService.create(
                "obs-c2", appendixRequest("req-c2-a", "ap-c2", resolutionId, 0, "reading", "11")));
        tasks.add(() -> corrigendumService.create(
                "obs-c2", appendixRequest("req-c2-b", "ap-c2", resolutionId, 0, "reading", "11")));
        tasks.add(() -> corrigendumService.create(
                "obs-c2", appendixRequest("req-c2-c", "ap-c2", resolutionId, 0, "reading", "11")));
        List<CorrigendumService.CorrigendumOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(o -> o.body().appendixId().equals("ap-c2"));
        assertThat(outcomes).allMatch(o -> o.body().appendixVersion() == 1);
        assertThat(outcomes).allMatch(o -> "11".equals(o.body().effectiveReading()));
        assertThat(outcomes).extracting(CorrigendumService.CorrigendumOutcome::status)
                .containsExactlyInAnyOrder(201, 200, 200);
        Integer appendixRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM corrigendum_appendix WHERE appendix_id = 'ap-c2'", Integer.class);
        assertThat(appendixRows).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id IN ('req-c2-a','req-c2-b','req-c2-c')",
                Integer.class);
        assertThat(requestRows).isEqualTo(3);
    }

    @Test
    void concurrentSameRequestIdReplaysSingleResult() throws Exception {
        String resolutionId = setupResolvedCluster("obs-c3", "c3");

        // 同一 requestId + 同一 appendixId + 相同参数并发 3 次：只占一键、只生成一条附录
        List<Callable<CorrigendumService.CorrigendumOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> corrigendumService.create(
                    "obs-c3", appendixRequest("req-c3-same", "ap-c3", resolutionId, 0,
                            "reading", "11")));
        }
        List<CorrigendumService.CorrigendumOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(o -> o.status() == 201);
        assertThat(outcomes).allMatch(o -> o.body().appendixVersion() == 1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-c3-same'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
        Integer appendixRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM corrigendum_appendix WHERE observation_id = 'obs-c3'",
                Integer.class);
        assertThat(appendixRows).isEqualTo(1);
    }

    @Test
    void concurrentAppendixAndMergeWinnerDecidesVersionState() throws Exception {
        // 裁决之后并发：一个附录、一个普通合并。行锁串行化二者：
        // 合并先提交则附录因裁决后版本漂移返回 422；附录先提交则合并按三方规则处理。
        String resolutionId = setupResolvedCluster("obs-c4", "c4");

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        corrigendumService.create(
                                "obs-c4", appendixRequest("req-c4-ap", "ap-c4", resolutionId, 0,
                                        "reading", "11"));
                        return "appendix-ok";
                    } catch (ApiException e) {
                        return "appendix-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.merge("obs-c4", new MergeObservationRequest(
                                "req-c4-m", 2, "站点B", "10", "合并备注"));
                        return "merge-ok";
                    } catch (ApiException e) {
                        return "merge-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        Integer appendixRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM corrigendum_appendix WHERE observation_id = 'obs-c4'",
                Integer.class);
        ObservationResponse observation = observationService.getCurrent("obs-c4");
        if (results.contains("appendix-422")) {
            // 合并先生效：观测版本漂移到 v3，附录被拒绝，读数仍为 10
            assertThat(results).containsExactlyInAnyOrder("merge-ok", "appendix-422");
            assertThat(appendixRows).isZero();
            assertThat(observation.version()).isEqualTo(3);
            assertThat(observation.note()).isEqualTo("合并备注");
        } else {
            // 附录先生效 V1（读数 11），随后合并基于 v2 应用备注到 v3；
            // 附录链是锚定在裁决簇上的不可变历史：V1 视角读数仍为 11，不被后续合并改写。
            assertThat(results).containsExactlyInAnyOrder("appendix-ok", "merge-ok");
            assertThat(appendixRows).isEqualTo(1);
            assertThat(observation.version()).isEqualTo(3);
            VersionedObservationResponse v1 = corrigendumService.getVersioned("obs-c4", 1);
            assertThat(v1.effectiveReading()).isEqualTo("11");
            VersionedObservationResponse v0 = corrigendumService.getVersioned("obs-c4", 0);
            assertThat(v0.effectiveReading()).isEqualTo("10");
            // 漂移后新的附录不能再挂在原裁决上：422
            org.junit.jupiter.api.Assertions.assertThrows(ApiException.class, () -> corrigendumService.create(
                    "obs-c4", appendixRequest("req-c4-ap2", "ap-c4-2", resolutionId, 1,
                            "note", "再更正")));
        }
    }
}
