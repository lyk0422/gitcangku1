package com.example.starter.observation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
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
 * 重复观测簇归并并发边界测试：真实并发提交，验证重叠簇串行化、簇与离线合并/删除竞争不丢代次、
 * 同 requestId 并发重放只产生一个簇。使用真实 H2 内存库（MODE=MySQL）。
 */
@SpringBootTest
class ClusterMergeConcurrencyTest {

    private static final String SITE = "S1";
    private static final String TYPE = "T1";
    private static final Instant T0 = Instant.parse("2026-09-23T11:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-23T11:00:20Z");
    private static final Instant T2 = Instant.parse("2026-09-23T11:00:40Z");
    private static final Instant T3 = Instant.parse("2026-09-23T11:00:50Z");

    @Autowired
    private ObservationService observationService;

    @Autowired
    private ClusterMergeService clusterMergeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM cluster_field_source");
        jdbcTemplate.update("DELETE FROM cluster_member");
        jdbcTemplate.update("DELETE FROM duplicate_cluster");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
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

    private void createMember(String id, String requestId, Instant observedAt) {
        observationService.create(new CreateObservationRequest(requestId, id,
                "L-" + id, "1.000", "N-" + id, SITE, TYPE, observedAt, "dev-" + id));
    }

    private ClusterMemberRef ref(String key) {
        return new ClusterMemberRef(key, 1);
    }

    private Map<String, String> sources(String key) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("location", key);
        map.put("reading", key);
        map.put("note", key);
        return map;
    }

    private ClusterMergeRequest clusterRequest(String requestId, String clusterKey, String canonicalKey,
                                               List<String> memberKeys) {
        return new ClusterMergeRequest(requestId, clusterKey, canonicalKey,
                memberKeys.stream().map(this::ref).toList(), sources(memberKeys.get(0)));
    }

    private String runCluster(ClusterMergeRequest request) {
        try {
            clusterMergeService.merge(request);
            return "ok";
        } catch (ApiException e) {
            return String.valueOf(e.status().value());
        }
    }

    private int countSql(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    private String mergeStatus(String id) {
        return jdbcTemplate.queryForObject(
                "SELECT merge_status FROM observation_current WHERE observation_id = ?", String.class, id);
    }

    @Test
    void overlappingClustersSerializeExactlyOneWins() throws Exception {
        createMember("m1", "req-1", T0);
        createMember("m2", "req-2", T1);
        createMember("m3", "req-3", T2);

        // 两个候选簇共享成员 m2：只有一个能成功，另一个因 m2 已 MERGED 而 409
        List<Callable<String>> tasks = List.of(
                () -> runCluster(clusterRequest("req-cl-a", "cl-a", "canon-a", List.of("m1", "m2"))),
                () -> runCluster(clusterRequest("req-cl-b", "cl-b", "canon-b", List.of("m2", "m3"))));
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok", "409");
        assertThat(countSql("SELECT COUNT(*) FROM duplicate_cluster")).isEqualTo(1);
        // 成功簇的两个成员为 MERGED；落败簇的非共享成员必须保持 ACTIVE（无部分提交）
        String winner = results.get(0).equals("ok") ? "a" : "b";
        if (winner.equals("a")) {
            assertThat(mergeStatus("m1")).isEqualTo("MERGED");
            assertThat(mergeStatus("m2")).isEqualTo("MERGED");
            assertThat(mergeStatus("m3")).isEqualTo("ACTIVE");
            assertThat(countSql("SELECT COUNT(*) FROM observation_current WHERE observation_id = 'canon-a'"))
                    .isEqualTo(1);
            assertThat(countSql("SELECT COUNT(*) FROM observation_current WHERE observation_id = 'canon-b'"))
                    .isZero();
        } else {
            assertThat(mergeStatus("m2")).isEqualTo("MERGED");
            assertThat(mergeStatus("m3")).isEqualTo("MERGED");
            assertThat(mergeStatus("m1")).isEqualTo("ACTIVE");
        }
    }

    @Test
    void clusterAgainstOfflineMergeNeverLosesGeneration() throws Exception {
        createMember("m1", "req-1", T0);
        createMember("m2", "req-2", T1);

        List<Callable<String>> tasks = List.of(
                () -> runCluster(clusterRequest("req-cl", "cl-c", "canon-c", List.of("m1", "m2"))),
                () -> {
                    try {
                        observationService.merge("m1",
                                new MergeObservationRequest("req-offline", 1, "L-new", "2.500", "N-m1"));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 恰好一个成功：簇先则离线合并 409（m1 已 MERGED）；离线合并先则簇 409（m1 generation 变为 2）
        assertThat(results).containsExactlyInAnyOrder("ok", "409");

        Integer versionRows = countSql("SELECT COUNT(*) FROM observation_version WHERE observation_id = 'm1'");
        if (mergeStatus("m1").equals("MERGED")) {
            // 簇先生效：m1 冻结在 generation 1，离线更新被拒，内容仍是原值
            assertThat(versionRows).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT reading FROM observation_current WHERE observation_id = 'm1'", String.class))
                    .isEqualTo("1.000");
            assertThat(countSql("SELECT COUNT(*) FROM duplicate_cluster")).isEqualTo(1);
        } else {
            // 离线合并先生效：m1 前进到 generation 2 且保持 ACTIVE，簇整体回滚
            assertThat(mergeStatus("m1")).isEqualTo("ACTIVE");
            assertThat(versionRows).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT version FROM observation_current WHERE observation_id = 'm1'", Integer.class))
                    .isEqualTo(2);
            assertThat(mergeStatus("m2")).isEqualTo("ACTIVE");
            assertThat(countSql("SELECT COUNT(*) FROM duplicate_cluster")).isZero();
            assertThat(countSql("SELECT COUNT(*) FROM observation_current WHERE observation_id = 'canon-c'"))
                    .isZero();
        }
    }

    @Test
    void clusterAgainstDeleteSerializes() throws Exception {
        createMember("m1", "req-1", T0);
        createMember("m2", "req-2", T1);

        List<Callable<String>> tasks = List.of(
                () -> runCluster(clusterRequest("req-cl-d", "cl-d", "canon-d", List.of("m1", "m2"))),
                () -> {
                    try {
                        observationService.delete("m1", new DeleteObservationRequest("req-del", 1));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok", "409");
        boolean deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM observation_current WHERE observation_id = 'm1'", Boolean.class);
        if (Boolean.TRUE.equals(deleted)) {
            // 删除先生效：簇必须整体回滚，m2 不被归并
            assertThat(countSql("SELECT COUNT(*) FROM duplicate_cluster")).isZero();
            assertThat(mergeStatus("m2")).isEqualTo("ACTIVE");
        } else {
            // 簇先生效：m1 已 MERGED，删除被拒，无墓碑
            assertThat(mergeStatus("m1")).isEqualTo("MERGED");
            assertThat(countSql("SELECT COUNT(*) FROM observation_version WHERE observation_id = 'm1'"))
                    .isEqualTo(1);
        }
    }

    @Test
    void concurrentSameRequestIdCreatesSingleCluster() throws Exception {
        createMember("m1", "req-1", T0);
        createMember("m2", "req-2", T1);

        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> runCluster(
                    clusterRequest("req-cl-idem", "cl-idem", "canon-idem", List.of("m1", "m2"))));
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).allMatch("ok"::equals);
        assertThat(countSql("SELECT COUNT(*) FROM duplicate_cluster WHERE cluster_key = 'cl-idem'")).isEqualTo(1);
        assertThat(countSql("SELECT COUNT(*) FROM cluster_member WHERE cluster_key = 'cl-idem'")).isEqualTo(2);
        assertThat(countSql("SELECT COUNT(*) FROM cluster_field_source WHERE cluster_key = 'cl-idem'"))
                .isEqualTo(3);
        assertThat(countSql("SELECT COUNT(*) FROM observation_current WHERE observation_id = 'canon-idem'"))
                .isEqualTo(1);
        assertThat(countSql("SELECT COUNT(*) FROM request_log WHERE request_id = 'req-cl-idem'")).isEqualTo(1);
    }
}
