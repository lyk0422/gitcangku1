package com.example.starter.observation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

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
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 冻结快照与并发写入按事务提交顺序裁决的真实 H2 并发测试。
 *
 * <p>用受控线程与闩锁构造两种确定性交错：写事务先持全局版本行锁时快照必须全含新版本；
 * 快照事务先持锁时并发写入被阻塞、快照必须全不含新版本。另覆盖同 snapshotKey 的并发幂等。
 */
@SpringBootTest
class SnapshotConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-09-20T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-20T01:00:00Z");

    @Autowired
    private ObservationService observationService;

    @Autowired
    private SnapshotService snapshotService;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private GlobalRevisionRepository globalRevisionRepository;

    @Autowired
    private SnapshotRepository snapshotRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM observation_snapshot_item");
        jdbcTemplate.update("DELETE FROM observation_snapshot");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        jdbcTemplate.update("UPDATE global_revision SET revision = 0 WHERE id = 1");
        Mockito.when(clock.instant()).thenReturn(T0);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private void createBoth() {
        observationService.create(new CreateObservationRequest(
                "req-c1", "obs-1", "站点A", "1.0", "备注"));
        observationService.create(new CreateObservationRequest(
                "req-c2", "obs-2", "地点X", "9.5", "备注X"));
    }

    private long currentRevision() {
        return jdbcTemplate.queryForObject("SELECT revision FROM global_revision WHERE id = 1", Long.class);
    }

    /**
     * 写事务先提交：快照在其提交后才取锁，必须整体包含新版本（obs-1 新版本与全局版本号同时前进，无新旧混取）。
     */
    @Test
    void snapshotAfterWriterCommitIncludesEverything() {
        createBoth();
        Mockito.when(clock.instant()).thenReturn(T1);
        observationService.merge("obs-1",
                new MergeObservationRequest("req-m1", 1, "站点B", "2.0", "新备注"));

        SnapshotService.SnapshotOutcome outcome = snapshotService.createSnapshot(
                new CreateSnapshotRequest("req-s1", "snap-in", T1, List.of("obs-2", "obs-1")));

        SnapshotResponse snapshot = outcome.body();
        assertThat(snapshot.globalLatestVersion()).isEqualTo(3L);
        assertThat(snapshot.items()).hasSize(2);
        SnapshotResponse.Item item1 = snapshot.items().stream()
                .filter(i -> i.observationId().equals("obs-1")).findFirst().orElseThrow();
        SnapshotResponse.Item item2 = snapshot.items().stream()
                .filter(i -> i.observationId().equals("obs-2")).findFirst().orElseThrow();
        assertThat(item1.version()).isEqualTo(2);
        assertThat(item1.location()).isEqualTo("站点B");
        assertThat(item2.version()).isEqualTo(1);
    }

    /**
     * 快照事务先持锁：并发合并在其提交前一直阻塞，快照必须全不含；合并在快照提交后才完成。
     */
    @Test
    void snapshotHoldingLockExcludesConcurrentWriterEntirely() throws Exception {
        createBoth();
        Mockito.when(clock.instant()).thenReturn(T1);

        CountDownLatch snapshotHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 快照事务：显式持有全局版本行锁直到放行，模拟“快照读取+固化进行中”。
            Future<?> snapshotTx = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                long revision = globalRevisionRepository.lockAndGet();
                snapshotHoldsLock.countDown();
                try {
                    releaseSnapshot.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                snapshotRepository.insertHeader(new SnapshotHeader("snap-excl", T1, revision, T1));
                int ordinal = 1;
                for (String id : List.of("obs-1", "obs-2")) {
                    VersionRecord record = observationRepository.findVersionAsOf(id, T1).orElseThrow();
                    ObservationSnapshot s = record.snapshot();
                    snapshotRepository.insertItem(new SnapshotItem("snap-excl", ordinal++, id,
                            s.deleted() ? "TOMBSTONE" : "ACTIVE", s.version(), s.deleted(),
                            s.location(), s.reading(), s.note(), null));
                }
            }));
            assertThat(snapshotHoldsLock.await(10, TimeUnit.SECONDS)).isTrue();

            // 并发合并：在 request_log 占位后阻塞于全局版本行锁，无法提交。
            Future<ObservationService.WriteOutcome> writer = executor.submit(() -> observationService.merge(
                    "obs-1", new MergeObservationRequest("req-m1", 1, "站点B", "2.0", "新备注")));
            assertThatThrownBy(() -> writer.get(400, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            // 快照持锁期间：外部连接看到的仍是旧全局版本，写入尚未提交
            assertThat(currentRevision()).isEqualTo(2L);

            releaseSnapshot.countDown();
            snapshotTx.get(10, TimeUnit.SECONDS);
            ObservationService.WriteOutcome writeOutcome = writer.get(10, TimeUnit.SECONDS);
            assertThat(writeOutcome.status()).isEqualTo(200);
            assertThat(writeOutcome.body().version()).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }

        // 写事务在快照提交后才完成：快照固化全不含（revision=2，obs-1 仍为 v1）
        assertThat(currentRevision()).isEqualTo(3L);
        SnapshotResponse snapshot = snapshotService.getSnapshot("snap-excl");
        assertThat(snapshot.globalLatestVersion()).isEqualTo(2L);
        SnapshotResponse.Item item1 = snapshot.items().stream()
                .filter(i -> i.observationId().equals("obs-1")).findFirst().orElseThrow();
        assertThat(item1.version()).isEqualTo(1);
        assertThat(item1.location()).isEqualTo("站点A");
        // 当前状态已前进，证明写入确实发生在快照之后
        assertThat(observationService.getCurrent("obs-1").version()).isEqualTo(2);
        assertThat(observationService.getCurrent("obs-1").location()).isEqualTo("站点B");
    }

    /**
     * 写事务先持锁：并发快照在其提交前阻塞；写事务提交后快照取锁，必须全含新版本。
     */
    @Test
    void writerHoldingLockMakesConcurrentSnapshotWaitAndThenIncludesAll() throws Exception {
        createBoth();
        Mockito.when(clock.instant()).thenReturn(T1);

        CountDownLatch writerHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 手动构造一个持锁中途暂停的真实写事务（全部走真实 SQL，非 mock）。
            Future<?> writerTx = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                long revision = globalRevisionRepository.lockAndGet();
                ObservationSnapshot current = observationRepository.findCurrentForUpdate("obs-1").orElseThrow();
                writerHoldsLock.countDown();
                try {
                    releaseWriter.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                globalRevisionRepository.advance();
                ObservationSnapshot next = new ObservationSnapshot(
                        "obs-1", current.version() + 1, "站点B", "2.0", "新备注", false);
                observationRepository.updateCurrent(next);
                observationRepository.insertVersion(next, revision + 1, T1);
            }));
            assertThat(writerHoldsLock.await(10, TimeUnit.SECONDS)).isTrue();

            // 并发快照：阻塞于全局版本行锁
            Future<SnapshotService.SnapshotOutcome> snapshotFuture = executor.submit(
                    () -> snapshotService.createSnapshot(new CreateSnapshotRequest(
                            "req-s1", "snap-incl", T1, List.of("obs-1", "obs-2"))));
            assertThatThrownBy(() -> snapshotFuture.get(400, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(currentRevision()).isEqualTo(2L);

            releaseWriter.countDown();
            writerTx.get(10, TimeUnit.SECONDS);
            SnapshotService.SnapshotOutcome outcome = snapshotFuture.get(10, TimeUnit.SECONDS);
            assertThat(outcome.status()).isEqualTo(201);

            // 全含：全局版本号与 obs-1 新版本作为同一个提交点一起出现
            SnapshotResponse snapshot = outcome.body();
            assertThat(snapshot.globalLatestVersion()).isEqualTo(3L);
            SnapshotResponse.Item item1 = snapshot.items().stream()
                    .filter(i -> i.observationId().equals("obs-1")).findFirst().orElseThrow();
            assertThat(item1.version()).isEqualTo(2);
            assertThat(item1.location()).isEqualTo("站点B");
        } finally {
            executor.shutdownNow();
        }
        assertThat(currentRevision()).isEqualTo(3L);
    }

    /**
     * 同 snapshotKey、同参数的快照并发提交：只生成一份不可变快照，双方都返回首次结果。
     */
    @Test
    void concurrentSnapshotsWithSameKeyAndParamsCreateSingleSnapshot() throws Exception {
        createBoth();
        List<Callable<SnapshotService.SnapshotOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int index = i;
            tasks.add(() -> snapshotService.createSnapshot(new CreateSnapshotRequest(
                    "req-snap-" + index, "snap-same", T0, List.of("obs-2", "obs-1"))));
        }
        List<SnapshotService.SnapshotOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(o -> o.status() == 201);
        assertThat(outcomes).allMatch(o -> o.body().globalLatestVersion() == 2L);
        Integer headers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_snapshot WHERE snapshot_key = 'snap-same'", Integer.class);
        Integer items = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_snapshot_item WHERE snapshot_key = 'snap-same'", Integer.class);
        assertThat(headers).isEqualTo(1);
        assertThat(items).isEqualTo(2);
    }

    /**
     * 同 snapshotKey、不同参数的快照并发提交：恰好一个成功，另一个 409，不产生第二份快照。
     */
    @Test
    void concurrentSnapshotsWithSameKeyDifferentParamsYieldOneSuccessAndOneConflict() throws Exception {
        createBoth();
        List<Callable<Integer>> tasks = List.of(
                () -> {
                    try {
                        return snapshotService.createSnapshot(new CreateSnapshotRequest(
                                "req-a", "snap-dup", T0, List.of("obs-1"))).status();
                    } catch (ApiException e) {
                        return e.status().value();
                    }
                },
                () -> {
                    try {
                        return snapshotService.createSnapshot(new CreateSnapshotRequest(
                                "req-b", "snap-dup", T0, List.of("obs-2"))).status();
                    } catch (ApiException e) {
                        return e.status().value();
                    }
                });
        List<Integer> statuses = runConcurrently(tasks);

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        Integer headers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_snapshot WHERE snapshot_key = 'snap-dup'", Integer.class);
        assertThat(headers).isEqualTo(1);
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(2, tasks.size()));
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
}
