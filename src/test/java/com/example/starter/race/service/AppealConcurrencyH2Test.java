package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ConfirmAppealRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RecommendAppealRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitAppealRequest;
import com.example.starter.race.domain.AppealStatus;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceRow;
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
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 申诉裁决的 H2 真实并发测试：裁决与计时修订/其他裁决按提交顺序串行，
 * 不会出现处罚已改而榜单未重算的中间态。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class AppealConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-appeal-concurrent";

    @Autowired
    private RaceService raceService;

    /** 建赛、登记 a/b 并对 a 加时；返回当前版本 4。 */
    private int setupRaceWithPenalty() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 1000L, 1, "req-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", 1000L, 2, "req-b"));
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-1", "a", "ADD_TIME", 500L, 3, "req-p1"));
        return 4;
    }

    private void submitAndRecommend(String appealKey, int expectedVersion) {
        raceService.submitAppeal(RACE, new SubmitAppealRequest(
                appealKey, "a", "p-1", 1, "判罚有误", expectedVersion, "req-ak-" + appealKey));
        raceService.recommendAppeal(RACE, appealKey,
                new RecommendAppealRequest("off-1", "REMOVE", null, "req-r-" + appealKey));
    }

    @Test
    void 并发确认与计时修订互斥且最终状态自洽() throws Exception {
        setupRaceWithPenalty();
        submitAndRecommend("ak-1", 4);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger confirmSuccess = new AtomicInteger();
        AtomicInteger reviseSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.confirmAppeal(RACE, "ak-1",
                                        new ConfirmAppealRequest("off-2", "CONFIRM", "REMOVE",
                                                null, "req-c-" + i));
                                confirmSuccess.incrementAndGet();
                            } else {
                                raceService.reviseTime(RACE,
                                        new ReviseTimeRequest("b", 900L, 4, "req-t-" + i));
                                reviseSuccess.incrementAndGet();
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

        // 确认与修订都基于版本4：恰好一类成功一次，其余全部冲突
        assertThat(confirmSuccess.get() + reviseSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(5);
        PenaltyRow penalty = repository.findPenalty("p-1").orElseThrow();
        if (confirmSuccess.get() == 1) {
            // 裁决成功：处罚撤销与榜单重算同一事务完成，不存在处罚已改而榜单未重算
            assertThat(penalty.revoked()).isTrue();
            assertThat(repository.findAppeal("ak-1").orElseThrow().status())
                    .isEqualTo(AppealStatus.REMOVED);
            assertThat(repository.findAppeal("ak-1").orElseThrow().newLeaderboardVersion())
                    .isEqualTo(5);
            StandingResponse standing = raceService.getResults(RACE);
            assertThat(standing.version()).isEqualTo(5);
            assertThat(standing.entries()).allMatch(entry -> entry.rank() != null
                    && entry.rank() == 1);
        } else {
            // 修订成功：申诉仍待决，处罚与冻结现场不变
            assertThat(penalty.revoked()).isFalse();
            assertThat(repository.findAppeal("ak-1").orElseThrow().status())
                    .isEqualTo(AppealStatus.PENDING);
            assertThat(raceService.getResults(RACE).entries().get(1).appealPending()).isTrue();
        }
    }

    @Test
    void 并发相同appealKey只受理一次() throws Exception {
        setupRaceWithPenalty();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            raceService.submitAppeal(RACE, new SubmitAppealRequest(
                                    "ak-dup", "a", "p-1", 1, "判罚有误", 4, "req-ak-" + i));
                            created.incrementAndGet();
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

        assertThat(created.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(repository.findAppeals(RACE)).hasSize(1);
    }

    @Test
    void 并发确认同一申诉只成功一次且榜单版本只加一() throws Exception {
        setupRaceWithPenalty();
        submitAndRecommend("ak-1", 4);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            raceService.confirmAppeal(RACE, "ak-1",
                                    new ConfirmAppealRequest("off-" + (i + 2), "CONFIRM", "REMOVE",
                                            null, "req-c-" + i));
                            success.incrementAndGet();
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

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        // 榜单版本只推进一次，处罚只撤销一次
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
        assertThat(repository.findAppeal("ak-1").orElseThrow().newLeaderboardVersion())
                .isEqualTo(5);
        PenaltyRow penalty = repository.findPenalty("p-1").orElseThrow();
        assertThat(penalty.revoked()).isTrue();
        assertThat(penalty.version()).isEqualTo(2);
    }
}
