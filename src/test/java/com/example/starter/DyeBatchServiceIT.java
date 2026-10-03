package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.BatchView;
import com.example.starter.api.dto.MeasurementResponse;
import com.example.starter.api.dto.ReleaseResponse;
import com.example.starter.repo.DyeBatchRepository;
import com.example.starter.repo.DyeBatchRepository.BatchRow;
import com.example.starter.repo.DyeBatchRepository.RollRow;
import com.example.starter.service.ColorDifference;
import com.example.starter.service.DyeBatchService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 真实 H2（MODE=MySQL）集成测试：正常放行、各类拒绝、幂等、并发与勘误。
 * 使用固定时钟，业务时间可断言。
 */
@SpringBootTest
@Import(DyeBatchServiceIT.FixedClockConfig.class)
class DyeBatchServiceIT {

    static final Instant FIXED_NOW = Instant.parse("2026-10-04T00:00:00Z");
    static final Instant MEASURED_AT = Instant.parse("2026-10-04T01:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    DyeBatchService service;

    @Autowired
    DyeBatchRepository repo;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_errata");
        jdbc.update("DELETE FROM release_line");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM recheck_ruling");
        jdbc.update("DELETE FROM color_measurement");
        jdbc.update("DELETE FROM batch_roll");
        jdbc.update("DELETE FROM dye_batch");
    }

    private BatchRow newBatch(String batchNo) {
        return service.createBatch(batchNo, 50, 0, 0);
    }

    private MeasurementResponse measure(long rollId, String kind, String requestId,
            double l, double a, double b) {
        return service.recordMeasurement(rollId, kind, requestId, l, a, b, MEASURED_AT);
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> problemsOf(ApiException ex) {
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        return (Map<String, List<String>>) ex.detail();
    }

    @Test
    void normalFlowReleaseAndQuery() {
        BatchRow batch = newBatch("B-NORMAL");
        RollRow r1 = service.addRoll(batch.id(), "R-01");
        RollRow r2 = service.addRoll(batch.id(), "R-02");
        MeasurementResponse m1 = measure(r1.id(), "ORIGINAL", "m-1", 51, 1, 1);
        measure(r2.id(), "ORIGINAL", "m-2", 51, 1, 1);
        assertEquals(Math.sqrt(3), m1.deltaE(), 1e-12);
        assertTrue(m1.qualified());
        assertFalse(m1.replayed());

        ReleaseResponse release = service.release(batch.id(), "rel-1");
        assertFalse(release.replayed());
        assertEquals(1, release.targetVersion());
        assertEquals(FIXED_NOW, release.releasedAt());
        assertEquals(2, release.lines().size());
        assertEquals(Math.sqrt(3), release.lines().get(0).deltaE(), 1e-12);
        assertEquals("ORIGINAL", release.lines().get(0).adoptedKind());

        BatchView view = service.getBatchView(batch.id());
        assertEquals("RELEASED", view.status());
        assertEquals(1, view.targetVersion());
        assertEquals(2, view.rolls().size());
        assertNotNull(view.rolls().get(0).original());
        assertNull(view.rolls().get(0).recheck());
        assertEquals("ORIGINAL", view.rolls().get(0).adoptedKind());
        assertEquals(Math.sqrt(3), view.rolls().get(0).adoptedDeltaE(), 1e-12);
        assertNotNull(view.release());
        assertEquals(2, view.release().lines().size());
    }

    @Test
    void missingMeasurementRejectedAndFailedTxDoesNotOccupyKey() {
        BatchRow batch = newBatch("B-MISSING");
        RollRow r1 = service.addRoll(batch.id(), "R-01");
        RollRow r2 = service.addRoll(batch.id(), "R-02");
        measure(r1.id(), "ORIGINAL", "m-1", 51, 1, 1);

        ApiException ex = assertThrows(ApiException.class, () -> service.release(batch.id(), "rel-1"));
        Map<String, List<String>> problems = problemsOf(ex);
        assertEquals(List.of("MISSING_MEASUREMENT"), problems.get("R-02"));
        assertNull(problems.get("R-01"));
        // 失败事务不占键：无放行单，requestId 可复用
        assertTrue(repo.findReleaseByBatchId(batch.id()).isEmpty());
        assertTrue(repo.findReleaseByRequestId("rel-1").isEmpty());

        measure(r2.id(), "ORIGINAL", "m-2", 51, 1, 1);
        ReleaseResponse release = service.release(batch.id(), "rel-1");
        assertFalse(release.replayed());
        assertEquals(2, release.lines().size());
    }

    @Test
    void unruledRecheckRejectedThenRuledAndReleased() {
        BatchRow batch = newBatch("B-RULING");
        RollRow r1 = service.addRoll(batch.id(), "R-01");
        measure(r1.id(), "ORIGINAL", "m-1", 53, 0, 0);
        measure(r1.id(), "RECHECK", "m-2", 51, 1, 1);

        ApiException ex = assertThrows(ApiException.class, () -> service.release(batch.id(), "rel-1"));
        assertEquals(List.of("PENDING_RULING"), problemsOf(ex).get("R-01"));

        service.ruleRecheck(r1.id(), "RECHECK", "qc-wang");
        ReleaseResponse release = service.release(batch.id(), "rel-1");
        assertEquals("RECHECK", release.lines().get(0).adoptedKind());
        assertEquals(Math.sqrt(3), release.lines().get(0).deltaE(), 1e-12);

        BatchView view = service.getBatchView(batch.id());
        assertNotNull(view.rolls().get(0).original());
        assertNotNull(view.rolls().get(0).recheck());
        assertEquals("qc-wang", view.rolls().get(0).ruling().inspector());
        assertEquals("RECHECK", view.rolls().get(0).adoptedKind());
    }
    @Test
    void outOfToleranceRejectedWithRollNo() {
        BatchRow batch = newBatch("B-OOT");
        RollRow r1 = service.addRoll(batch.id(), "R-01");
        RollRow r2 = service.addRoll(batch.id(), "R-02");
        measure(r1.id(), "ORIGINAL", "m-1", 51, 1, 1);
        measure(r2.id(), "ORIGINAL", "m-2", 53, 0, 0);

        ApiException ex = assertThrows(ApiException.class, () -> service.release(batch.id(), "rel-1"));
        Map<String, List<String>> problems = problemsOf(ex);
        assertEquals(List.of("OUT_OF_TOLERANCE"), problems.get("R-02"));
        assertNull(problems.get("R-01"));
        // 不允许只放行合格子集：整批无放行单
        assertTrue(repo.findReleaseByBatchId(batch.id()).isEmpty());
    }

    @Test
    void staleTargetVersionRejectedThenRemeasuredAndReleased() {
        BatchRow batch = newBatch("B-VERSION");
        RollRow r1 = service.addRoll(batch.id(), "R-01");
        measure(r1.id(), "ORIGINAL", "m-1", 51, 1, 1);

        BatchRow updated = service.updateTarget(batch.id(), 50.5, 0, 0);
        assertEquals(2, updated.targetVersion());

        ApiException ex = assertThrows(ApiException.class, () -> service.release(batch.id(), "rel-1"));
        assertEquals(List.of("STALE_TARGET_VERSION"), problemsOf(ex).get("R-01"));

        // 旧测色保留，追加新测色后按新版本放行
        measure(r1.id(), "ORIGINAL", "m-2", 51, 1, 1);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM color_measurement WHERE roll_id = ?", Integer.class, r1.id());
        assertEquals(2, count);

        ReleaseResponse release = service.release(batch.id(), "rel-1");
        assertEquals(2, release.targetVersion());
        assertEquals(ColorDifference.deltaE(51, 1, 1, 50.5, 0, 0), release.lines().get(0).deltaE(), 1e-12);
    }

    @Test
    void measurementIdempotencyReplayAndConflict() {
        BatchRow batch = newBatch("B-IDEM");
        RollRow r1 = service.addRoll(batch.id(), "R-01");
        MeasurementResponse first = measure(r1.id(), "ORIGINAL", "m-1", 51, 1, 1);

        MeasurementResponse replay = measure(r1.id(), "ORIGINAL", "m-1", 51, 1, 1);
        assertTrue(replay.replayed());
        assertEquals(first.measurementId(), replay.measurementId());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM color_measurement WHERE request_id = 'm-1'", Integer.class);
        assertEquals(1, count);

        ApiException ex = assertThrows(ApiException.class,
                () -> measure(r1.id(), "ORIGINAL", "m-1", 52, 1, 1));
        assertEquals(HttpStatus.CONFLICT, ex.status());
    }

    @Test
    void releaseIdempotencyReplayAndConflict() {
        BatchRow batch = newBatch("B-RELIDEM");
        RollRow r1 = service.addRoll(batch.id(), "R-01");
        measure(r1.id(), "ORIGINAL", "m-1", 51, 1, 1);

        ReleaseResponse first = service.release(batch.id(), "rel-1");
        ReleaseResponse replay = service.release(batch.id(), "rel-1");
        assertTrue(replay.replayed());
        assertEquals(first.releaseId(), replay.releaseId());
        assertEquals(first.lines().size(), replay.lines().size());

        ApiException ex = assertThrows(ApiException.class, () -> service.release(batch.id(), "rel-2"));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_order WHERE batch_id = ?", Integer.class, batch.id());
        assertEquals(1, count);
    }

    @Test
    void recheckDoesNotAffectOtherRolls() {
        BatchRow batch = newBatch("B-ISO");
        RollRow r1 = service.addRoll(batch.id(), "R-01");
        RollRow r2 = service.addRoll(batch.id(), "R-02");
        measure(r1.id(), "ORIGINAL", "m-1", 51, 1, 1);
        measure(r2.id(), "ORIGINAL", "m-2", 51, 1, 1);

        measure(r1.id(), "RECHECK", "m-3", 50.5, 1, 1);
        service.ruleRecheck(r1.id(), "RECHECK", "qc-li");
        ReleaseResponse release = service.release(batch.id(), "rel-1");
        assertEquals(2, release.lines().size());
        // R-02 的已确认读数不受 R-01 复检影响
        assertEquals("ORIGINAL", release.lines().get(1).adoptedKind());
        assertEquals(Math.sqrt(3), release.lines().get(1).deltaE(), 1e-12);
    }
    @Test
    void concurrentReleaseCreatesSingleOrder() throws Exception {
        BatchRow batch = newBatch("B-CONC");
        RollRow r1 = service.addRoll(batch.id(), "R-01");
        measure(r1.id(), "ORIGINAL", "m-1", 51, 1, 1);

        int threads = 8;
        var ready = new java.util.concurrent.CountDownLatch(threads);
        var go = new java.util.concurrent.CountDownLatch(1);
        List<String> outcomes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(threads)) {
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String reqId = "rel-" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        ReleaseResponse r = service.release(batch.id(), reqId);
                        outcomes.add("OK:" + r.releaseId() + ":" + r.replayed());
                    } catch (ApiException e) {
                        outcomes.add("ERR:" + e.status().value());
                    } catch (Exception e) {
                        outcomes.add("FAIL:" + e.getClass().getSimpleName());
                    }
                }));
            }
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            go.countDown();
            for (var f : futures) {
                f.get(20, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        // 最多一张放行单，恰好一个成功，其余 409
        Integer orders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_order WHERE batch_id = ?", Integer.class, batch.id());
        assertEquals(1, orders);
        assertEquals(1, outcomes.stream().filter(o -> o.startsWith("OK:")).count());
        assertEquals(threads - 1, outcomes.stream().filter(o -> o.equals("ERR:409")).count());
    }

    @Test
    void concurrentSameRequestIdMeasurementYieldsSingleRow() throws Exception {
        BatchRow batch = newBatch("B-CONC-M");
        RollRow r1 = service.addRoll(batch.id(), "R-01");

        int threads = 4;
        var go = new java.util.concurrent.CountDownLatch(1);
        List<Long> ids = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(threads)) {
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    ids.add(measure(r1.id(), "ORIGINAL", "m-same", 51, 1, 1).measurementId());
                    return null;
                }));
            }
            go.countDown();
            for (var f : futures) {
                f.get(20, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        assertEquals(1, ids.stream().distinct().count());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM color_measurement WHERE request_id = 'm-same'", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void errataAppendOnlyAndFrozenDataUntouched() {
        BatchRow batch = newBatch("B-ERRATA");
        RollRow r1 = service.addRoll(batch.id(), "R-01");
        measure(r1.id(), "ORIGINAL", "m-1", 51, 1, 1);
        ReleaseResponse release = service.release(batch.id(), "rel-1");

        service.appendErrata(release.releaseId(), "卷R-01标签打印偏移，实物合格");
        service.appendErrata(release.releaseId(), "补充：客户已确认收货");

        BatchView view = service.getBatchView(batch.id());
        assertEquals(2, view.release().errata().size());
        // 冻结明细未被勘误修改
        assertEquals(1, view.release().lines().size());
        assertEquals(Math.sqrt(3), view.release().lines().get(0).deltaE(), 1e-12);

        // 已放行批次：测色、裁决、目标值、加卷均拒绝，只能追加勘误
        ApiException e1 = assertThrows(ApiException.class,
                () -> measure(r1.id(), "ORIGINAL", "m-2", 51, 1, 1));
        assertEquals(HttpStatus.CONFLICT, e1.status());
        ApiException e2 = assertThrows(ApiException.class,
                () -> service.updateTarget(batch.id(), 51, 0, 0));
        assertEquals(HttpStatus.CONFLICT, e2.status());
        ApiException e3 = assertThrows(ApiException.class,
                () -> service.addRoll(batch.id(), "R-02"));
        assertEquals(HttpStatus.CONFLICT, e3.status());
        ApiException e4 = assertThrows(ApiException.class,
                () -> service.ruleRecheck(r1.id(), "RECHECK", "qc-li"));
        assertEquals(HttpStatus.CONFLICT, e4.status());
    }

    @Test
    void maxThirtyRollsEnforced() {
        BatchRow batch = newBatch("B-MAX");
        for (int i = 1; i <= 30; i++) {
            service.addRoll(batch.id(), "R-" + i);
        }
        ApiException ex = assertThrows(ApiException.class, () -> service.addRoll(batch.id(), "R-31"));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals(30, repo.countRolls(batch.id()));
    }

    @Test
    void fixedClockUsedForBusinessTimestamps() {
        BatchRow batch = newBatch("B-CLOCK");
        assertEquals(FIXED_NOW, batch.createdAt());
        RollRow r1 = service.addRoll(batch.id(), "R-01");
        measure(r1.id(), "ORIGINAL", "m-1", 51, 1, 1);
        ReleaseResponse release = service.release(batch.id(), "rel-1");
        assertEquals(FIXED_NOW, release.releasedAt());
        // 测色时刻来自请求（UTC），不受时钟影响
        assertEquals(MEASURED_AT, release.lines().get(0).measuredAt());
    }
}