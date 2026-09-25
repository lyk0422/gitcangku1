package com.example.starter.race.service;

import com.example.starter.race.api.ConfigureInspectionRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.StartRunnerRequest;
import com.example.starter.race.api.SubmitInspectionRequest;
import com.example.starter.race.domain.RunnerRaceState;
import com.example.starter.race.persistence.RunnerRaceStateRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.concurrent.CountDownLatch;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 器材检录相关操作在真实 H2 上的并发裁决：
 * 检录/复检与起跑按 race 行锁的事务提交顺序串行化；同器材并发双 PASS 不会产生双重绑定；
 * 同 requestId 并发重放只产生一次业务变更。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class InspectionConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-inspection-concurrent";

    @Autowired
    private RaceService raceService;

    /** 建赛（v1）、登记 a/b（v2/v3）、开启强制检录10分钟（v4）。 */
    private void seed() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", null, 1, "req-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", null, 2, "req-b"));
        raceService.configureInspection(RACE,
                new ConfigureInspectionRequest(true, 10, 3, "req-cfg"));
    }

    @Test
    void 起跑与FAIL复检并发时恰好一个成功且最终状态自洽() throws Exception {
        seed();
        // 先放一个有效 PASS（v4→v5）
        raceService.submitInspection(RACE, "a",
                new SubmitInspectionRequest("k-pass", "S1", "PASS", 4, "req-pass"));

        int runs = 2;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger startSuccess = new AtomicInteger();
        AtomicInteger failSuccess = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            Future<?> f1 = pool.submit(() -> {
                await(start);
                try {
                    // 起跑先拿到 race 行锁时：最近记录是已提交 PASS，应当成功
                    raceService.startRunner(RACE, "a",
                            new StartRunnerRequest(5, "req-start-race"));
                    startSuccess.incrementAndGet();
                } catch (ConflictException | UnprocessableEntityException ex) {
                    rejected.incrementAndGet();
                }
            });
            Future<?> f2 = pool.submit(() -> {
                await(start);
                try {
                    // FAIL 复检先拿到行锁时：随后起跑必须按版本冲突/门禁失败
                    raceService.submitInspection(RACE, "a",
                            new SubmitInspectionRequest("k-fail", "S1", "FAIL", 5, "req-fail"));
                    failSuccess.incrementAndGet();
                } catch (ConflictException | UnprocessableEntityException ex) {
                    rejected.incrementAndGet();
                }
            });
            start.countDown();
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // 同一版本（v5）下两个写操作恰好一个成功
        assertThat(startSuccess.get() + failSuccess.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);

        RunnerRaceStateRow state = repository.findRunnerState(RACE, "a").orElseThrow();
        if (startSuccess.get() == 1) {
            // 起跑先提交：读到的是已提交 PASS，选手已起跑；FAIL 复检被版本冲突拒绝
            assertThat(state.state()).isEqualTo(RunnerRaceState.STARTED);
            assertThat(raceService.getInspectionStatus(RACE, "a").effective())
                    .isEqualTo("PASS_VALID");
        } else {
            // FAIL 先提交：起跑失败且不写入起跑状态；随后起跑 422，复检 PASS 后才可用
            assertThat(failSuccess.get()).isEqualTo(1);
            assertThat(state.state()).isEqualTo(RunnerRaceState.REGISTERED);
            assertThat(raceService.getInspectionStatus(RACE, "a").effective()).isEqualTo("FAIL");
            assertStart422();
            // 版本现为6：复检 PASS（v7）后起跑（v8）成功
            raceService.submitInspection(RACE, "a",
                    new SubmitInspectionRequest("k-pass-2", "S1", "PASS", 6, "req-pass-2"));
            ServiceResult started = raceService.startRunner(RACE, "a",
                    new StartRunnerRequest(7, "req-start-after"));
            assertThat(started.status()).isEqualTo(200);
        }
    }

    private void assertStart422() {
        try {
            raceService.startRunner(RACE, "a",
                    new StartRunnerRequest(6, "req-start-blocked"));
            throw new AssertionError("FAIL 未过期复检前起跑应被422拒绝");
        } catch (UnprocessableEntityException expected) {
            // 预期：最近结果 FAIL
        }
    }

    @Test
    void 同器材并发双PASS只产生一个活跃绑定() throws Exception {
        seed();
        int runs = 2;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = java.util.List.of(
                    pool.submit(() -> submitSameSerial("a", "ka", start, success, conflicts)),
                    pool.submit(() -> submitSameSerial("b", "kb", start, success, conflicts)));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 同一版本仅一个写能成功；另一个因版本冲突或器材唯一约束失败
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        assertThat(repository.findActiveBindings(RACE)).hasSize(1);
        assertThat(repository.findActiveBinding(RACE, "SERIAL-Y")).isPresent();
        // 恰好一名选手留下检录记录
        int histories = repository.findInspectionsForRunner(RACE, "a").size()
                + repository.findInspectionsForRunner(RACE, "b").size();
        assertThat(histories).isEqualTo(1);
    }

    private Void submitSameSerial(
            String bib,
            String key,
            CountDownLatch start,
            AtomicInteger success,
            AtomicInteger conflicts) {
        await(start);
        try {
            raceService.submitInspection(RACE, bib,
                    new SubmitInspectionRequest(key, "SERIAL-Y", "PASS", 4, "req-" + key));
            success.incrementAndGet();
        } catch (ConflictException ex) {
            conflicts.incrementAndGet();
        }
        return null;
    }

    @Test
    void 同requestId并发检录重放只产生一条记录() throws Exception {
        seed();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        try {
            var futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        await(start);
                        try {
                            ServiceResult result = raceService.submitInspection(RACE, "a",
                                    new SubmitInspectionRequest(
                                            "same-key", "S1", "PASS", 4, "req-same"));
                            ok.incrementAndGet();
                            return result.status();
                        } catch (RuntimeException ex) {
                            errors.incrementAndGet();
                            throw ex;
                        }
                    }))
                    .toList();
            start.countDown();
            Integer firstStatus = futures.getFirst().get(30, TimeUnit.SECONDS);
            for (Future<Integer> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(firstStatus);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors.get()).isZero();
        assertThat(ok.get()).isEqualTo(threads);
        assertThat(repository.findInspectionsForRunner(RACE, "a")).hasSize(1);
        assertThat(repository.findActiveBindings(RACE)).hasSize(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
