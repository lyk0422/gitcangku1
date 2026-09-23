package com.example.starter.observation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 墓碑恢复并发与幂等边界测试（真实 H2 内存库）：两次恢复竞争只生成一个新版本；
 * 恢复与离线合并、再次删除并发按提交顺序串行处理，失败方不增加代次、不留下恢复历史。
 */
@SpringBootTest
class RestoreConcurrencyTest {

    @Autowired
    private ObservationService observationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM observation_recovery");
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

    private String runCatching(Callable<?> callable) {
        try {
            callable.call();
            return "ok";
        } catch (ApiException e) {
            return String.valueOf(e.status().value());
        } catch (Exception e) {
            return "error:" + e.getClass().getSimpleName();
        }
    }

    /**
     * create v1(A) -> merge v2(B) -> delete v3（墓碑）。
     */
    private void seedDeletedAtV3(String observationId) {
        observationService.create(
                new CreateObservationRequest("req-create-" + observationId, observationId, "站点A", "1.0", "备注A"));
        observationService.merge(observationId,
                new MergeObservationRequest("req-merge-" + observationId, 1, "站点B", "2.0", "备注B"));
        observationService.delete(observationId,
                new DeleteObservationRequest("req-delete-" + observationId, 2));
    }

    @Test
    void concurrentRestoresSameRequestIdProduceSingleVersion() throws Exception {
        seedDeletedAtV3("obs-c1");

        List<Callable<ObservationService.WriteOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> observationService.restore("obs-c1",
                    new RestoreObservationRequest("req-restore-same", 3, 1, "并发恢复")));
        }
        List<ObservationService.WriteOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        assertThat(outcomes).allMatch(outcome -> outcome.body().version() == 4);
        assertThat(outcomes).allMatch(outcome -> outcome.body().generation() == 2);

        ObservationResponse current = observationService.getCurrent("obs-c1");
        assertThat(current.version()).isEqualTo(4);
        assertThat(current.generation()).isEqualTo(2);
        assertThat(current.deleted()).isFalse();
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-c1'", Integer.class);
        assertThat(versionRows).isEqualTo(4);
        Integer recoveryRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_recovery WHERE observation_id = 'obs-c1'", Integer.class);
        assertThat(recoveryRows).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-restore-same'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentRestoresDifferentRequestIdsYieldSingleSuccess() throws Exception {
        seedDeletedAtV3("obs-c2");

        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final String requestId = "req-restore-diff-" + i;
            tasks.add(() -> runCatching(() -> observationService.restore("obs-c2",
                    new RestoreObservationRequest(requestId, 3, 1, "并发恢复"))));
        }
        List<String> results = runConcurrently(tasks);

        // 行锁串行化：第一个恢复成功，之后当前已非墓碑，其余返回 409；只生成一个新版本
        assertThat(results).filteredOn("ok"::equals).hasSize(1);
        assertThat(results).filteredOn("409"::equals).hasSize(2);

        ObservationResponse current = observationService.getCurrent("obs-c2");
        assertThat(current.version()).isEqualTo(4);
        assertThat(current.generation()).isEqualTo(2);
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-c2'", Integer.class);
        assertThat(versionRows).isEqualTo(4);
        Integer recoveryRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_recovery WHERE observation_id = 'obs-c2'", Integer.class);
        assertThat(recoveryRows).isEqualTo(1);
    }

    @Test
    void restoreAndOfflineMergeSerializeByCommitOrder() throws Exception {
        seedDeletedAtV3("obs-c3");

        List<Callable<String>> tasks = List.of(
                () -> runCatching(() -> observationService.restore("obs-c3",
                        new RestoreObservationRequest("req-restore-merge", 3, 1, "恢复"))),
                () -> runCatching(() -> observationService.merge("obs-c3",
                        new MergeObservationRequest("req-merge-offline", 1, "站点X", "9.0", "离线备注"))));
        List<String> results = runConcurrently(tasks);

        // 恢复必然成功；离线合并必定失败：先恢复则旧代次基线 409，先合并则墓碑 410
        assertThat(results).contains("ok");
        assertThat(results).anyMatch(result -> result.equals("409") || result.equals("410"));
        assertThat(results).doesNotContain("error:" );

        ObservationResponse current = observationService.getCurrent("obs-c3");
        assertThat(current.version()).isEqualTo(4);
        assertThat(current.generation()).isEqualTo(2);
        assertThat(current.deleted()).isFalse();
        // 删除前的离线修改不得灌入恢复后的记录
        assertThat(current.location()).isEqualTo("站点A");
        assertThat(current.note()).isEqualTo("备注A");
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-c3'", Integer.class);
        assertThat(versionRows).isEqualTo(4);
        Integer recoveryRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_recovery WHERE observation_id = 'obs-c3'", Integer.class);
        assertThat(recoveryRows).isEqualTo(1);
        // 失败的离线合并不占键
        Integer mergeRequestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-merge-offline'", Integer.class);
        assertThat(mergeRequestRows).isZero();
    }

    @Test
    void restoreAndAnotherDeleteSerializeByCommitOrder() throws Exception {
        seedDeletedAtV3("obs-c4");

        List<Callable<String>> tasks = List.of(
                () -> runCatching(() -> observationService.restore("obs-c4",
                        new RestoreObservationRequest("req-restore-delete", 3, 1, "恢复"))),
                // 基于墓碑版本的“再次删除”：恢复先提交则版本不匹配 409，删除先拿到锁则墓碑 410
                () -> runCatching(() -> observationService.delete("obs-c4",
                        new DeleteObservationRequest("req-delete-again", 3))));
        List<String> results = runConcurrently(tasks);

        assertThat(results).contains("ok");
        assertThat(results).anyMatch(result -> result.equals("409") || result.equals("410"));

        ObservationResponse current = observationService.getCurrent("obs-c4");
        assertThat(current.version()).isEqualTo(4);
        assertThat(current.generation()).isEqualTo(2);
        assertThat(current.deleted()).isFalse();
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-c4'", Integer.class);
        assertThat(versionRows).isEqualTo(4);
        Integer recoveryRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_recovery WHERE observation_id = 'obs-c4'", Integer.class);
        assertThat(recoveryRows).isEqualTo(1);
        Integer deleteRequestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-delete-again'", Integer.class);
        assertThat(deleteRequestRows).isZero();
    }

    @Test
    void failedRestoreDoesNotIncrementGenerationOrLeaveHistory() throws Exception {
        seedDeletedAtV3("obs-c5");

        // 来源是墓碑，恢复 422 回滚：代次不变、无新版本、无恢复历史、不占键
        String result = runCatching(() -> observationService.restore("obs-c5",
                new RestoreObservationRequest("req-restore-fail", 3, 3, "来源非法")));
        assertThat(result).isEqualTo("422");

        ObservationResponse current = observationService.getCurrent("obs-c5");
        assertThat(current.version()).isEqualTo(3);
        assertThat(current.generation()).isEqualTo(1);
        assertThat(current.deleted()).isTrue();
        Integer recoveryRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_recovery WHERE observation_id = 'obs-c5'", Integer.class);
        assertThat(recoveryRows).isZero();
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-restore-fail'", Integer.class);
        assertThat(requestRows).isZero();

        // 同一 requestId 随后可用于一次成功恢复，代次正常加一
        observationService.restore("obs-c5",
                new RestoreObservationRequest("req-restore-fail", 3, 1, "恢复"));
        ObservationResponse after = observationService.getCurrent("obs-c5");
        assertThat(after.version()).isEqualTo(4);
        assertThat(after.generation()).isEqualTo(2);
    }
}
