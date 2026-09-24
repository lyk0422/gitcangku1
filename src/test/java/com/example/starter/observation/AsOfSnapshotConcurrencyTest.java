package com.example.starter.observation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冻结快照与并发写入按事务提交顺序裁决的并发边界测试（真实 H2 内存库、真实并发线程）。
 *
 * <p>通过对 {@link ObservationRepository#lockGlobalWriteMutex()} 的间谍回调建立按调用次序的同步点，
 * 确定性地构造“快照先持锁 / 写先持锁”两种交叠，断言全含或全不含、快照内绝不出现新旧版本混读，
 * 以及同 snapshotKey 并发唯一、同 requestId 并发同参重放。
 */
@SpringBootTest
class AsOfSnapshotConcurrencyTest {

    private static final Instant T1 = Instant.parse("2026-09-22T10:00:00Z");
    private static final Instant WRITER_TIME = Instant.parse("2026-09-22T10:10:00Z");
    private static final Instant TARGET = Instant.parse("2026-09-22T10:30:00Z");
    private static final Instant NOW = Instant.parse("2026-09-22T11:00:00Z");

    @Autowired
    private AsOfSnapshotService asOfSnapshotService;

    @Autowired
    private ObservationService observationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    @MockitoSpyBean
    private ObservationRepository observationRepository;

    private final AtomicReference<Instant> currentInstant = new AtomicReference<>(NOW);

    /** 互斥锁调用序号（在基线数据写入完成后归零）。 */
    private final AtomicLong lockCallSeq = new AtomicLong();
    /** 仅首次真正取锁的一方把提交时刻拨到目标时刻之前（模拟先于快照切刻提交）。 */
    private final AtomicBoolean stampFirstCallWithWriterTime = new AtomicBoolean(false);

    private CountDownLatch firstEntered;
    private CountDownLatch secondEntered;
    private CountDownLatch firstGate;
    private CountDownLatch secondGate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM observation_snapshot_item");
        jdbcTemplate.update("DELETE FROM observation_snapshot");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version_commit");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        firstEntered = null;
        secondEntered = null;
        firstGate = null;
        secondGate = null;
        stampFirstCallWithWriterTime.set(false);
        lockCallSeq.set(0);
        currentInstant.set(NOW);
        Mockito.when(clock.instant()).thenAnswer(invocation -> currentInstant.get());
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        Mockito.clearInvocations(observationRepository);
        Mockito.doAnswer(invocation -> {
            long seq = lockCallSeq.incrementAndGet();
            if (seq == 1 && firstEntered != null) {
                firstEntered.countDown();
                firstGate.await(15, TimeUnit.SECONDS);
            } else if (seq == 2 && secondEntered != null) {
                secondEntered.countDown();
                secondGate.await(15, TimeUnit.SECONDS);
            }
            if (seq == 1 && stampFirstCallWithWriterTime.get()) {
                currentInstant.set(WRITER_TIME);
            }
            return invocation.callRealMethod();
        }).when(observationRepository).lockGlobalWriteMutex();
    }

    /** 基线写入不参与交叠：写入后重置屏障状态与调用序号。 */
    private void armBarriers() {
        firstEntered = new CountDownLatch(1);
        secondEntered = new CountDownLatch(1);
        firstGate = new CountDownLatch(1);
        secondGate = new CountDownLatch(1);
        lockCallSeq.set(0);
    }

    private void seedTwoObservations() {
        currentInstant.set(T1);
        observationService.create(new CreateObservationRequest(
                "req-seed-1", "obs-1", "站点1", "1.0", "备注1"));
        observationService.create(new CreateObservationRequest(
                "req-seed-2", "obs-2", "站点2", "1.0", "备注2"));
        currentInstant.set(NOW);
    }

    private ObservationService.WriteOutcome mergeObs1ToV2(String requestId) {
        return observationService.merge("obs-1",
                new MergeObservationRequest(requestId, 1, "站点1-新", "2.0", "备注1-新"));
    }

    private ObservationService.WriteOutcome mergeObs2ToV2(String requestId) {
        return observationService.merge("obs-2",
                new MergeObservationRequest(requestId, 1, "站点2-新", "2.0", "备注2-新"));
    }

    private SnapshotRecord snapshot(String requestId, String snapshotKey) {
        return asOfSnapshotService.createSnapshot(
                new SnapshotCreateRequest(requestId, snapshotKey, TARGET, List.of("obs-2", "obs-1")));
    }

    @Test
    void snapshotHoldingLockExcludesConcurrentWriterCommittingAfterward() throws Exception {
        seedTwoObservations();
        armBarriers();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 快照事务先在互斥锁前停住；写事务第二个停住（此时双方都未真正持有数据库锁）
            Future<SnapshotRecord> snapshotFuture = executor.submit(() -> snapshot("req-snap-a", "snap-a"));
            assertThat(firstEntered.await(15, TimeUnit.SECONDS)).isTrue();
            Future<ObservationService.WriteOutcome> mergeFuture =
                    executor.submit(() -> mergeObs2ToV2("req-write-a"));
            assertThat(secondEntered.await(15, TimeUnit.SECONDS)).isTrue();

            // 仅放行快照：它先取得真实互斥锁并在写事务提交前完成一致读取与落盘
            firstGate.countDown();
            SnapshotRecord frozen = snapshotFuture.get(30, TimeUnit.SECONDS);
            // 快照提交后再放行写事务：新版本提交于快照事务之后
            secondGate.countDown();
            ObservationService.WriteOutcome mergeOutcome = mergeFuture.get(30, TimeUnit.SECONDS);

            // 全不含：新版本不进入快照；两条都取切刻前版本，无新旧混读
            assertThat(mergeOutcome.status()).isEqualTo(200);
            assertThat(mergeOutcome.body().version()).isEqualTo(2);
            assertThat(frozen.globalLatestVersion()).isEqualTo(2L);
            assertThat(frozen.items()).extracting(SnapshotItemRecord::observationId)
                    .containsExactly("obs-1", "obs-2");
            assertThat(frozen.items()).extracting(SnapshotItemRecord::version).containsExactly(1, 1);
            assertThat(frozen.items()).allMatch(item -> item.state() == ObservationState.PRESENT);

            ObservationResponse current2 = observationService.getCurrent("obs-2");
            assertThat(current2.version()).isEqualTo(2);
            Integer snapshotRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM observation_snapshot WHERE snapshot_key = 'snap-a'", Integer.class);
            Integer itemRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM observation_snapshot_item WHERE snapshot_key = 'snap-a'", Integer.class);
            assertThat(snapshotRows).isEqualTo(1);
            assertThat(itemRows).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void writerCommittingBeforeSnapshotCutIsFullyIncluded() throws Exception {
        seedTwoObservations();
        armBarriers();
        stampFirstCallWithWriterTime.set(true);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 写事务先停在互斥锁前；快照事务第二个停住
            Future<ObservationService.WriteOutcome> mergeFuture =
                    executor.submit(() -> mergeObs1ToV2("req-write-b"));
            assertThat(firstEntered.await(15, TimeUnit.SECONDS)).isTrue();
            Future<SnapshotRecord> snapshotFuture = executor.submit(() -> snapshot("req-snap-b", "snap-b"));
            assertThat(secondEntered.await(15, TimeUnit.SECONDS)).isTrue();

            // 放行写事务：取得真实互斥锁，以早于目标时刻的提交时间写入 v2 并提交
            firstGate.countDown();
            ObservationService.WriteOutcome mergeOutcome = mergeFuture.get(30, TimeUnit.SECONDS);
            // 快照放行前恢复服务端当前时刻，避免目标时刻被误判为未来
            currentInstant.set(NOW);
            // 写事务提交后再放行快照：它必须整体包含已提交的新版本
            secondGate.countDown();
            SnapshotRecord frozen = snapshotFuture.get(30, TimeUnit.SECONDS);

            assertThat(mergeOutcome.body().version()).isEqualTo(2);
            // 全含：obs-1 取新版本 2，obs-2 仍为版本 1，全局最新版本序号随提交前进到 3
            assertThat(frozen.globalLatestVersion()).isEqualTo(3L);
            assertThat(frozen.items()).extracting(SnapshotItemRecord::observationId)
                    .containsExactly("obs-1", "obs-2");
            assertThat(frozen.items()).extracting(SnapshotItemRecord::version).containsExactly(2, 1);
            assertThat(frozen.items().get(0).location()).isEqualTo("站点1-新");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSameSnapshotKeyYieldsSingleSnapshotAndOtherGets409() throws Exception {
        seedTwoObservations();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<String>> tasks = List.of(
                    () -> {
                        ready.countDown();
                        start.await(10, TimeUnit.SECONDS);
                        try {
                            snapshot("req-key-1", "snap-key");
                            return "201";
                        } catch (ApiException e) {
                            return String.valueOf(e.status().value());
                        }
                    },
                    () -> {
                        ready.countDown();
                        start.await(10, TimeUnit.SECONDS);
                        try {
                            snapshot("req-key-2", "snap-key");
                            return "201";
                        } catch (ApiException e) {
                            return String.valueOf(e.status().value());
                        }
                    });
            List<Future<String>> futures = tasks.stream().map(executor::submit).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(results).containsExactlyInAnyOrder("201", "409");
            Integer snapshotRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM observation_snapshot", Integer.class);
            Integer itemRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM observation_snapshot_item", Integer.class);
            assertThat(snapshotRows).isEqualTo(1);
            assertThat(itemRows).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSameRequestIdAndParamsBothReplaySingleSnapshot() throws Exception {
        seedTwoObservations();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<SnapshotRecord>> tasks = List.of(
                    () -> {
                        ready.countDown();
                        start.await(10, TimeUnit.SECONDS);
                        return snapshot("req-replay", "snap-replay");
                    },
                    () -> {
                        ready.countDown();
                        start.await(10, TimeUnit.SECONDS);
                        return snapshot("req-replay", "snap-replay");
                    });
            List<Future<SnapshotRecord>> futures = tasks.stream().map(executor::submit).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            SnapshotRecord first = futures.get(0).get(30, TimeUnit.SECONDS);
            SnapshotRecord second = futures.get(1).get(30, TimeUnit.SECONDS);

            assertThat(first.snapshotKey()).isEqualTo(second.snapshotKey());
            assertThat(first.items()).usingRecursiveComparison().isEqualTo(second.items());
            Integer snapshotRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM observation_snapshot WHERE snapshot_key = 'snap-replay'",
                    Integer.class);
            assertThat(snapshotRows).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }
}
