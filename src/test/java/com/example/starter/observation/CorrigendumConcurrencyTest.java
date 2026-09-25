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
 * 附页并发与幂等边界测试：附页/合并/撤销并发按提交顺序经行锁串行化裁决，
 * 同 corrKey 并发重放只占一键，失败不产生半成品状态。
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
        jdbcTemplate.update("DELETE FROM re_review_marker");
        jdbcTemplate.update("DELETE FROM corrigendum_revocation");
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

    private void createObservation(String id, String requestId) {
        observationService.create(new CreateObservationRequest(requestId, id, "站点A", "1.0", "初始备注"));
    }

    private CorrigendumRequest corrRequest(String corrKey, String note) {
        return new CorrigendumRequest(corrKey, 1, Map.of("note", note), "并发更正", "collector-a");
    }

    @Test
    void concurrentSubmitsSerializeCorrVersionsWithoutGaps() throws Exception {
        createObservation("obs-p1", "req-p0");

        // 4 个不同 corrKey 并发提交：按提交顺序串行裁决，附页版本连续递增 1..4
        List<Callable<CorrigendumService.CorrOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int index = i;
            tasks.add(() -> corrigendumService.submit("obs-p1",
                    corrRequest("ck-p" + index, "并发备注" + index)));
        }
        List<CorrigendumService.CorrOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes.stream().map(outcome -> outcome.body().corrVersion()))
                .containsExactlyInAnyOrder(1, 2, 3, 4);
        Integer corrRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_corrigendum WHERE observation_id = 'obs-p1'", Integer.class);
        assertThat(corrRows).isEqualTo(4);
        // 原始观测不被附页覆盖
        assertThat(observationService.getCurrent("obs-p1").version()).isEqualTo(1);
    }

    @Test
    void concurrentSameCorrKeyReplaysSingleCorrigendum() throws Exception {
        createObservation("obs-p2", "req-p1");

        // 同一 corrKey、相同参数并发提交 4 次：全部返回同一附页，只产生一张附页
        List<Callable<CorrigendumService.CorrOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> corrigendumService.submit("obs-p2", corrRequest("ck-shared", "并发重放")));
        }
        List<CorrigendumService.CorrOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes).allMatch(outcome -> outcome.body().corrVersion() == 1);
        Integer corrRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_corrigendum WHERE observation_id = 'obs-p2'", Integer.class);
        assertThat(corrRows).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'ck-shared'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentRevokeAndSubmitAreSerializedInSubmissionOrder() throws Exception {
        createObservation("obs-p3", "req-p2");
        corrigendumService.submit("obs-p3", corrRequest("ck-p3-base", "待撤销附页"));

        // 并发撤销 v1 与提交新附页：行锁串行化后两种顺序都合法
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        corrigendumService.revoke("obs-p3",
                                new CorrigendumRevokeRequest("req-p3-revoke", 1, "operator-a"));
                        return "revoke-ok";
                    } catch (ApiException e) {
                        return "revoke-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        corrigendumService.submit("obs-p3", corrRequest("ck-p3-new", "并发新附页"));
                        return "submit-ok";
                    } catch (ApiException e) {
                        return "submit-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 新附页总是成功（版本 2）；撤销仅在先于提交时成功，否则 v1 已非最新有效附页而 409
        assertThat(results).contains("submit-ok");
        assertThat(results).containsAnyOf("revoke-ok", "revoke-409");
        Integer corrRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_corrigendum WHERE observation_id = 'obs-p3'", Integer.class);
        assertThat(corrRows).isEqualTo(2);
        CorrigendumRecord latestValid = corrigendumService.listCorrigenda("obs-p3").stream()
                .filter(record -> !record.revoked())
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(latestValid.corrVersion()).isEqualTo(2);
        Integer revocationRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM corrigendum_revocation WHERE observation_id = 'obs-p3'", Integer.class);
        assertThat(revocationRows).isEqualTo(results.contains("revoke-ok") ? 1 : 0);
    }

    @Test
    void concurrentMergeAndCorrigendumBothApplyWithoutPartialState() throws Exception {
        createObservation("obs-p4", "req-p3");

        // 合并与附页并发：按提交顺序串行裁决，两者都成功且互不覆盖
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        observationService.merge("obs-p4",
                                new MergeObservationRequest("req-p4-merge", 1, "站点A", "1.0", "合并备注"));
                        return "merge-ok";
                    } catch (ApiException e) {
                        return "merge-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        corrigendumService.submit("obs-p4",
                                new CorrigendumRequest("ck-p4", 1, Map.of("location", "站点Z"),
                                        "并发更正", "collector-a"));
                        return "submit-ok";
                    } catch (ApiException e) {
                        return "submit-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("merge-ok", "submit-ok");
        // 合并推进观测版本，附页不改写原始观测
        ObservationResponse current = observationService.getCurrent("obs-p4");
        assertThat(current.version()).isEqualTo(2);
        assertThat(current.note()).isEqualTo("合并备注");
        assertThat(current.location()).isEqualTo("站点A");
        // 导出视图（未裁决）应用最新有效附页
        ExportViewResponse export = corrigendumService.exportView("obs-p4");
        assertThat(export.location()).isEqualTo("站点Z");
        assertThat(export.note()).isEqualTo("合并备注");
        assertThat(export.appliedCorrigendumVersion()).isEqualTo(1);
    }
}
