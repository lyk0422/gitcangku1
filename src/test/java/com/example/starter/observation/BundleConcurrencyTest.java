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
 * 关联簇联合裁决并发边界测试（真实 H2 内存库，真实并发线程）：
 * 同簇并发裁决按提交顺序只成功一次、裁决与删除竞争不出现半个簇已裁决、
 * 同 requestId 并发只产生一次裁决、并发建簇不允许观测重复加入。
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
        jdbcTemplate.update("DELETE FROM field_conflict");
        jdbcTemplate.update("DELETE FROM bundle_arbitration");
        jdbcTemplate.update("DELETE FROM observation_bundle_member");
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

    private void createObservation(String requestId, String id, String location, String reading, String note) {
        observationService.create(new CreateObservationRequest(
                requestId, id, "survey-1", location, reading, note));
    }

    /**
     * 建簇：obs-a 当前 v2（站点B/2.0），离线基于 v1 改 站点C/2.5 产生 location+reading 冲突；
     * obs-b 当前 v1 无冲突。
     */
    private void setupBundleWithTwoMembers(String tag) {
        createObservation("req-" + tag + "-c0", "obs-" + tag + "-a", "站点A", "1.0", "备注");
        observationService.merge("obs-" + tag + "-a", new MergeObservationRequest(
                "req-" + tag + "-c1", 1, "站点B", "2.0", "备注"));
        createObservation("req-" + tag + "-c2", "obs-" + tag + "-b", "站点Z", "9.0", "备注");
        BundleMemberItem memberA = new BundleMemberItem(
                "obs-" + tag + "-a", 1, "站点C", "2.5", "备注");
        BundleMemberItem memberB = new BundleMemberItem(
                "obs-" + tag + "-b", 1, "站点Z", "9.0", "备注");
        bundleService.createBundle(new CreateBundleRequest(
                "req-" + tag + "-bundle", "bundle-" + tag, "survey-1", List.of(),
                List.of(memberA, memberB), "reviewer-a"));
    }

    private ArbitrateBundleRequest arbitrationRequest(String tag, String requestId,
                                                      int versionA, int versionB) {
        Map<String, Integer> versions = new LinkedHashMap<>();
        versions.put("obs-" + tag + "-a", versionA);
        versions.put("obs-" + tag + "-b", versionB);
        List<FieldDecision> decisions = List.of(
                new FieldDecision("obs-" + tag + "-a", "location", ArbitrationSource.REMOTE, null, null),
                new FieldDecision("obs-" + tag + "-a", "reading", ArbitrationSource.REMOTE, null, null));
        return new ArbitrateBundleRequest(requestId, versions, decisions, "reviewer-a");
    }

    @Test
    void concurrentArbitrationsOnSameBundleExactlyOneSucceeds() throws Exception {
        setupBundleWithTwoMembers("x1");

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        bundleService.arbitrate("bundle-x1", arbitrationRequest("x1", "req-x1-a", 2, 1));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        bundleService.arbitrate("bundle-x1", arbitrationRequest("x1", "req-x1-b", 2, 1));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok", "409");
        // 不允许半个簇已裁决：冲突要么全部关闭，要么全部 OPEN；裁决记录至多一条
        Integer resolved = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM field_conflict WHERE bundle_key = 'bundle-x1' AND status = 'RESOLVED'",
                Integer.class);
        Integer open = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM field_conflict WHERE bundle_key = 'bundle-x1' AND status = 'OPEN'",
                Integer.class);
        Integer arbitrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bundle_arbitration WHERE bundle_key = 'bundle-x1'", Integer.class);
        assertThat(arbitrations).isEqualTo(1);
        assertThat(resolved).isEqualTo(2);
        assertThat(open).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM observation_bundle WHERE bundle_key = 'bundle-x1'", String.class))
                .isEqualTo("CLOSED");
        ObservationResponse currentA = observationService.getCurrent("obs-x1-a");
        assertThat(currentA.version()).isEqualTo(3);
        assertThat(currentA.location()).isEqualTo("站点C");
        // obs-b 无变化，仍是 v1
        assertThat(observationService.getCurrent("obs-x1-b").version()).isEqualTo(1);
        Integer versionsA = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-x1-a'", Integer.class);
        assertThat(versionsA).isEqualTo(3);
    }

    @Test
    void concurrentArbitrationAndDeleteNeverLeaveHalfArbitratedCluster() throws Exception {
        setupBundleWithTwoMembers("x2");

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        bundleService.arbitrate("bundle-x2", arbitrationRequest("x2", "req-x2-arb", 2, 1));
                        return "arbitrate-ok";
                    } catch (ApiException e) {
                        return "arbitrate-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.delete("obs-x2-b",
                                new DeleteObservationRequest("req-x2-del", 1));
                        return "delete-ok";
                    } catch (ApiException e) {
                        return "delete-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        ObservationResponse currentB = observationService.getCurrent("obs-x2-b");
        Integer arbitrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bundle_arbitration WHERE bundle_key = 'bundle-x2'", Integer.class);
        Integer openConflicts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM field_conflict WHERE bundle_key = 'bundle-x2' AND status = 'OPEN'",
                Integer.class);
        if (results.contains("arbitrate-ok")) {
            // 裁决先提交：簇整体关闭，全部冲突 RESOLVED；删除随后按 v1 成功或因版本竞争失败都不影响簇完整性
            assertThat(arbitrations).isEqualTo(1);
            assertThat(openConflicts).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM observation_bundle WHERE bundle_key = 'bundle-x2'", String.class))
                    .isEqualTo("CLOSED");
            assertThat(observationService.getCurrent("obs-x2-a").location()).isEqualTo("站点C");
        } else {
            // 删除先提交：obs-b 变墓碑 v2，裁决因 expectedVersion/状态变化失败并整体回滚
            assertThat(results).contains("arbitrate-409");
            assertThat(arbitrations).isZero();
            assertThat(openConflicts).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM observation_bundle WHERE bundle_key = 'bundle-x2'", String.class))
                    .isEqualTo("OPEN");
            assertThat(currentB.deleted()).isTrue();
            assertThat(currentB.version()).isEqualTo(2);
            // obs-a 未被裁决改动
            ObservationResponse currentA = observationService.getCurrent("obs-x2-a");
            assertThat(currentA.version()).isEqualTo(2);
            assertThat(currentA.location()).isEqualTo("站点B");
        }
    }

    @Test
    void concurrentSameRequestIdArbitrationsProduceSingleResult() throws Exception {
        setupBundleWithTwoMembers("x3");

        List<Callable<BundleService.ArbitrationOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> bundleService.arbitrate(
                    "bundle-x3", arbitrationRequest("x3", "req-x3-same", 2, 1)));
        }
        List<BundleService.ArbitrationOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        assertThat(outcomes).allMatch(outcome ->
                outcome.body().after().get(0).version() == 3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bundle_arbitration WHERE request_id = 'req-x3-same'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-x3-same'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-x3-a'", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void concurrentBundleCreationsRejectDuplicateMembership() throws Exception {
        createObservation("req-x4-a", "obs-x4-a", "站点A", "1.0", "备注");
        createObservation("req-x4-b", "obs-x4-b", "站点A", "1.0", "备注");
        createObservation("req-x4-c", "obs-x4-c", "站点A", "1.0", "备注");

        BundleMemberItem memberOfA1 = new BundleMemberItem("obs-x4-a", 1, "站点A", "1.0", "备注");
        BundleMemberItem memberOfB = new BundleMemberItem("obs-x4-b", 1, "站点A", "1.0", "备注");
        BundleMemberItem memberOfA2 = new BundleMemberItem("obs-x4-a", 1, "站点A", "1.0", "备注");
        BundleMemberItem memberOfC = new BundleMemberItem("obs-x4-c", 1, "站点A", "1.0", "备注");

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        bundleService.createBundle(new CreateBundleRequest(
                                "req-x4-1", "bundle-x4-1", "survey-1", List.of(),
                                List.of(memberOfA1, memberOfB), "reviewer-a"));
                        return "ok-1";
                    } catch (ApiException e) {
                        return "fail-1-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        bundleService.createBundle(new CreateBundleRequest(
                                "req-x4-2", "bundle-x4-2", "survey-1", List.of(),
                                List.of(memberOfA2, memberOfC), "reviewer-a"));
                        return "ok-2";
                    } catch (ApiException e) {
                        return "fail-2-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 恰好一个簇建立成功；另一个因 obs-x4-a 已在未结簇中而 409
        assertThat(results).anyMatch(result -> result.startsWith("ok-"));
        assertThat(results).anyMatch(result -> result.endsWith("-409"));
        Integer bundleCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_bundle", Integer.class);
        assertThat(bundleCount).isEqualTo(1);
        // obs-x4-a 只挂在成功的簇上，没有被两个簇同时登记
        Integer memberRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_bundle_member WHERE observation_id = 'obs-x4-a'",
                Integer.class);
        assertThat(memberRows).isEqualTo(1);
        String attached = jdbcTemplate.queryForObject(
                "SELECT open_bundle_key FROM observation_current WHERE observation_id = 'obs-x4-a'",
                String.class);
        String winningBundle = results.stream().filter(r -> r.startsWith("ok-")).findFirst().orElseThrow()
                .substring("ok-".length());
        assertThat(attached).isEqualTo("bundle-x4-" + winningBundle);
    }
}
