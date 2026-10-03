package com.example.starter.curtailment;

import com.example.starter.curtailment.api.ApiDtos;
import com.example.starter.curtailment.api.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发场景：确认/撤销竞争、同 requestId 并发对账、更正与对账并发、重叠指令并发创建。
 * 以 CountDownLatch 协调真实并发，全部设置超时，最终核对数据库状态。
 */
class CurtailmentConcurrencyTest extends AbstractCurtailmentIntegrationTest {

    /** 并发确认与撤销：只允许一种终态，且与成功方一致。 */
    @Test
    void confirmAndRevokeRaceYieldsSingleTerminalState() throws Exception {
        for (int round = 0; round < 10; round++) {
            resetScenario();
            ApiDtos.InstructionView instruction = newInstruction("RACE-" + round, "100");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> runWhenReady(ready, go, () -> {
                service.confirm(instruction.id(), new ApiDtos.ConfirmRequest("race-confirm"));
                return "CONFIRMED";
            })));
            futures.add(pool.submit(() -> runWhenReady(ready, go, () -> {
                service.revoke(instruction.id(), new ApiDtos.RevokeRequest("race-revoke"));
                return "REVOKED";
            })));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<String> succeeded = new ArrayList<>();
            List<String> conflicts = new ArrayList<>();
            for (Future<String> future : futures) {
                try {
                    succeeded.add(future.get(15, TimeUnit.SECONDS));
                } catch (java.util.concurrent.ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ApiException.class);
                    conflicts.add(((ApiException) e.getCause()).code());
                }
            }
            pool.shutdown();
            String stored = jdbc.queryForObject(
                    "SELECT status FROM curtailment_instruction WHERE id = ?", String.class, instruction.id());
            // 终态只允许一种：若仅一方成功，终态与成功方一致；若确认先于撤销（两者均成功），终态为已撤销
            assertThat(succeeded).isNotEmpty();
            if (succeeded.size() == 1) {
                assertThat(conflicts).hasSize(1);
                assertThat(stored).isEqualTo(succeeded.get(0));
            } else {
                assertThat(succeeded).containsExactlyInAnyOrder("CONFIRMED", "REVOKED");
                assertThat(stored).isEqualTo("REVOKED");
            }
        }
    }

    /** 同 requestId 并发对账：全部返回同一对账单，电量只累计一次。 */
    @Test
    void concurrentDuplicateReconcileCountsEnergyOnce() throws Exception {
        ApiDtos.InstructionView instruction = newConfirmedInstruction("DUP", "100");
        reportFour(instruction.id(), instruction.startUtc(), "80");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ApiDtos.ReconciliationView>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> runWhenReady(ready, go, () -> service.reconcile(instruction.id(),
                    new ApiDtos.ReconcileRequest("recon-race", instruction.startUtc())))));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        List<ApiDtos.ReconciliationView> results = new ArrayList<>();
        for (Future<ApiDtos.ReconciliationView> future : futures) {
            results.add(future.get(20, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(results).allSatisfy(r -> {
            assertThat(r.id()).isEqualTo(results.get(0).id());
            assertThat(r.totalEnergyKwh()).isEqualByComparingTo(new BigDecimal("80.000"));
        });
        assertThat(count("reconciliation")).isEqualTo(1);
        assertThat(count("reconciliation_tick")).isEqualTo(4);
        BigDecimal total = jdbc.queryForObject("SELECT SUM(total_energy_kwh) FROM reconciliation", BigDecimal.class);
        assertThat(total).isEqualByComparingTo(new BigDecimal("80.000"));
    }

    /**
     * 采样更正与对账并发：对账要么看到更正前快照（随后产生差额），要么看到更正后快照（无差额），
     * 不得混用不同版本的刻度数据。
     */
    @Test
    void correctionAndReconcileRaceStaysConsistent() throws Exception {
        for (int round = 0; round < 10; round++) {
            resetScenario();
            ApiDtos.InstructionView instruction = newConfirmedInstruction("CR", "100");
            final int roundNo = round;
            reportFour(instruction.id(), instruction.startUtc(), "80");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<ApiDtos.ReconciliationView> reconFuture = pool.submit(() -> runWhenReady(ready, go, () ->
                    service.reconcile(instruction.id(),
                            new ApiDtos.ReconcileRequest("recon-cr-" + roundNo, instruction.startUtc()))));
            Future<ApiDtos.SampleView> fixFuture = pool.submit(() -> runWhenReady(ready, go, () ->
                    service.reportSample(instruction.id(), new ApiDtos.SampleRequest(
                            "fix-cr-" + roundNo, instruction.startUtc(), new BigDecimal("90")))));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            ApiDtos.ReconciliationView reconciliation = reconFuture.get(15, TimeUnit.SECONDS);
            ApiDtos.SampleView correction = fixFuture.get(15, TimeUnit.SECONDS);
            pool.shutdown();
            assertThat(correction.sampleVersion()).isEqualTo(2);
            ApiDtos.InstructionDetailView detail = service.getDetail(instruction.id());
            assertThat(detail.reconciliations()).hasSize(1);
            ApiDtos.TickView firstTick = detail.reconciliations().get(0).ticks().get(0);
            if (firstTick.sampleVersion() == 1) {
                // 对账先于更正：固化 80 kWh，差额 (90-80)*0.25 = 2.5 kWh
                assertThat(detail.reconciliations().get(0).totalEnergyKwh())
                        .isEqualByComparingTo(new BigDecimal("80.000"));
                assertThat(detail.adjustments()).hasSize(1);
                assertThat(detail.adjustments().get(0).deltaEnergyKwh())
                        .isEqualByComparingTo(new BigDecimal("2.500"));
            } else {
                // 更正先于对账：固化 82.5 kWh，无差额
                assertThat(firstTick.sampleVersion()).isEqualTo(2);
                assertThat(detail.reconciliations().get(0).totalEnergyKwh())
                        .isEqualByComparingTo(new BigDecimal("82.500"));
                assertThat(detail.adjustments()).isEmpty();
            }
        }
    }

    /** 同机组并发创建重叠指令：只有一条成功。 */
    @Test
    void concurrentOverlappingCreateAllowsOnlyOne() throws Exception {
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger overlaps = new AtomicInteger();
        for (int round = 0; round < 10; round++) {
            resetScenario();
            var start = clock.instant().plusSeconds(3600);
            var request = new ApiDtos.CreateInstructionRequest(
                    "OVL", start, start.plusSeconds(3600), new BigDecimal("100"), null);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> runWhenReady(ready, go, () -> {
                    try {
                        service.createInstruction(request);
                        successes.incrementAndGet();
                    } catch (ApiException e) {
                        assertThat(e.code()).isEqualTo("INSTRUCTION_OVERLAP");
                        overlaps.incrementAndGet();
                    }
                    return null;
                })));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertThat(successes.get()).isEqualTo(1);
            assertThat(overlaps.get()).isEqualTo(1);
            assertThat(count("curtailment_instruction")).isEqualTo(1);
            successes.set(0);
            overlaps.set(0);
        }
    }

    /** 等待发令枪后执行业务调用。 */
    private static <T> T runWhenReady(CountDownLatch ready, CountDownLatch go,
                                      java.util.concurrent.Callable<T> action) throws Exception {
        ready.countDown();
        if (!go.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发协调超时");
        }
        return action.call();
    }
}