package com.example.starter.aging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 真实 H2 库上的业务链测试：正常放行、拒绝原因、幂等重放、作废替代与并发放行。
 */
@SpringBootTest
class AgingServiceTest {

    private static final Instant STORED = Instant.parse("2026-01-10T08:30:00Z");
    private static final LocalDate DAY_ONE = LocalDate.of(2026, 1, 10);

    @Autowired
    private AgingService service;
    @Autowired
    private AgingRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-01-20T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    private String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private AgingBatch newBatch(String tag) {
        return service.createBatch("req-batch-" + tag, "LOT-" + tag, STORED);
    }

    private void fillDay(AgingBatch batch, String tag, int day, String celsius) {
        LocalDate date = DAY_ONE.plusDays(day - 1L);
        service.confirmTurn(batch.id(), "req-turn-" + day + "-" + tag, date, "operator-" + tag);
        service.recordTemperature(batch.id(), "req-temp-" + day + "-" + tag, date,
                new BigDecimal(celsius));
    }

    private AgingBatch fullBatch(String tag) {
        AgingBatch batch = newBatch(tag);
        for (int day = 1; day <= 7; day++) {
            fillDay(batch, tag, day, "10.0");
        }
        return batch;
    }

    @Test
    void happyPathReleaseSucceedsAndSnapshotFrozen() {
        String tag = tag();
        AgingBatch batch = fullBatch(tag);
        ReleaseRecord release = service.release(batch.id(), "req-rel-" + tag);
        assertNotNull(release.id());
        assertEquals(AgingBatch.STATUS_RELEASED,
                repository.findBatchById(batch.id()).orElseThrow().status());
        assertTrue(release.snapshotJson().contains("\"turnRecordId\""));
        assertEquals(7, release.snapshotJson().split("turnRecordId").length - 1);

        // 放行后作废当日温度，不影响已固化的快照
        TemperatureRecord temp = repository
                .findTemperatures(batch.id(), DAY_ONE, DAY_ONE).get(0);
        service.voidTemperature(batch.id(), temp.id(), "req-void-" + tag, "读数异常",
                new BigDecimal("9.5"));
        ReleaseRecord reread = repository.findReleaseByBatchId(batch.id()).orElseThrow();
        assertEquals(release.snapshotJson(), reread.snapshotJson());
    }

    @Test
    void missingDay4TemperatureBlocksThenPasses() {
        String tag = tag();
        AgingBatch batch = newBatch(tag);
        for (int day = 1; day <= 7; day++) {
            service.confirmTurn(batch.id(), "req-turn-" + day + "-" + tag,
                    DAY_ONE.plusDays(day - 1L), "operator-" + tag);
            if (day != 4) {
                service.recordTemperature(batch.id(), "req-temp-" + day + "-" + tag,
                        DAY_ONE.plusDays(day - 1L), new BigDecimal("10.0"));
            }
        }
        BusinessException rejected = assertThrows(BusinessException.class,
                () -> service.release(batch.id(), "req-rel-" + tag));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejected.status());
        assertEquals(List.of("2026-01-13"), rejected.details().get("missingTemperatureDates"));

        // 审核失败不占 requestId：补记第4天有效读数后用同一 requestId 重新审核通过
        service.recordTemperature(batch.id(), "req-temp-4-" + tag, LocalDate.of(2026, 1, 13),
                new BigDecimal("11.0"));
        ReleaseRecord release = service.release(batch.id(), "req-rel-" + tag);
        assertNotNull(release.id());
    }

    @Test
    void outOfRangeTemperatureBlocksUntilReplaced() {
        String tag = tag();
        AgingBatch batch = newBatch(tag);
        for (int day = 1; day <= 7; day++) {
            fillDay(batch, tag, day, day == 3 ? "15.0" : "10.0");
        }
        BusinessException rejected = assertThrows(BusinessException.class,
                () -> service.release(batch.id(), "req-rel-" + tag));
        assertEquals(List.of("2026-01-12"), rejected.details().get("missingTemperatureDates"));

        TemperatureRecord bad = repository
                .findTemperatures(batch.id(), LocalDate.of(2026, 1, 12), LocalDate.of(2026, 1, 12))
                .get(0);
        service.voidTemperature(batch.id(), bad.id(), "req-void-" + tag, "越界读数",
                new BigDecimal("9.0"));
        ReleaseRecord release = service.release(batch.id(), "req-rel-" + tag);
        assertNotNull(release.id());
    }
    @Test
    void duplicateTurnSameDayIsNotTwoDays() {
        String tag = tag();
        AgingBatch batch = newBatch(tag);
        TurnRecord first = service.confirmTurn(batch.id(), "req-turn-1-" + tag, DAY_ONE, "op-a");

        TurnRecord replay = service.confirmTurn(batch.id(), "req-turn-1-" + tag, DAY_ONE, "op-a");
        assertEquals(first.id(), replay.id());

        BusinessException conflict = assertThrows(BusinessException.class,
                () -> service.confirmTurn(batch.id(), "req-turn-1b-" + tag, DAY_ONE, "op-b"));
        assertEquals(HttpStatus.CONFLICT, conflict.status());

        BusinessException mismatch = assertThrows(BusinessException.class,
                () -> service.confirmTurn(batch.id(), "req-turn-1-" + tag, DAY_ONE, "op-b"));
        assertEquals(HttpStatus.CONFLICT, mismatch.status());

        assertEquals(1, repository.findTurns(batch.id(), DAY_ONE, DAY_ONE).size());
    }

    @Test
    void releaseIdempotentReplayAndConflict() {
        String tag = tag();
        AgingBatch batch = fullBatch(tag);
        ReleaseRecord first = service.release(batch.id(), "req-rel-" + tag);

        ReleaseRecord replay = service.release(batch.id(), "req-rel-" + tag);
        assertEquals(first.id(), replay.id());

        BusinessException conflict = assertThrows(BusinessException.class,
                () -> service.release(batch.id(), "req-rel-other-" + tag));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_record WHERE batch_id = ?", Integer.class,
                batch.id()));
    }

    @Test
    void voidTemperatureAppendsReplacementAndIsIdempotent() {
        String tag = tag();
        AgingBatch batch = fullBatch(tag);
        TemperatureRecord original = repository
                .findTemperatures(batch.id(), DAY_ONE.plusDays(1), DAY_ONE.plusDays(1)).get(0);

        AgingService.VoidResult result = service.voidTemperature(batch.id(), original.id(),
                "req-void-" + tag, "传感器故障", new BigDecimal("9.5"));
        assertEquals(TemperatureRecord.STATUS_VOID, result.voided().status());
        assertEquals(TemperatureRecord.STATUS_VALID, result.replacement().status());
        assertEquals(original.recordedDate(), result.replacement().recordedDate());

        AgingService.VoidResult replay = service.voidTemperature(batch.id(), original.id(),
                "req-void-" + tag, "传感器故障", new BigDecimal("9.5"));
        assertEquals(result.replacement().id(), replay.replacement().id());

        BusinessException conflict = assertThrows(BusinessException.class,
                () -> service.voidTemperature(batch.id(), original.id(),
                        "req-void-2-" + tag, "重复作废", new BigDecimal("9.0")));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals(2, repository
                .findTemperatures(batch.id(), DAY_ONE.plusDays(1), DAY_ONE.plusDays(1)).size());
    }

    @Test
    void concurrentReleaseCreatesAtMostOneRecord() throws Exception {
        String tag = tag();
        AgingBatch batch = fullBatch(tag);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        Future<?>[] futures = new Future<?>[threads];
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures[i] = pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                    service.release(batch.id(), "req-rel-" + index + "-" + tag);
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    assertEquals(HttpStatus.CONFLICT, e.status());
                    conflict.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        for (Future<?> future : futures) {
            future.get(15, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertEquals(1, success.get());
        assertEquals(threads - 1, conflict.get());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_record WHERE batch_id = ?", Integer.class,
                batch.id()));
    }
}