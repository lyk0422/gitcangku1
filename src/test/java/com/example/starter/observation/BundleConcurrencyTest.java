package com.example.starter.observation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * 关联观测簇联合裁决并发边界测试：真实并发提交，验证两个联合裁决按提交顺序串行、
 * 裁决与单条冲突解决/删除竞争不会出现半个簇已裁决、同 requestId 并发重放只生成一条记录。
 */
@SpringBootTest
class BundleConcurrencyTest {

    @Autowired
    private ObservationService observationService;

    @Autowired
    private BundleService bundleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM bundle_arbitration");
        jdbcTemplate.update("DELETE FROM bundle_conflict");
        jdbcTemplate.update("DELETE FROM bundle_member");
        jdbcTemplate.update("DELETE FROM observation_bundle");
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

    private void createObservation(String id, String surveyId, String location, String reading, String note) {
        observationService.create(new CreateObservationRequest(
                "req-create-" + id, id, surveyId, location, reading, note));
    }

    private void merge(String id, int baseVersion, String location, String reading, String note) {
        observationService.merge(id, new MergeObservationRequest(
                "req-merge-" + id + "-" + baseVersion + "-" + location + reading,
                baseVersion, location, reading, note));
    }

    private String bundleWithTwoLocationConflicts(String bundleKey) {
        createObservation("obs-q1", "S1", "A地", "1.0", "备注1");
        createObservation("obs-q2", "S1", "A地", "1.0", "备注2");
        merge("obs-q1", 1, "B地", "1.0", "备注1");
        merge("obs-q2", 1, "B地", "1.0", "备注2");
        bundleService.createBundle(new CreateBundleRequest(
                "req-bundle-" + bundleKey, bundleKey, "S1", List.of("obs-q1", "obs-q2"),
                List.of(), "op"));
        bundleService.registerConflict(bundleKey, new RegisterBundleConflictRequest(
                "req-reg-q1-" + bundleKey, "obs-q1", 1, "C地", "1.0", "备注1"));
        bundleService.registerConflict(bundleKey, new RegisterBundleConflictRequest(
                "req-reg-q2-" + bundleKey, "obs-q2", 1, "C地", "1.0", "备注2"));
        return candidateToken(bundleKey, "obs-q1", "location");
    }

    private String candidateToken(String bundleKey, String observationId, String field) {
        BundleResponse bundle = bundleService.getBundle(bundleKey);
        return bundle.openConflicts().stream()
                .filter(conflict -> conflict.observationId().equals(observationId)
                        && conflict.field().equals(field))
                .findFirst()
                .orElseThrow()
                .candidateToken();
    }

    private long conflictId(String bundleKey, String observationId, String field) {
        return bundleService.getBundle(bundleKey).openConflicts().stream()
                .filter(conflict -> conflict.observationId().equals(observationId)
                        && conflict.field().equals(field))
                .findFirst()
                .orElseThrow()
                .conflictId();
    }

    private ArbitrateBundleRequest arbitrationRequest(String requestId, Map<String, Integer> versions,
                                                      List<BundleConflictChoice> choices,
                                                      List<BundleRestoreInstruction> restores) {
        List<BundleExpectedVersion> expected = versions.entrySet().stream()
                .map(entry -> new BundleExpectedVersion(entry.getKey(), entry.getValue()))
                .toList();
        return new ArbitrateBundleRequest(requestId, "op", expected, choices, restores);
    }

    private BundleConflictChoice choice(String bundleKey, String observationId, String field,
                                        String source, String value) {
        BundleResponse.ConflictView conflict = bundleService.getBundle(bundleKey).openConflicts().stream()
                .filter(view -> view.observationId().equals(observationId) && view.field().equals(field))
                .findFirst()
                .orElseThrow();
        return new BundleConflictChoice(conflict.conflictId(), conflict.candidateToken(), source, value);
    }

    @Test
    void concurrentArbitrationsSerializeByCommitOrder() throws Exception {
        bundleWithTwoLocationConflicts("bundle-z1");

        // 两个裁决都期望当前版本为 2：先提交者成功（REMOTE 推进到 v3）并关闭簇；后到者因簇已关闭 409
        Callable<String> first = () -> {
            try {
                List<BundleConflictChoice> choices = List.of(
                        choice("bundle-z1", "obs-q1", "location", "REMOTE", null),
                        choice("bundle-z1", "obs-q2", "location", "REMOTE", null));
                bundleService.arbitrate("bundle-z1", arbitrationRequest(
                        "req-z1-a", Map.of("obs-q1", 2, "obs-q2", 2), choices, List.of()));
                return "ok";
            } catch (ApiException e) {
                return String.valueOf(e.status().value());
            }
        };
        Callable<String> second = () -> {
            try {
                List<BundleConflictChoice> choices = List.of(
                        choice("bundle-z1", "obs-q1", "location", "REMOTE", null),
                        choice("bundle-z1", "obs-q2", "location", "REMOTE", null));
                bundleService.arbitrate("bundle-z1", arbitrationRequest(
                        "req-z1-b", Map.of("obs-q1", 2, "obs-q2", 2), choices, List.of()));
                return "ok";
            } catch (ApiException e) {
                return String.valueOf(e.status().value());
            }
        };
        List<String> results = runConcurrently(List.of(first, second));

        assertThat(results).containsExactlyInAnyOrder("ok", "409");
        // 不出现半个簇已裁决：两条观测版本一致前进，冲突全部 RESOLVED，恰好一张裁决记录
        ObservationResponse q1 = observationService.getCurrent("obs-q1");
        ObservationResponse q2 = observationService.getCurrent("obs-q2");
        assertThat(q1.version()).isEqualTo(3);
        assertThat(q2.version()).isEqualTo(3);
        Integer resolvedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bundle_conflict WHERE bundle_key = 'bundle-z1' AND status = 'RESOLVED'",
                Integer.class);
        assertThat(resolvedRows).isEqualTo(2);
        Integer openRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bundle_conflict WHERE bundle_key = 'bundle-z1' AND status = 'OPEN'",
                Integer.class);
        assertThat(openRows).isZero();
        Integer arbitrationRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bundle_arbitration WHERE bundle_key = 'bundle-z1'", Integer.class);
        assertThat(arbitrationRows).isEqualTo(1);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM observation_bundle WHERE bundle_key = 'bundle-z1'", String.class);
        assertThat(status).isEqualTo("CLOSED");
    }

    @Test
    void concurrentArbitrationAndSingleResolveNeverLeavesHalfResolvedCluster() throws Exception {
        bundleWithTwoLocationConflicts("bundle-z2");

        Callable<String> bundleTask = () -> {
            try {
                List<BundleConflictChoice> choices = List.of(
                        choice("bundle-z2", "obs-q1", "location", "REMOTE", null),
                        choice("bundle-z2", "obs-q2", "location", "REMOTE", null));
                bundleService.arbitrate("bundle-z2", arbitrationRequest(
                        "req-z2-arb", Map.of("obs-q1", 2, "obs-q2", 2), choices, List.of()));
                return "arb-ok";
            } catch (ApiException e) {
                return "arb-" + e.status().value();
            }
        };
        // 并发单条冲突解决 obs-q1（候选 C地）：先提交者推进 obs-q1 版本，另一方按版本前提失败
        Callable<String> resolveTask = () -> {
            try {
                observationService.resolveConflict("obs-q1", new ResolveConflictRequest(
                        "req-z2-res", "res-z2", 1, 2, "C地", "1.0", "备注1",
                        Map.of("location", "CANDIDATE"), "op"));
                return "resolve-ok";
            } catch (ApiException e) {
                return "resolve-" + e.status().value();
            }
        };
        List<String> results = runConcurrently(List.of(bundleTask, resolveTask));

        ObservationResponse q1 = observationService.getCurrent("obs-q1");
        ObservationResponse q2 = observationService.getCurrent("obs-q2");
        if (results.contains("arb-ok")) {
            // 裁决先提交：两观测都到 v3，单条解决因版本不匹配失败
            assertThat(results).containsExactlyInAnyOrder("arb-ok", "resolve-409");
            assertThat(q1.version()).isEqualTo(3);
            assertThat(q2.version()).isEqualTo(3);
            assertThat(q1.location()).isEqualTo("C地");
        } else {
            // 单条解决先提交：obs-q1 到 v3，裁决因 expectedVersion 不匹配整体失败，obs-q2 保持 v2
            assertThat(results).containsExactlyInAnyOrder("arb-409", "resolve-ok");
            assertThat(q1.version()).isEqualTo(3);
            assertThat(q1.location()).isEqualTo("C地");
            assertThat(q2.version()).isEqualTo(2);
            assertThat(q2.location()).isEqualTo("B地");
            // 裁决失败不留半个簇：冲突仍全部 OPEN，簇仍 OPEN，无裁决记录
            Integer openRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM bundle_conflict WHERE bundle_key = 'bundle-z2' AND status = 'OPEN'",
                    Integer.class);
            assertThat(openRows).isEqualTo(2);
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM observation_bundle WHERE bundle_key = 'bundle-z2'", String.class);
            assertThat(status).isEqualTo("OPEN");
        }
    }

    @Test
    void concurrentArbitrationAndDeleteSerializeWithoutResurrectingOrHalfCommit() throws Exception {
        bundleWithTwoLocationConflicts("bundle-z3");

        Callable<String> bundleTask = () -> {
            try {
                List<BundleConflictChoice> choices = List.of(
                        choice("bundle-z3", "obs-q1", "location", "REMOTE", null),
                        choice("bundle-z3", "obs-q2", "location", "REMOTE", null));
                bundleService.arbitrate("bundle-z3", arbitrationRequest(
                        "req-z3-arb", Map.of("obs-q1", 2, "obs-q2", 2), choices, List.of()));
                return "arb-ok";
            } catch (ApiException e) {
                return "arb-" + e.status().value();
            }
        };
        Callable<String> deleteTask = () -> {
            try {
                observationService.delete("obs-q1", new DeleteObservationRequest("req-z3-del", 2));
                return "delete-ok";
            } catch (ApiException e) {
                return "delete-" + e.status().value();
            }
        };
        List<String> results = runConcurrently(List.of(bundleTask, deleteTask));

        ObservationResponse q1 = observationService.getCurrent("obs-q1");
        if (results.contains("arb-ok")) {
            // 裁决先提交：obs-q1 为存活 v3，删除因版本不匹配失败
            assertThat(results).containsExactlyInAnyOrder("arb-ok", "delete-409");
            assertThat(q1.deleted()).isFalse();
            assertThat(q1.version()).isEqualTo(3);
        } else {
            // 删除先提交：obs-q1 墓碑 v3 且其 OPEN 冲突级联清除，裁决因选择多余/版本前提失败，绝不复活
            assertThat(results).containsExactlyInAnyOrder("arb-409", "delete-ok");
            assertThat(q1.deleted()).isTrue();
            assertThat(q1.version()).isEqualTo(3);
            Integer q1OpenConflicts = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM bundle_conflict WHERE bundle_key = 'bundle-z3' "
                            + "AND observation_id = 'obs-q1' AND status = 'OPEN'", Integer.class);
            assertThat(q1OpenConflicts).isZero();
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM observation_bundle WHERE bundle_key = 'bundle-z3'", String.class);
            assertThat(status).isEqualTo("OPEN");
        }
    }

    @Test
    void concurrentSameRequestIdArbitrationReplaysSingleResult() throws Exception {
        bundleWithTwoLocationConflicts("bundle-z4");

        List<Callable<BundleService.ArbitrateOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> {
                List<BundleConflictChoice> choices = List.of(
                        choice("bundle-z4", "obs-q1", "location", "REMOTE", null),
                        choice("bundle-z4", "obs-q2", "location", "REMOTE", null));
                return bundleService.arbitrate("bundle-z4", arbitrationRequest(
                        "req-z4-same", Map.of("obs-q1", 2, "obs-q2", 2), choices, List.of()));
            });
        }
        List<BundleService.ArbitrateOutcome> outcomes = runConcurrently(tasks);

        String arbitrationId = outcomes.get(0).body().arbitrationId();
        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        assertThat(outcomes).allMatch(outcome ->
                outcome.body().arbitrationId().equals(arbitrationId));
        Integer arbitrationRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bundle_arbitration WHERE bundle_key = 'bundle-z4'", Integer.class);
        assertThat(arbitrationRows).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-z4-same'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id IN ('obs-q1','obs-q2')",
                Integer.class);
        assertThat(versionRows).isEqualTo(6);
    }

    @Test
    void sameObservationCannotJoinTwoOpenBundlesConcurrently() throws Exception {
        createObservation("obs-s1", "S1", "A地", "1.0", "备注1");
        createObservation("obs-s2", "S1", "A地", "1.0", "备注2");
        createObservation("obs-s3", "S1", "A地", "1.0", "备注3");

        Callable<String> first = () -> {
            try {
                bundleService.createBundle(new CreateBundleRequest(
                        "req-bs1", "bundle-s1", "S1", List.of("obs-s1", "obs-s2"), List.of(), "op"));
                return "b1-ok";
            } catch (ApiException e) {
                return "b1-" + e.status().value();
            }
        };
        Callable<String> second = () -> {
            try {
                bundleService.createBundle(new CreateBundleRequest(
                        "req-bs2", "bundle-s2", "S1", List.of("obs-s1", "obs-s3"), List.of(), "op"));
                return "b2-ok";
            } catch (ApiException e) {
                return "b2-" + e.status().value();
            }
        };
        List<String> results = runConcurrently(List.of(first, second));

        // 库级唯一索引保证同一观测不会同时进入两个未结簇：恰好一个成功（先提交者赢，身份不限）
        long okCount = results.stream().filter(result -> result.endsWith("-ok")).count();
        long conflictCount = results.stream().filter(result -> result.endsWith("-409")).count();
        assertThat(okCount).isEqualTo(1);
        assertThat(conflictCount).isEqualTo(1);
        Integer openBundles = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_bundle WHERE status = 'OPEN'", Integer.class);
        assertThat(openBundles).isEqualTo(1);
        Integer occupation = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bundle_member WHERE open_observation_key = 'obs-s1'", Integer.class);
        assertThat(occupation).isEqualTo(1);
    }

    @Test
    void concurrentArbitrationFailureRollsBackRequestPlaceholder() throws Exception {
        // 两个并发裁决都提交错误（expectedVersion 不匹配）：均失败且 requestId 不占键、簇不变
        bundleWithTwoLocationConflicts("bundle-z5");
        List<Callable<String>> tasks = new ArrayList<>();
        for (String requestId : List.of("req-z5-a", "req-z5-b")) {
            tasks.add(() -> {
                try {
                    List<BundleConflictChoice> choices = List.of(
                            choice("bundle-z5", "obs-q1", "location", "LOCAL", null),
                            choice("bundle-z5", "obs-q2", "location", "LOCAL", null));
                    bundleService.arbitrate("bundle-z5", arbitrationRequest(
                            requestId, Map.of("obs-q1", 9, "obs-q2", 9), choices, List.of()));
                    return "ok";
                } catch (ApiException e) {
                    return String.valueOf(e.status().value());
                }
            });
        }
        List<String> results = runConcurrently(tasks);
        assertThat(results).containsExactly("409", "409");
        for (String requestId : List.of("req-z5-a", "req-z5-b")) {
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
            assertThat(rows).isZero();
        }
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM observation_bundle WHERE bundle_key = 'bundle-z5'", String.class);
        assertThat(status).isEqualTo("OPEN");
    }
}
