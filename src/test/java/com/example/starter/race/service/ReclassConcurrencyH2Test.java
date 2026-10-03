package com.example.starter.race.service;

import com.example.starter.race.api.ApproveReclassRequest;
import com.example.starter.race.api.CreateCategoryRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SubmitReclassApplicationRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ReclassRequestStatus;
import com.example.starter.race.persistence.RaceRow;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 组别重分的真实并发测试：申请、批准、退赛与封榜按事务提交顺序裁决，
 * 同 reclassKey 并发只产生一次迁移。
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

    private void setupRaceWithRunner() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.createCategory(RACE, new CreateCategoryRequest("A", 5, 1, "req-cat-a"));
        raceService.createCategory(RACE, new CreateCategoryRequest("B", 5, 2, "req-cat-b"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 1000L, "A", 3, "req-reg-a"));
        raceService.submitReclassApplication(RACE, new SubmitReclassApplicationRequest(
                "app-1", "a", "B", "并发测试", 4, "req-sub-1"));
        // 当前版本：5
    }

    @Test
    void 并发同键批准只迁移一次且重放结果一致() throws Exception {
        setupRaceWithRunner();
        ApproveReclassRequest request =
                new ApproveReclassRequest("k1", "op-1", 5, List.of("app-1"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        java.util.List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            var futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            ServiceResult result = raceService.approveReclass(RACE, request);
                            success.incrementAndGet();
                            bodies.add(mapper.writeValueAsString(result.body()));
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

        // 同键同参：全部成功且响应一致（一次真实迁移 + 其余重放）
        assertThat(conflicts.get()).isZero();
        assertThat(success.get()).isEqualTo(threads);
        assertThat(bodies).allMatch(body -> body.equals(bodies.getFirst()));
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(6);
        assertThat(raceService.listReclassRecords(RACE)).hasSize(1);
        assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode()).isEqualTo("B");
    }

    @Test
    void 并发批准与退赛按提交顺序裁决且最终状态自洽() throws Exception {
        setupRaceWithRunner();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger approveSuccess = new AtomicInteger();
        AtomicInteger withdrawSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.approveReclass(RACE, new ApproveReclassRequest(
                                        "k-" + i, "op-1", 5, List.of("app-1")));
                                approveSuccess.incrementAndGet();
                            } else {
                                raceService.withdrawRunner(RACE, "a",
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

        // 同一版本上互斥：恰好一个写成功，其余全部冲突
        assertThat(approveSuccess.get() + withdrawSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(version()).isEqualTo(6);
        var runner = repository.findRunner(RACE, "a").orElseThrow();
        if (approveSuccess.get() == 1) {
            // 批准先提交：选手已迁移且未退赛
            assertThat(runner.categoryCode()).isEqualTo("B");
            assertThat(runner.withdrawn()).isFalse();
            assertThat(raceService.listReclassRecords(RACE)).hasSize(1);
            assertThat(raceService.listReclassApplications(RACE).getFirst().status())
                    .isEqualTo(ReclassRequestStatus.APPROVED);
        } else {
            // 退赛先提交：选手留在原组别且申请仍待审批
            assertThat(runner.categoryCode()).isEqualTo("A");
            assertThat(runner.withdrawn()).isTrue();
            assertThat(raceService.listReclassRecords(RACE)).isEmpty();
            assertThat(raceService.listReclassApplications(RACE).getFirst().status())
                    .isEqualTo(ReclassRequestStatus.PENDING);
        }
    }

    @Test
    void 并发批准与封榜互斥且最终状态自洽() throws Exception {
        setupRaceWithRunner();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger approveSuccess = new AtomicInteger();
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.approveReclass(RACE, new ApproveReclassRequest(
                                        "k-" + i, "op-1", 5, List.of("app-1")));
                                approveSuccess.incrementAndGet();
                            } else {
                                raceService.sealRace(RACE,
                                        new SealRaceRequest(5, "req-seal-" + i));
                                sealSuccess.incrementAndGet();
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

        assertThat(approveSuccess.get() + sealSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(6);
        if (sealSuccess.get() == 1) {
            // 封榜先提交：快照中选手仍属来源组别，无重分记录
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            assertThat(raceService.listReclassRecords(RACE)).isEmpty();
            assertThat(raceService.getSnapshot(RACE).entries())
                    .filteredOn(entry -> entry.bib().equals("a"))
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.categoryCode()).isEqualTo("A"));
        } else {
            // 批准先提交：选手已迁移，赛事仍可封榜且快照属目标组别
            assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
            assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode())
                    .isEqualTo("B");
            raceService.sealRace(RACE, new SealRaceRequest(6, "req-seal-final"));
            assertThat(raceService.getSnapshot(RACE).entries())
                    .filteredOn(entry -> entry.bib().equals("a"))
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.categoryCode()).isEqualTo("B"));
        }
    }
}