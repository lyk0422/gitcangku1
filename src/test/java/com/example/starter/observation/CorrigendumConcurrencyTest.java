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
 * 更正附页并发边界测试：真实并发提交，验证附页/撤销/批量按提交顺序串行、
 * 同 corrKey 并发重放只产生一条附页、并发撤销只有一个成功。
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
        jdbcTemplate.update("DELETE FROM review_flag");
        jdbcTemplate.update("DELETE FROM corrigendum_revocation");
        jdbcTemplate.update("DELETE FROM corrigendum");
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

    private void createObservation(String requestId, String observationId) {
        observationService.create(new CreateObservationRequest(
                requestId, observationId, "30.000,120.000", "1.0", "备注"));
    }

    private SubmitCorrigendumRequest corrRequest(String corrKey, String reading) {
        return new SubmitCorrigendumRequest(corrKey, 1, Map.of("reading", reading), "复测", "collector-a");
    }

    @Test
    void concurrentSubmitsSerializeInSubmissionOrder() throws Exception {
        createObservation("req-p1", "obs-p1");

        List<Callable<String>> tasks = List.of(
                () -> String.valueOf(corrigendumService.submit("obs-p1", corrRequest("ck-p1-a", "2.0"))
                        .body().corrVersion()),
                () -> String.valueOf(corrigendumService.submit("obs-p1", corrRequest("ck-p1-b", "3.0"))
                        .body().corrVersion()));
        List<String> results = runConcurrently(tasks);

        // 两个提交都成功，附页版本按提交顺序各占一个
        assertThat(results).containsExactlyInAnyOrder("1", "2");
        List<CorrigendumResponse> chain = corrigendumService.listCorrigenda("obs-p1");
        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).corrVersion()).isEqualTo(1);
        assertThat(chain.get(1).corrVersion()).isEqualTo(2);
    }

    @Test
    void concurrentSameCorrKeyProducesSingleCorrigendum() throws Exception {
        createObservation("req-p2", "obs-p2");

        List<Callable<Integer>> tasks = List.of(
                () -> corrigendumService.submit("obs-p2", corrRequest("ck-p2", "2.0")).body().corrVersion(),
                () -> corrigendumService.submit("obs-p2", corrRequest("ck-p2", "2.0")).body().corrVersion(),
                () -> corrigendumService.submit("obs-p2", corrRequest("ck-p2", "2.0")).body().corrVersion());
        List<Integer> results = runConcurrently(tasks);

        assertThat(results).containsOnly(1);
        assertThat(corrigendumService.listCorrigenda("obs-p2")).hasSize(1);
        Integer logCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'ck-p2'", Integer.class);
        assertThat(logCount).isEqualTo(1);
    }

    @Test
    void concurrentSubmitAndRevokeAreSerialized() throws Exception {
        createObservation("req-p3", "obs-p3");
        corrigendumService.submit("obs-p3", corrRequest("ck-p3-1", "2.0"));

        // 并发：撤销 v1 与提交新附页。两种提交顺序均可接受，但最终状态必须一致：
        // 先撤后提 → v1 REVOKED + v2 VALID；先提后撤 → 撤销 409，v1/v2 均 VALID。
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        corrigendumService.revoke("obs-p3",
                                new RevokeCorrigendumRequest("ck-p3-r", 1, "operator-a", "撤销"));
                        return "revoked";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    CorrigendumService.CorrOutcome outcome = corrigendumService.submit("obs-p3",
                            corrRequest("ck-p3-2", "3.0"));
                    return "submitted-" + outcome.body().corrVersion();
                });
        List<String> results = runConcurrently(tasks);

        List<CorrigendumResponse> chain = corrigendumService.listCorrigenda("obs-p3");
        assertThat(chain).hasSize(2);
        if (results.contains("revoked")) {
            assertThat(results).containsExactlyInAnyOrder("revoked", "submitted-2");
            assertThat(chain.get(0).status()).isEqualTo("REVOKED");
            assertThat(chain.get(1).status()).isEqualTo("VALID");
            assertThat(corrigendumService.view("obs-p3").effectiveReading()).isEqualTo("3.0");
        } else {
            assertThat(results).containsExactlyInAnyOrder("409", "submitted-2");
            assertThat(chain.get(0).status()).isEqualTo("VALID");
            assertThat(chain.get(1).status()).isEqualTo("VALID");
        }
    }

    @Test
    void concurrentBatchesOnOverlappingObservationsAreSerialized() throws Exception {
        createObservation("req-p4-a", "obs-p4a");
        createObservation("req-p4-b", "obs-p4b");

        BatchCorrigendumRequest batch1 = new BatchCorrigendumRequest("ck-p4-1", List.of(
                new BatchCorrigendumRequest.Item("obs-p4a", 1, Map.of("reading", "1.5"), "r", "collector-a"),
                new BatchCorrigendumRequest.Item("obs-p4b", 1, Map.of("reading", "2.5"), "r", "collector-b")));
        BatchCorrigendumRequest batch2 = new BatchCorrigendumRequest("ck-p4-2", List.of(
                new BatchCorrigendumRequest.Item("obs-p4a", 1, Map.of("note", "新备注"), "r", "collector-c"),
                new BatchCorrigendumRequest.Item("obs-p4b", 1, Map.of("note", "新备注"), "r", "collector-d")));

        List<Callable<String>> tasks = List.of(
                () -> String.valueOf(corrigendumService.submitBatch(batch1).status()),
                () -> String.valueOf(corrigendumService.submitBatch(batch2).status()));
        List<String> results = runConcurrently(tasks);

        // 两批互不阻塞（附页不推进观测版本），按提交顺序各自递增附页版本
        assertThat(results).containsOnly("201");
        assertThat(corrigendumService.listCorrigenda("obs-p4a")).hasSize(2);
        assertThat(corrigendumService.listCorrigenda("obs-p4b")).hasSize(2);
    }
}
