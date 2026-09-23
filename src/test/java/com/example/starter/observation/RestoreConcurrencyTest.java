package com.example.starter.observation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * 墓碑恢复并发边界测试：真实并发提交，验证两次恢复竞争只生成一个新版本、
 * 恢复与离线合并/再次删除按提交顺序串行处理且失败不增加代次或留下恢复历史、
 * 同 requestId 并发恢复只占一键。
 */
@SpringBootTest
class RestoreConcurrencyTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-23T09:00:00Z");

    @Autowired
    private ObservationService observationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM restore_history");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        Mockito.when(clock.instant()).thenReturn(FIXED_NOW);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
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

    private String codeOf(ApiException e) {
        return String.valueOf(e.status().value());
    }

    private void createObservation(String id, String requestId, String location, String reading, String note) {
        observationService.create(new CreateObservationRequest(requestId, id, location, reading, note));
    }

    @Test
    void concurrentRestoresCreateOnlyOneNewVersion() throws Exception {
        createObservation("obs-r1", "req-r0", "站点A", "1.0", "备注");
        observationService.delete("obs-r1", new DeleteObservationRequest("req-rd", 1));

        // 两个不同 requestId 的恢复并发，期望版本都为墓碑版本 2：只有一个成功，另一个 409
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        ObservationService.WriteOutcome o = observationService.restore("obs-r1",
                                new RestoreObservationRequest("req-r1", 2, 1, "恢复原因1"));
                        return "ok-" + o.body().version();
                    } catch (ApiException e) {
                        return codeOf(e);
                    }
                },
                () -> {
                    try {
                        ObservationService.WriteOutcome o = observationService.restore("obs-r1",
                                new RestoreObservationRequest("req-r2", 2, 1, "恢复原因2"));
                        return "ok-" + o.body().version();
                    } catch (ApiException e) {
                        return codeOf(e);
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok-3", "409");
        ObservationResponse current = observationService.getCurrent("obs-r1");
        assertThat(current.version()).isEqualTo(3);
        assertThat(current.generation()).isEqualTo(2);
        assertThat(current.deleted()).isFalse();
        // 只生成一个新版本与一条恢复历史；失败方未增加代次
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-r1'", Integer.class);
        assertThat(versionRows).isEqualTo(3);
        Integer restoreRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM restore_history WHERE observation_id = 'obs-r1'", Integer.class);
        assertThat(restoreRows).isEqualTo(1);
        Integer currentGeneration = jdbcTemplate.queryForObject(
                "SELECT generation FROM observation_current WHERE observation_id = 'obs-r1'", Integer.class);
        assertThat(currentGeneration).isEqualTo(2);
    }

    @Test
    void concurrentRestoreAndOfflineMergeSerializeByCommitOrder() throws Exception {
        createObservation("obs-r2", "req-m0", "站点A", "1.0", "备注");
        observationService.delete("obs-r2", new DeleteObservationRequest("req-md", 1));

        // 恢复（墓碑版本 2 -> 活版本 3，代次 2）与删除前离线合并（基于代次 1 基线版本 1）并发
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        observationService.restore("obs-r2",
                                new RestoreObservationRequest("req-mr", 2, 1, "恢复"));
                        return "restore-ok";
                    } catch (ApiException e) {
                        return "restore-" + codeOf(e);
                    }
                },
                () -> {
                    try {
                        observationService.merge("obs-r2",
                                new MergeObservationRequest("req-mm", 1, "站点B", "2.0", "离线备注"));
                        return "merge-ok";
                    } catch (ApiException e) {
                        return "merge-" + codeOf(e);
                    }
                });
        List<String> results = runConcurrently(tasks);

        ObservationResponse current = observationService.getCurrent("obs-r2");
        if (results.contains("restore-ok")) {
            // 恢复已提交：旧代次离线合并必须 409，不能灌入恢复后的记录
            assertThat(results).contains("merge-409");
            assertThat(current.deleted()).isFalse();
            assertThat(current.version()).isEqualTo(3);
            assertThat(current.generation()).isEqualTo(2);
            assertThat(current.location()).isEqualTo("站点A");
            assertThat(current.note()).isEqualTo("备注");
        } else {
            // 合并先拿到锁时当前为墓碑：合并 410，恢复随后成功
            assertThat(results).contains("merge-410", "restore-ok");
            assertThat(current.deleted()).isFalse();
            assertThat(current.version()).isEqualTo(3);
        }
        Integer restoreRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM restore_history WHERE observation_id = 'obs-r2'", Integer.class);
        assertThat(restoreRows).isEqualTo(1);
    }

    @Test
    void concurrentRestoreAndReDeleteSerializeByCommitOrder() throws Exception {
        createObservation("obs-r3", "req-x0", "站点A", "1.0", "备注");
        observationService.delete("obs-r3", new DeleteObservationRequest("req-xd", 1));

        // 恢复（expectedVersion=2）与“再次删除”并发：墓碑上 delete 总是 410，因此恢复成功、删除 410
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        observationService.restore("obs-r3",
                                new RestoreObservationRequest("req-xr", 2, 1, "恢复"));
                        return "restore-ok";
                    } catch (ApiException e) {
                        return "restore-" + codeOf(e);
                    }
                },
                () -> {
                    try {
                        observationService.delete("obs-r3",
                                new DeleteObservationRequest("req-xx", 2));
                        return "delete-ok";
                    } catch (ApiException e) {
                        return "delete-" + codeOf(e);
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 行锁串行：若删除先执行则墓碑上 410；若恢复先执行，删除针对活记录版本不匹配 409
        assertThat(results).contains("restore-ok");
        assertThat(results).anyMatch(r -> r.equals("delete-410") || r.equals("delete-409"));
        ObservationResponse current = observationService.getCurrent("obs-r3");
        assertThat(current.deleted()).isFalse();
        assertThat(current.version()).isEqualTo(3);
        assertThat(current.generation()).isEqualTo(2);
        Integer restoreRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM restore_history WHERE observation_id = 'obs-r3'", Integer.class);
        assertThat(restoreRows).isEqualTo(1);
    }

    @Test
    void failedConcurrentRestoreLeavesNoGenerationBumpOrHistory() throws Exception {
        createObservation("obs-r4", "req-y0", "站点A", "1.0", "备注");
        observationService.delete("obs-r4", new DeleteObservationRequest("req-yd", 1));

        // 一个恢复来源是墓碑（422 失败），与一个合法恢复并发：失败事务回滚，不留历史、不增代次
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        observationService.restore("obs-r4",
                                new RestoreObservationRequest("req-ybad", 2, 2, "来源墓碑"));
                        return "ok";
                    } catch (ApiException e) {
                        return codeOf(e);
                    }
                },
                () -> {
                    try {
                        observationService.restore("obs-r4",
                                new RestoreObservationRequest("req-ygood", 2, 1, "合法恢复"));
                        return "ok";
                    } catch (ApiException e) {
                        return codeOf(e);
                    }
                });
        // 一个恢复来源是墓碑，与一个合法恢复并发：失败事务回滚，不留历史、不增代次。
        // 失败方先持锁时来源为墓碑 -> 422；合法恢复先提交后当前已非墓碑 -> 409；两者都必须整体回滚。
        List<String> results = runConcurrently(tasks);

        assertThat(results).contains("ok");
        assertThat(results).anyMatch(r -> r.equals("422") || r.equals("409"));
        ObservationResponse current = observationService.getCurrent("obs-r4");
        assertThat(current.version()).isEqualTo(3);
        assertThat(current.generation()).isEqualTo(2);
        // 仅一条恢复历史；失败方的 requestId 未被占用
        Integer restoreRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM restore_history WHERE observation_id = 'obs-r4'", Integer.class);
        assertThat(restoreRows).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-ybad'", Integer.class);
        assertThat(requestRows).isZero();
    }

    @Test
    void concurrentSameRequestIdRestoreReplaysSingleResult() throws Exception {
        createObservation("obs-r5", "req-z0", "站点A", "1.0", "备注");
        observationService.delete("obs-r5", new DeleteObservationRequest("req-zd", 1));

        List<Callable<ObservationService.WriteOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> observationService.restore("obs-r5",
                    new RestoreObservationRequest("req-zsame", 2, 1, "并发恢复")));
        }
        List<ObservationService.WriteOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(o -> o.status() == 200);
        assertThat(outcomes).allMatch(o -> o.body().version() == 3 && o.body().generation() == 2);
        ObservationResponse current = observationService.getCurrent("obs-r5");
        assertThat(current.version()).isEqualTo(3);
        assertThat(current.generation()).isEqualTo(2);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-zsame'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
        Integer restoreRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM restore_history WHERE observation_id = 'obs-r5'", Integer.class);
        assertThat(restoreRows).isEqualTo(1);
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-r5'", Integer.class);
        assertThat(versionRows).isEqualTo(3);
    }
}
