package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.repository.DirectiveRepository;
import com.example.starter.repository.Rows.ReconRow;
import com.example.starter.service.ApiException;
import com.example.starter.service.DirectiveService;
import com.example.starter.web.dto.Requests.ConfirmRequest;
import com.example.starter.web.dto.Requests.CreateDirectiveRequest;
import com.example.starter.web.dto.Requests.ReadingRequest;
import com.example.starter.web.dto.Requests.ReconcileRequest;
import com.example.starter.web.dto.Responses.DirectiveResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 并发与幂等测试：真实 H2 库、真实线程协调，验证确认/撤销竞争、
 * 采样更正与对账并发的快照一致性，以及重复对账不重复累计电量。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class DirectiveConcurrencyTest {

    private static final String START = "2026-10-04T00:00:00Z";
    private static final String END = "2026-10-04T01:00:00Z";
    private static final Instant TICK0 = Instant.parse("2026-10-04T00:00:00Z");
    private static final Instant AFTER = Instant.parse("2026-10-04T02:00:00Z");

    @Autowired
    private DirectiveService service;
    @Autowired
    private DirectiveRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private ExecutorService pool;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM reconciliation_difference");
        jdbc.update("DELETE FROM reconciliation_sample");
        jdbc.update("DELETE FROM reconciliation");
        jdbc.update("DELETE FROM directive_sample");
        jdbc.update("DELETE FROM curtailment_directive");
        clock.set(Instant.parse("2026-10-03T12:00:00Z"));
        pool = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    private long newDirective() {
        return service.create(new CreateDirectiveRequest(
                "SYNTH-U1", Instant.parse(START), Instant.parse(END), 100)).id();
    }

    private long newConfirmedDirectiveWithReadings() {
        long id = newDirective();
        service.confirm(id, new ConfirmRequest("c-" + UUID.randomUUID(), "tester"));
        for (int i = 0; i < 4; i++) {
            service.reportReading(id, new ReadingRequest(TICK0.plusSeconds(i * 900L), 80));
        }
        clock.set(AFTER);
        return id;
    }

    /** 并发执行两个操作并返回结果（异常包装为 ApiException 返回）。 */
    private List<Object> race(Runnable a, Runnable b) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Runnable task : List.of(a, b)) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                try {
                    task.run();
                    return "OK";
                } catch (ApiException ex) {
                    return ex;
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    @Test
    void confirmAndCancelRaceLeavesExactlyOneTerminalState() throws Exception {
        long id = newDirective();
        List<Object> results = race(
                () -> service.confirm(id, new ConfirmRequest("r-conf", "tester")),
                () -> service.cancel(id));

        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ex && ex.status() == HttpStatus.CONFLICT).count();
        assertThat(conflicts).isEqualTo(1);
        DirectiveResponse after = service.getDirective(id);
        assertThat(after.status()).isIn("CONFIRMED", "CANCELLED");
        // 成功方与最终状态一致：确认成功则 CONFIRMED，撤销成功则 CANCELLED
        assertThat(after.status()).isEqualTo(results.get(0).equals("OK") ? "CONFIRMED" : "CANCELLED");
    }

    @Test
    void duplicateReconcileConcurrentlyCountsEnergyOnce() throws Exception {
        long id = newConfirmedDirectiveWithReadings();
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                service.reconcile(id, new ReconcileRequest("r-dup", "auditor-1"));
                return null;
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        // 同一 requestId 并发对账：只落一单，电量只累计一次
        List<ReconRow> recons = repository.reconsByDirective(id);
        assertThat(recons).hasSize(1);
        assertThat(recons.get(0).totalEnergyKwh()).isEqualByComparingTo(new BigDecimal("80.00"));
    }

    @Test
    void correctionAndReconcileConcurrentlyKeepConsistentSnapshot() throws Exception {
        long id = newConfirmedDirectiveWithReadings();
        race(
                () -> service.reportReading(id, new ReadingRequest(TICK0, 90)),
                () -> service.reconcile(id, new ReconcileRequest("r-a", "auditor-1")));
        // 收尾对账：无论竞争顺序如何，最终版本必须反映最新采样
        service.reconcile(id, new ReconcileRequest("r-b", "auditor-1"));

        List<ReconRow> recons = repository.reconsByDirective(id);
        assertThat(recons).hasSizeBetween(1, 2);
        ReconRow last = recons.get(recons.size() - 1);
        // 最终对账总量 = 90 + 80*3 = 330 kW × 0.25h = 82.5 kWh
        assertThat(last.totalEnergyKwh()).isEqualByComparingTo(new BigDecimal("82.50"));
        // 最终对账快照与当前最新采样逐刻度一致，未混用不同版本
        var frozen = repository.frozenSamples(last.id());
        var latest = repository.latestSamples(id);
        assertThat(frozen).hasSize(4);
        for (int i = 0; i < 4; i++) {
            assertThat(frozen.get(i).tickUtc()).isEqualTo(latest.get(i).tickUtc());
            assertThat(frozen.get(i).sampleVersion()).isEqualTo(latest.get(i).sampleVersion());
            assertThat(frozen.get(i).avgPowerKw()).isEqualTo(latest.get(i).avgPowerKw());
        }
        // 差额链完整：v1(80) → v2(82.5) 时差额 +2.5，且仅当存在两个版本时存在
        var diffs = repository.differencesByDirective(id);
        if (recons.size() == 2) {
            assertThat(recons.get(0).totalEnergyKwh()).isEqualByComparingTo(new BigDecimal("80.00"));
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).deltaKwh()).isEqualByComparingTo(new BigDecimal("2.50"));
        } else {
            assertThat(diffs).isEmpty();
        }
        // 已结束指令不因更正恢复为运行中
        assertThat(service.getDirective(id).status()).isEqualTo("ENDED");
    }

    @Test
    void concurrentCorrectionsOnSameTickAppendDistinctVersions() throws Exception {
        long id = newDirective();
        service.confirm(id, new ConfirmRequest("c-" + UUID.randomUUID(), "tester"));
        int threads = 6;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int kw = 80 + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                service.reportReading(id, new ReadingRequest(TICK0, kw));
                return null;
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        // 同一刻度并发更正：版本从 1 连续递增到线程数，无丢失无重复
        assertThat(repository.latestSampleVersion(id, TICK0)).hasValue(threads);
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM directive_sample WHERE directive_id = ? AND tick_utc = ?",
                Integer.class, id, java.sql.Timestamp.from(TICK0));
        assertThat(rows).isEqualTo(threads);
    }
}