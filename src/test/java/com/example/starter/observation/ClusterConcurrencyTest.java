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
 * 重复观测簇归并并发边界测试：真实并发提交，验证成员行锁串行化、归并与离线合并竞争不丢代次、
 * 同 requestId 并发重放只落一簇、clusterKey 唯一约束的并发兜底。
 */
@SpringBootTest
class ClusterConcurrencyTest {

    @Autowired
    private ObservationService observationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM cluster_field_source");
        jdbcTemplate.update("DELETE FROM cluster_member");
        jdbcTemplate.update("DELETE FROM duplicate_cluster");
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

    private void createObservation(String id, String requestId, String observedAt, String deviceId) {
        observationService.create(new CreateObservationRequest(
                requestId, id, "站点-" + id, "10.0", "备注-" + id,
                "SITE-A", "TEMP", observedAt, deviceId));
    }

    private ClusterMergeRequest mergeRequest(String requestId, String clusterKey, String canonicalId,
                                             List<String> memberKeys) {
        List<ClusterMergeRequest.MemberGeneration> members = new ArrayList<>();
        for (String key : memberKeys) {
            members.add(new ClusterMergeRequest.MemberGeneration(key, 1));
        }
        Map<String, ClusterMergeRequest.FieldSource> sources = new LinkedHashMap<>();
        sources.put("location", new ClusterMergeRequest.FieldSource(memberKeys.get(0)));
        sources.put("reading", new ClusterMergeRequest.FieldSource(memberKeys.get(0)));
        sources.put("note", new ClusterMergeRequest.FieldSource(memberKeys.get(0)));
        return new ClusterMergeRequest(requestId, clusterKey, canonicalId,
                members, sources, "reviewer-1");
    }

    private String outcome(Callable<?> callable) {
        try {
            callable.call();
            return "ok";
        } catch (ApiException e) {
            return String.valueOf(e.status().value());
        } catch (Exception e) {
            return "error";
        }
    }

    @Test
    void concurrentClustersOnOverlappingMembersYieldSingleWinner() throws Exception {
        createObservation("obs-1", "req-0", "2026-09-23T10:00:00Z", "dev-1");
        createObservation("obs-2", "req-1", "2026-09-23T10:00:10Z", "dev-2");
        createObservation("obs-3", "req-2", "2026-09-23T10:00:20Z", "dev-3");

        // 两个候选簇共享 obs-2：行锁串行化，先提交者成功，后到者看到成员已 MERGED，409
        List<Callable<String>> tasks = List.of(
                () -> outcome(() -> observationService.commitCluster(
                        mergeRequest("req-a", "cluster-a", "canon-a", List.of("obs-1", "obs-2")))),
                () -> outcome(() -> observationService.commitCluster(
                        mergeRequest("req-b", "cluster-b", "canon-b", List.of("obs-2", "obs-3")))));
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok", "409");
        Integer clusterCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM duplicate_cluster", Integer.class);
        assertThat(clusterCount).isEqualTo(1);
        // 恰好两个成员 MERGED（成功簇），第三个仍 ACTIVE
        Integer mergedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_current WHERE record_status = 'MERGED'", Integer.class);
        assertThat(mergedCount).isEqualTo(2);
        Integer activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_current WHERE record_status = 'ACTIVE' AND origin = 'RAW'",
                Integer.class);
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    void concurrentClusterAndOfflineMergeSerializeWithoutLostGeneration() throws Exception {
        createObservation("obs-1", "req-0", "2026-09-23T10:00:00Z", "dev-1");
        createObservation("obs-2", "req-1", "2026-09-23T10:00:10Z", "dev-2");

        List<Callable<String>> tasks = List.of(
                () -> outcome(() -> observationService.commitCluster(
                        mergeRequest("req-c", "cluster-c", "canon-c", List.of("obs-1", "obs-2")))),
                () -> outcome(() -> observationService.merge("obs-1",
                        new MergeObservationRequest("req-m", 1, "新站点", "10.0", "备注-obs-1"))));
        List<String> results = runConcurrently(tasks);

        // 归并先成功：离线合并撞 MERGED 409；离线合并先成功：归并撞冻结代次 409
        assertThat(results).contains("ok");
        assertThat(results).contains("409");

        ObservationResponse obs1 = observationService.getCurrent("obs-1");
        if ("ok".equals(results.get(0))) {
            // 归并成功：obs-1 保持 MERGED 且代次仍为 1，离线更新未生效
            assertThat(obs1.status()).isEqualTo("MERGED");
            assertThat(obs1.version()).isEqualTo(1);
            assertThat(obs1.location()).isEqualTo("站点-obs-1");
        } else {
            // 离线合并成功：obs-1 代次前进到 2，归并不落库
            assertThat(obs1.status()).isEqualTo("ACTIVE");
            assertThat(obs1.version()).isEqualTo(2);
            assertThat(obs1.location()).isEqualTo("新站点");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM duplicate_cluster", Integer.class)).isZero();
        }
    }

    @Test
    void concurrentSameRequestIdCommitsReplaySingleCluster() throws Exception {
        createObservation("obs-1", "req-0", "2026-09-23T10:00:00Z", "dev-1");
        createObservation("obs-2", "req-1", "2026-09-23T10:00:10Z", "dev-2");

        List<Callable<ObservationService.ClusterOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> observationService.commitCluster(
                    mergeRequest("req-same", "cluster-same", "canon-same", List.of("obs-1", "obs-2"))));
        }
        List<ObservationService.ClusterOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes).allMatch(outcome ->
                outcome.body().canonical().observationId().equals("canon-same"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM duplicate_cluster", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_current WHERE observation_id = 'canon-same'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-same'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentSameClusterKeyYieldsSingleWinner() throws Exception {
        createObservation("obs-1", "req-0", "2026-09-23T10:00:00Z", "dev-1");
        createObservation("obs-2", "req-1", "2026-09-23T10:00:10Z", "dev-2");
        createObservation("obs-3", "req-2", "2026-09-23T10:00:20Z", "dev-3");
        createObservation("obs-4", "req-3", "2026-09-23T10:00:30Z", "dev-4");

        // 不同成员集合、不同 requestId 抢同一个 clusterKey：唯一约束兜底，只允许一个成功
        List<Callable<String>> tasks = List.of(
                () -> outcome(() -> observationService.commitCluster(
                        mergeRequest("req-x", "cluster-x", "canon-x", List.of("obs-1", "obs-2")))),
                () -> outcome(() -> observationService.commitCluster(
                        mergeRequest("req-y", "cluster-x", "canon-y", List.of("obs-3", "obs-4")))));
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok", "409");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM duplicate_cluster WHERE cluster_key = 'cluster-x'",
                Integer.class)).isEqualTo(1);
        // 失败一侧不产生 canonical 主记录
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_current WHERE observation_id IN ('canon-x','canon-y')",
                Integer.class)).isEqualTo(1);
    }
}
