package com.example.starter.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.calibration.domain.Certificate;
import com.example.starter.calibration.domain.Measurement;
import com.example.starter.calibration.domain.MeasurementStatus;
import com.example.starter.calibration.error.ApiException;
import com.example.starter.calibration.service.CertificateService;
import com.example.starter.calibration.service.MeasurementService;
import com.example.starter.calibration.service.ReleaseService;
import com.example.starter.calibration.support.InMemoryRepositories;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 批量放行服务测试：主流程、各项失败原因、整批原子性、并发与幂等边界。
 */
class ReleaseServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant MEASURED_AT = Instant.parse("2026-01-15T12:00:00Z");

    private InMemoryRepositories repositories;
    private CertificateService certificateService;
    private MeasurementService measurementService;
    private ReleaseService releaseService;
    private Certificate certificate;

    @BeforeEach
    void setUp() {
        repositories = new InMemoryRepositories();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        certificateService = new CertificateService(repositories.certificates(), clock);
        measurementService = new MeasurementService(
                repositories.measurements(),
                repositories.certificates(),
                repositories.releaseRecords(),
                clock);
        releaseService = new ReleaseService(
                repositories.measurements(),
                repositories.certificates(),
                repositories.releaseRecords(),
                clock);
        certificate = certificateService.create(
                "inst-1",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:00Z"),
                new BigDecimal("1"),
                new BigDecimal("0"));
    }

    private Measurement submitPassed(String key) {
        return measurementService.submit(
                key, "inst-1", MEASURED_AT,
                new BigDecimal("1"), new BigDecimal("0"), new BigDecimal("10"), "submitter-a");
    }

    private Measurement submitFailed(String key) {
        return measurementService.submit(
                key, "inst-1", MEASURED_AT,
                new BigDecimal("99"), new BigDecimal("0"), new BigDecimal("10"), "submitter-a");
    }

    @Test
    void releaseBatchSucceeds() {
        Measurement first = submitPassed("k-1");
        Measurement second = submitPassed("k-2");
        ReleaseService.ReleaseResult result =
                releaseService.releaseBatch(List.of(first.id(), second.id()), "releaser-b");

        assertEquals("releaser-b", result.releasedBy());
        assertEquals(2, result.records().size());
        assertEquals(NOW, result.releasedAt());

        Measurement released = repositories.measurements().findById(first.id()).orElseThrow();
        assertEquals(MeasurementStatus.RELEASED, released.status());
        assertEquals("releaser-b", released.releasedBy());
        // 放行历史已持久化
        assertTrue(repositories.releaseRecords().findByMeasurementId(first.id()).isPresent());
        assertTrue(repositories.releaseRecords().findByMeasurementId(second.id()).isPresent());
        // 当前可用结果包含两条
        assertEquals(2, measurementService.findCurrentUsable("inst-1").size());
    }

    @Test
    void batchSizeBoundariesAreEnforced() {
        assertEquals(400, assertThrows(ApiException.class,
                () -> releaseService.releaseBatch(List.of(), "releaser-b")).status());
        List<Long> fiftyOne = LongStream.rangeClosed(1, 51).boxed().toList();
        assertEquals(400, assertThrows(ApiException.class,
                () -> releaseService.releaseBatch(fiftyOne, "releaser-b")).status());
    }

    @Test
    void duplicateIdsInBatchReturn400() {
        Measurement measurement = submitPassed("k-3");
        assertEquals(400, assertThrows(ApiException.class,
                () -> releaseService.releaseBatch(
                        List.of(measurement.id(), measurement.id()), "releaser-b")).status());
    }

    @Test
    void blankActorReturns400() {
        Measurement measurement = submitPassed("k-4");
        assertEquals(400, assertThrows(ApiException.class,
                () -> releaseService.releaseBatch(List.of(measurement.id()), " ")).status());
    }

    @Test
    void unknownMeasurementReturns404WithItemReasons() {
        Measurement measurement = submitPassed("k-5");
        ApiException ex = assertThrows(ApiException.class,
                () -> releaseService.releaseBatch(List.of(measurement.id(), 9999L), "releaser-b"));
        assertEquals(404, ex.status());
        assertEquals(1, ex.items().size());
        assertEquals(9999L, ex.items().get(0).measurementId());
        assertEquals("MEASUREMENT_NOT_FOUND", ex.items().get(0).reason());
        // 整批拒绝：合法条目也未放行
        assertEquals(MeasurementStatus.PENDING_RELEASE,
                repositories.measurements().findById(measurement.id()).orElseThrow().status());
    }

    @Test
    void repeatedReleaseReturns409() {
        Measurement measurement = submitPassed("k-6");
        releaseService.releaseBatch(List.of(measurement.id()), "releaser-b");
        ApiException ex = assertThrows(ApiException.class,
                () -> releaseService.releaseBatch(List.of(measurement.id()), "releaser-b"));
        assertEquals(409, ex.status());
        assertEquals("NOT_PENDING_RELEASE", ex.items().get(0).reason());
    }

    @Test
    void failedMeasurementCannotBeReleased() {
        Measurement measurement = submitFailed("k-7");
        ApiException ex = assertThrows(ApiException.class,
                () -> releaseService.releaseBatch(List.of(measurement.id()), "releaser-b"));
        assertEquals(409, ex.status());
        assertEquals("NOT_PASSED", ex.items().get(0).reason());
    }

    @Test
    void actorMustDifferFromSubmitter() {
        Measurement measurement = submitPassed("k-8");
        ApiException ex = assertThrows(ApiException.class,
                () -> releaseService.releaseBatch(List.of(measurement.id()), "submitter-a"));
        assertEquals(409, ex.status());
        assertEquals("ACTOR_EQUALS_SUBMITTER", ex.items().get(0).reason());
    }

    @Test
    void revokedCertificateBlocksRelease() {
        Measurement measurement = submitPassed("k-9");
        certificateService.revoke(certificate.id());
        ApiException ex = assertThrows(ApiException.class,
                () -> releaseService.releaseBatch(List.of(measurement.id()), "releaser-b"));
        assertEquals(409, ex.status());
        assertEquals("CERTIFICATE_REVOKED", ex.items().get(0).reason());
        assertEquals(MeasurementStatus.PENDING_RELEASE,
                repositories.measurements().findById(measurement.id()).orElseThrow().status());
    }

    @Test
    void batchIsAtomicWhenAnyItemFails() {
        Measurement good = submitPassed("k-10");
        Measurement bad = submitFailed("k-11");
        ApiException ex = assertThrows(ApiException.class,
                () -> releaseService.releaseBatch(List.of(good.id(), bad.id()), "releaser-b"));
        assertEquals(409, ex.status());
        assertEquals(1, ex.items().size());
        assertEquals(bad.id(), ex.items().get(0).measurementId());
        // 不能部分成功：合格条目也未放行，且无放行记录
        assertEquals(MeasurementStatus.PENDING_RELEASE,
                repositories.measurements().findById(good.id()).orElseThrow().status());
        assertTrue(repositories.releaseRecords().findByMeasurementId(good.id()).isEmpty());
        assertTrue(measurementService.findCurrentUsable("inst-1").isEmpty());
    }

    @Test
    void multipleFailuresReportAllItemReasons() {
        Measurement failed = submitFailed("k-12");
        Measurement selfRelease = submitPassed("k-13");
        ApiException ex = assertThrows(ApiException.class,
                () -> releaseService.releaseBatch(
                        List.of(failed.id(), selfRelease.id()), "submitter-a"));
        assertEquals(409, ex.status());
        // 不合格条目同时命中 NOT_PASSED 与 ACTOR_EQUALS_SUBMITTER，共 3 条原因
        assertEquals(3, ex.items().size());
        List<String> reasons = ex.items().stream().map(ApiException.ItemReason::reason).toList();
        assertTrue(reasons.contains("NOT_PASSED"));
        assertTrue(reasons.contains("ACTOR_EQUALS_SUBMITTER"));
        assertEquals(2, ex.items().stream()
                .filter(item -> item.measurementId() == failed.id()).count());
    }

    @Test
    void concurrentReleasesOfSameMeasurementSucceedOnce() throws Exception {
        Measurement measurement = submitPassed("k-14");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                // 串行化模拟数据库事务提交顺序（行锁）
                synchronized (repositories.lock()) {
                    try {
                        releaseService.releaseBatch(List.of(measurement.id()), "releaser-b");
                        successes.incrementAndGet();
                    } catch (ApiException ex) {
                        assertEquals(409, ex.status());
                        conflicts.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertEquals(1, successes.get(), "重复放行最多成功一次");
        assertEquals(threads - 1, conflicts.get());
        assertEquals(1, repositories.releaseRecords().findByMeasurementIds(List.of(measurement.id())).size());
    }

    @Test
    void concurrentReleaseAndRevokeFollowCommitOrder() throws Exception {
        // 撤销先提交时，放行必须失败，不能出现撤销后仍被放行的结果
        for (int round = 0; round < 20; round++) {
            setUp();
            Measurement measurement = submitPassed("k-race-" + round);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<String> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
            Future<?> releaseFuture = pool.submit(() -> {
                ready.countDown();
                start.await();
                synchronized (repositories.lock()) {
                    try {
                        releaseService.releaseBatch(List.of(measurement.id()), "releaser-b");
                        outcomes.add("released");
                    } catch (ApiException ex) {
                        assertEquals(409, ex.status());
                        assertEquals("CERTIFICATE_REVOKED", ex.items().get(0).reason());
                        outcomes.add("release-rejected");
                    }
                }
                return null;
            });
            Future<?> revokeFuture = pool.submit(() -> {
                ready.countDown();
                start.await();
                synchronized (repositories.lock()) {
                    certificateService.revoke(certificate.id());
                    outcomes.add("revoked");
                }
                return null;
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            releaseFuture.get(10, TimeUnit.SECONDS);
            revokeFuture.get(10, TimeUnit.SECONDS);
            pool.shutdownNow();

            int releaseIndex = outcomes.indexOf("released");
            int revokeIndex = outcomes.indexOf("revoked");
            boolean revokedCommittedFirst = revokeIndex >= 0
                    && (releaseIndex < 0 || revokeIndex < outcomes.indexOf("release-rejected"));
            Measurement finalState = repositories.measurements().findById(measurement.id()).orElseThrow();
            if (revokedCommittedFirst) {
                assertEquals(MeasurementStatus.PENDING_RELEASE, finalState.status(),
                        "撤销先提交时不允许放行成功");
            }
            if (releaseIndex >= 0) {
                // 放行先提交：结果已放行且历史保留，但撤销后不再当前可用
                assertEquals(MeasurementStatus.RELEASED, finalState.status());
                assertTrue(repositories.releaseRecords().findByMeasurementId(measurement.id()).isPresent());
                assertTrue(measurementService.findCurrentUsable("inst-1").isEmpty());
            }
            assertFalse(outcomes.contains("released") && revokedCommittedFirst,
                    "不能出现撤销后仍被放行的结果");
        }
    }
}
