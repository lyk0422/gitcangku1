package com.example.starter.observation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * 禁运与发布并发边界测试：真实并发提交，验证同一簇并发到期发布只生成一个快照、
 * 并发禁运设置只有一个成功，且结果按提交顺序裁决。真实 H2 内存库（MySQL 兼容模式）。
 */
@SpringBootTest
class EmbargoConcurrencyTest {

    @Autowired
    private ObservationService observationService;

    @Autowired
    private EmbargoService embargoService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM disclosure_appendix");
        jdbcTemplate.update("DELETE FROM disclosure_snapshot");
        jdbcTemplate.update("DELETE FROM observation_embargo");
        jdbcTemplate.update("DELETE FROM observation_conflict");
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
                requestId, observationId, "站点A", "1.0", "备注"));
    }

    private void setEmbargo(String requestId, String observationId, Instant until) {
        embargoService.setEmbargo(new EmbargoSetRequest(requestId, "auditor-1",
                List.of(new EmbargoItem(observationId, until, "公开范围"))));
    }

    @Test
    void concurrentReleaseGeneratesExactlyOneSnapshot() throws Exception {
        createObservation("req-c1", "obs-1");
        // 截止时刻取当前时刻（区间右开，到达即到期），真实时钟下发布必然已到期
        setEmbargo("req-e1", "obs-1", Instant.now());

        int threads = 4;
        List<Callable<EmbargoService.ReleaseOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            tasks.add(() -> embargoService.release("obs-1",
                    new ReleaseRequest("req-rel-" + index, "publisher-" + index)));
        }
        List<EmbargoService.ReleaseOutcome> outcomes = runConcurrently(tasks);

        // 全部成功且只生成一个快照；所有响应指向同一首次快照
        assertThat(outcomes).hasSize(threads);
        Integer snapshotCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM disclosure_snapshot WHERE observation_id = 'obs-1'", Integer.class);
        assertThat(snapshotCount).isEqualTo(1);
        Instant releasedAt = outcomes.get(0).body().releasedAtUtc();
        String releaseRequestId = outcomes.get(0).body().releaseRequestId();
        for (EmbargoService.ReleaseOutcome outcome : outcomes) {
            assertThat(outcome.status()).isEqualTo(200);
            assertThat(outcome.body().winningVersion()).isEqualTo(1);
            assertThat(outcome.body().releasedAtUtc()).isEqualTo(releasedAt);
            assertThat(outcome.body().releaseRequestId()).isEqualTo(releaseRequestId);
        }
        // 每个请求各留一条成功去重记录
        Integer logCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE operation = 'RELEASE'", Integer.class);
        assertThat(logCount).isEqualTo(threads);
    }

    @Test
    void concurrentEmbargoSetOnlyOneSucceeds() throws Exception {
        createObservation("req-c1", "obs-1");
        Instant until = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int index = i;
            tasks.add(() -> {
                try {
                    return embargoService.setEmbargo(new EmbargoSetRequest("req-emb-" + index,
                            "auditor-" + index,
                            List.of(new EmbargoItem("obs-1", until, "范围-" + index))));
                } catch (ApiException e) {
                    return e;
                }
            });
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream()
                .filter(r -> r instanceof EmbargoService.EmbargoOutcome).count();
        long failures = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status().value() == 422).count();
        assertThat(successes).isEqualTo(1);
        assertThat(failures).isEqualTo(1);
        Integer embargoCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_embargo WHERE observation_id = 'obs-1'", Integer.class);
        assertThat(embargoCount).isEqualTo(1);
        // 失败方回滚不占键：只留一条成功去重记录
        Integer logCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE operation = 'EMBARGO'", Integer.class);
        assertThat(logCount).isEqualTo(1);
    }

    @Test
    void concurrentReleaseAndCorrectionKeepSingleSnapshotAndAppendix() throws Exception {
        createObservation("req-c1", "obs-1");
        setEmbargo("req-e1", "obs-1", Instant.now());

        // 发布与更正并发竞争：行锁保证按提交顺序裁决，快照只有一个
        List<Callable<Object>> tasks = List.of(
                () -> embargoService.release("obs-1", new ReleaseRequest("req-rel-0", "publisher-0")),
                () -> {
                    try {
                        return observationService.merge("obs-1", new MergeObservationRequest(
                                "req-m-0", 1, "站点B", "1.0", "备注"));
                    } catch (ApiException e) {
                        return e;
                    }
                });
        List<Object> results = runConcurrently(tasks);
        assertThat(results.get(0)).isInstanceOf(EmbargoService.ReleaseOutcome.class);

        Integer snapshotCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM disclosure_snapshot WHERE observation_id = 'obs-1'", Integer.class);
        assertThat(snapshotCount).isEqualTo(1);
        // 合并必然成功推进到 v2；附页是否存在取决于提交顺序，且与快照胜出版本一致：
        // 发布先提交则快照为 v1 且更正在附页；合并先提交则快照直接胜出 v2 且无附页。
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM observation_current WHERE observation_id = 'obs-1'", Integer.class);
        assertThat(version).isEqualTo(2);
        Integer appendixCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM disclosure_appendix WHERE observation_id = 'obs-1'", Integer.class);
        Integer winningVersion = jdbcTemplate.queryForObject(
                "SELECT winning_version FROM disclosure_snapshot WHERE observation_id = 'obs-1'", Integer.class);
        if (appendixCount == 1) {
            assertThat(winningVersion).isEqualTo(1);
        } else {
            assertThat(appendixCount).isZero();
            assertThat(winningVersion).isEqualTo(2);
        }
    }
}