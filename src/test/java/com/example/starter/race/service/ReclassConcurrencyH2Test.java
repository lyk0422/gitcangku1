package com.example.starter.race.service;

import com.example.starter.race.api.ApproveReclassRequest;
import com.example.starter.race.api.CreateCategoryRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.SealCategoryRequest;
import com.example.starter.race.api.SubmitReclassApplicationRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;
import com.example.starter.race.domain.CategoryStatus;
import com.example.starter.race.domain.RunnerStatus;
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
 * 组别重分并发 H2 测试：申请、批准、退赛与组别封榜按事务提交顺序裁决；
 * 同 reclassKey 并发只产生一次迁移，重放返回完整排名结果。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class ReclassConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-reclass-concurrent";

    @Autowired
    private RaceService raceService;

    /** 建赛事（v1）+ CAT_A（v2）+ CAT_B（v3）+ 选手 a1 入 CAT_A（v4）+ 申请 app-1（v5）。 */
    private void setupPendingApplication() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.createCategory(RACE, new CreateCategoryRequest("CAT_A", null, 1, "req-cat-a"));
        raceService.createCategory(RACE, new CreateCategoryRequest("CAT_B", null, 2, "req-cat-b"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a1", "CAT_A", 1000L, 3, "req-a1"));
        raceService.submitReclassApplication(RACE, new SubmitReclassApplicationRequest(
                "app-1", "a1", "CAT_B", "并发测试", 4, "req-app-1"));
    }

    @Test
    void 并发同reclassKey批准只迁移一次且全部重放成功() throws Exception {
        setupPendingApplication();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        try {
                            ServiceResult result = raceService.approveReclassApplications(RACE,
                                    new ApproveReclassRequest("k-concurrent", "referee", 5,
                                            List.of("app-1")));
                            success.incrementAndGet();
                            return result;
                        } catch (RuntimeException ex) {
                            errors.incrementAndGet();
                            throw ex;
                        }
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            ServiceResult first = futures.getFirst().get(30, TimeUnit.SECONDS);
            for (Future<ServiceResult> future : futures) {
                ServiceResult result = future.get(30, TimeUnit.SECONDS);
                assertThat(result.status()).isEqualTo(first.status());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors.get()).isZero();
        assertThat(success.get()).isEqualTo(threads);
        // 只迁移一次：版本只加一、重分记录只有一批
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);
        assertThat(raceService.listReclassRecords(RACE)).hasSize(1);
        assertThat(repository.findRunner(RACE, "a1").orElseThrow().categoryCode())
                .isEqualTo("CAT_B");
    }

    @Test
    void 批准与组别封榜并发互斥且最终状态自洽() throws Exception {
        setupPendingApplication();
        // 当前版本5：批准（目标CAT_B）与封 CAT_B 以同一 expectedVersion 并发
        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger approveSuccess = new AtomicInteger();
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, runs)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.approveReclassApplications(RACE,
                                        new ApproveReclassRequest("k-" + i, "referee", 5,
                                                List.of("app-1")));
                                approveSuccess.incrementAndGet();
                            } else {
                                raceService.sealCategory(RACE, "CAT_B",
                                        new SealCategoryRequest(5, "req-seal-" + i));
                                sealSuccess.incrementAndGet();
                            }
                        } catch (ConflictException | UnprocessableEntityException ex) {
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

        // 两类操作互斥：恰好一类成功一次，其余全部冲突；版本总共只加一
        assertThat(approveSuccess.get() + sealSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(runs - 1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);
        if (approveSuccess.get() == 1) {
            // 批准先提交：a1 已迁入 CAT_B，组别仍 OPEN
            assertThat(repository.findRunner(RACE, "a1").orElseThrow().categoryCode())
                    .isEqualTo("CAT_B");
            assertThat(repository.findCategory(RACE, "CAT_B").orElseThrow().status())
                    .isEqualTo(CategoryStatus.OPEN);
            assertThat(raceService.listReclassRecords(RACE)).hasSize(1);
        } else {
            // 封榜先提交：CAT_B 已封，a1 仍在 CAT_A，申请未批准
            assertThat(repository.findCategory(RACE, "CAT_B").orElseThrow().status())
                    .isEqualTo(CategoryStatus.SEALED);
            assertThat(repository.findRunner(RACE, "a1").orElseThrow().categoryCode())
                    .isEqualTo("CAT_A");
            assertThat(raceService.listReclassRecords(RACE)).isEmpty();
            assertThat(repository.findCategorySnapshot(RACE, "CAT_B")).isPresent();
        }
    }

    @Test
    void 批准与退赛并发按提交顺序裁决() throws Exception {
        setupPendingApplication();
        // 当前版本5：批准与退赛以同一 expectedVersion 并发
        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger approveSuccess = new AtomicInteger();
        AtomicInteger withdrawSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, runs)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.approveReclassApplications(RACE,
                                        new ApproveReclassRequest("k-wd-" + i, "referee", 5,
                                                List.of("app-1")));
                                approveSuccess.incrementAndGet();
                            } else {
                                raceService.withdrawRunner(RACE, "a1",
                                        new WithdrawRunnerRequest(5, "req-wd-" + i));
                                withdrawSuccess.incrementAndGet();
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

        // 按提交顺序裁决：恰好一个操作成功，版本只加一
        assertThat(approveSuccess.get() + withdrawSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(runs - 1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);
        if (withdrawSuccess.get() == 1) {
            // 退赛先提交：选手 WITHDRAWN，申请不可批准
            assertThat(repository.findRunner(RACE, "a1").orElseThrow().status())
                    .isEqualTo(RunnerStatus.WITHDRAWN);
            assertThat(repository.findRunner(RACE, "a1").orElseThrow().categoryCode())
                    .isEqualTo("CAT_A");
            assertThat(raceService.listReclassRecords(RACE)).isEmpty();
        } else {
            // 批准先提交：选手已迁入 CAT_B，退赛因版本冲突失败
            assertThat(repository.findRunner(RACE, "a1").orElseThrow().categoryCode())
                    .isEqualTo("CAT_B");
            assertThat(repository.findRunner(RACE, "a1").orElseThrow().status())
                    .isEqualTo(RunnerStatus.ACTIVE);
            assertThat(raceService.listReclassRecords(RACE)).hasSize(1);
        }
    }
}
