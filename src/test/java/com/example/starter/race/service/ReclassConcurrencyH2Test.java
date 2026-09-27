package com.example.starter.race.service;

import com.example.starter.race.api.ApproveReclassRequest;
import com.example.starter.race.api.CreateCategoryRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.ReclassApplyRequest;
import com.example.starter.race.api.ReclassApprovalResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.SealCategoryRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;
import com.example.starter.race.domain.CategoryStatus;
import com.example.starter.race.domain.ReclassRequestStatus;
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
 * 同 reclassKey 并发批准只迁移一次，成功重放完整排名结果。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class ReclassConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-reclass-concurrent";

    @Autowired
    private RaceService raceService;

    private int version() {
        return repository.findRace(RACE).orElseThrow().version();
    }

    /** 建赛事 + A/B 组别 + 选手 a(A)，并提交 a->B 申请，返回申请ID。 */
    private String setupPendingApplication() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.createCategory(RACE, new CreateCategoryRequest("A", 10, 1, "req-cat-a"));
        raceService.createCategory(RACE, new CreateCategoryRequest("B", 10, 2, "req-cat-b"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 1000L, "A", 3, "req-reg-a"));
        String appId = "app-a-b";
        raceService.submitReclass(RACE,
                new ReclassApplyRequest("a", "B", "并发测试", 4, appId));
        return appId;
    }

    @Test
    void 并发同reclassKey批准只迁移一次且重放结果一致() throws Exception {
        String appId = setupPendingApplication();
        int expectedVersion = version();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        try {
                            return raceService.approveReclass(RACE, new ApproveReclassRequest(
                                    "key-same", "referee", expectedVersion, List.of(appId)));
                        } catch (RuntimeException ex) {
                            errors.incrementAndGet();
                            throw ex;
                        }
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            ServiceResult first = futures.getFirst().get(30, TimeUnit.SECONDS);
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            for (Future<ServiceResult> future : futures) {
                ServiceResult result = future.get(30, TimeUnit.SECONDS);
                assertThat(result.status()).isEqualTo(first.status());
                // 重放线程拿到反序列化JSON，统一转换后断言完整排名结果一致
                ReclassApprovalResponse body =
                        mapper.convertValue(result.body(), ReclassApprovalResponse.class);
                assertThat(body.batchId()).isEqualTo("key-same");
                assertThat(body.standings()).hasSize(2);
            }
            assertThat(first.status()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors.get()).isZero();
        // 只迁移一次：一条重分记录、版本只加一
        assertThat(repository.findReclassRecords(RACE)).hasSize(1);
        assertThat(version()).isEqualTo(expectedVersion + 1);
        assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode()).isEqualTo("B");
    }

    @Test
    void 并发批准与退赛按事务提交顺序裁决() throws Exception {
        String appId = setupPendingApplication();
        int expectedVersion = version();

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
                                raceService.approveReclass(RACE, new ApproveReclassRequest(
                                        "key-ap-" + i, "referee", expectedVersion,
                                        List.of(appId)));
                                approveSuccess.incrementAndGet();
                            } else {
                                raceService.withdrawRunner(RACE, "a",
                                        new WithdrawRunnerRequest(expectedVersion,
                                                "req-wd-" + i));
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

        // 同一版本上互斥：恰好一个写操作成功，其余全部冲突
        assertThat(approveSuccess.get() + withdrawSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(runs - 1);
        assertThat(version()).isEqualTo(expectedVersion + 1);
        if (approveSuccess.get() == 1) {
            assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode())
                    .isEqualTo("B");
            assertThat(repository.findRunner(RACE, "a").orElseThrow().withdrawn()).isFalse();
            assertThat(repository.findReclassRequest(appId).orElseThrow().status())
                    .isEqualTo(ReclassRequestStatus.APPROVED);
            assertThat(repository.findReclassRecords(RACE)).hasSize(1);
        } else {
            assertThat(repository.findRunner(RACE, "a").orElseThrow().withdrawn()).isTrue();
            assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode())
                    .isEqualTo("A");
            assertThat(repository.findReclassRequest(appId).orElseThrow().status())
                    .isEqualTo(ReclassRequestStatus.PENDING);
            assertThat(repository.findReclassRecords(RACE)).isEmpty();
        }
    }

    @Test
    void 并发批准与组别封榜互斥且最终状态自洽() throws Exception {
        String appId = setupPendingApplication();
        int expectedVersion = version();

        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger approveSuccess = new AtomicInteger();
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger unprocessable = new AtomicInteger();
        try {
            var futures = IntStream.range(0, runs)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.approveReclass(RACE, new ApproveReclassRequest(
                                        "key-ap-" + i, "referee", expectedVersion,
                                        List.of(appId)));
                                approveSuccess.incrementAndGet();
                            } else {
                                raceService.sealCategory(RACE, "B",
                                        new SealCategoryRequest(expectedVersion,
                                                "req-seal-" + i));
                                sealSuccess.incrementAndGet();
                            }
                        } catch (ConflictException ex) {
                            conflicts.incrementAndGet();
                        } catch (UnprocessableEntityException ex) {
                            // 目标组别先被封榜后，其余批准按整批校验失败处理
                            unprocessable.incrementAndGet();
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

        // 版本互斥：批准与封榜合计至多一个成功；其余为版本冲突或封榜后的整批422
        assertThat(approveSuccess.get() + sealSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get() + unprocessable.get()).isEqualTo(runs - 1);
        if (approveSuccess.get() == 1) {
            assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode())
                    .isEqualTo("B");
            assertThat(repository.findCategory(RACE, "B").orElseThrow().status())
                    .isEqualTo(CategoryStatus.OPEN);
            assertThat(repository.findCategorySnapshot(RACE, "B")).isEmpty();
            assertThat(version()).isEqualTo(expectedVersion + 1);
        } else {
            assertThat(repository.findCategory(RACE, "B").orElseThrow().status())
                    .isEqualTo(CategoryStatus.SEALED);
            assertThat(repository.findCategorySnapshot(RACE, "B")).isPresent();
            assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode())
                    .isEqualTo("A");
            assertThat(repository.findReclassRecords(RACE)).isEmpty();
            assertThat(version()).isEqualTo(expectedVersion + 1);
        }
    }
}
