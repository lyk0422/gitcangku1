package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ApproveReclassRequest;
import com.example.starter.race.api.ConfigureCategoriesRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.SealCategoryRequest;
import com.example.starter.race.api.SubmitReclassApplicationRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 组别重分的真实并发测试（H2）：批准与处罚/退赛/封榜按事务提交顺序互斥，
 * 同 reclassKey 并发只产生一次迁移。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class ReclassConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-reclass-concurrent";

    @Autowired
    private RaceService raceService;

    /** 建赛 v1 + 组别 v2 + 选手 a(ELITE) v3 + 申请 a->AMATEUR v4。 */
    private String initRaceWithPendingApplication() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.configureCategories(RACE, new ConfigureCategoriesRequest(
                List.of(
                        new ConfigureCategoriesRequest.CategoryDefinition("AMATEUR", 10),
                        new ConfigureCategoriesRequest.CategoryDefinition("ELITE", 2)),
                1, "req-cats"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", "ELITE", 1000L, 2, "req-reg-a"));
        raceService.submitReclassApplication(RACE,
                new SubmitReclassApplicationRequest("a", "AMATEUR", "理由", 3, "req-app-a"));
        return "rca-req-app-a";
    }

    @Test
    void 批准与处罚退赛封榜并发时按提交顺序互斥且最终状态自洽() throws Exception {
        String appA = initRaceWithPendingApplication(); // 当前版本4

        int kinds = 4;
        ExecutorService pool = Executors.newFixedThreadPool(kinds);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger approveSuccess = new AtomicInteger();
        AtomicInteger penaltySuccess = new AtomicInteger();
        AtomicInteger withdrawSuccess = new AtomicInteger();
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, kinds)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            switch (i) {
                                case 0 -> {
                                    raceService.approveReclassBatch(RACE, new ApproveReclassRequest(
                                            "referee", List.of(appA), 4, "rk-con"));
                                    approveSuccess.incrementAndGet();
                                }
                                case 1 -> {
                                    raceService.addPenalty(RACE, new AddPenaltyRequest(
                                            "p-1", "a", "ADD_TIME", 100L, 4, "req-pen"));
                                    penaltySuccess.incrementAndGet();
                                }
                                case 2 -> {
                                    raceService.withdrawRunner(RACE,
                                            new WithdrawRunnerRequest("a", 4, "req-wd"));
                                    withdrawSuccess.incrementAndGet();
                                }
                                default -> {
                                    raceService.sealCategory(RACE, "AMATEUR",
                                            new SealCategoryRequest(4, "req-seal-cat"));
                                    sealSuccess.incrementAndGet();
                                }
                            }
                        } catch (ConflictException ex) {
                            conflicts.incrementAndGet();
                        }
                        return null;
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 四类写操作同一版本并发：恰好一个成功，其余全部版本冲突
        int successes = approveSuccess.get() + penaltySuccess.get()
                + withdrawSuccess.get() + sealSuccess.get();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(kinds - 1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
        // 最终状态与成功操作一致
        if (approveSuccess.get() == 1) {
            assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode())
                    .isEqualTo("AMATEUR");
            assertThat(raceService.getReclassRecords(RACE).records()).hasSize(1);
        } else {
            assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode())
                    .isEqualTo("ELITE");
            assertThat(raceService.getReclassRecords(RACE).records()).isEmpty();
        }
    }

    @Test
    void 同reclassKey并发重放只迁移一次且结果一致() throws Exception {
        String appA = initRaceWithPendingApplication(); // 当前版本4

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        return raceService.approveReclassBatch(RACE, new ApproveReclassRequest(
                                "referee", List.of(appA), 4, "rk-same"));
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            ServiceResult first = futures.getFirst().get(30, TimeUnit.SECONDS);
            assertThat(first.status()).isEqualTo(200);
            for (Future<ServiceResult> future : futures) {
                ServiceResult result = future.get(30, TimeUnit.SECONDS);
                assertThat(result.status()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        // 只迁移一次：版本只加一、记录只有一条、选手只落在一个组别
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
        assertThat(raceService.getReclassRecords(RACE).records()).hasSize(1);
        assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode())
                .isEqualTo("AMATEUR");
        assertThat(repository.countActiveRunnersInCategory(RACE, "AMATEUR")).isEqualTo(1);
        assertThat(repository.countActiveRunnersInCategory(RACE, "ELITE")).isZero();
    }
}