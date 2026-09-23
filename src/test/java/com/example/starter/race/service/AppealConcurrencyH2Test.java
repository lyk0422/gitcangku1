package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.AppealOpinionRequest;
import com.example.starter.race.api.AppealResponse;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitAppealRequest;
import com.example.starter.race.domain.AppealStatus;
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
 * 申诉裁决并发 H2 测试：race/appeal 行锁串行化两干事裁决与其它写者，
 * 裁决最多生效一次且版本只推进一次；裁决与普通写交错时最终榜单始终自洽，
 * 不出现“处罚已改而榜单未重算”。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class AppealConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-appeal-concurrent";
    private static final String PENALTY = "pen-b";
    private static final String APPEAL = "appeal-1";

    @Autowired
    private RaceService raceService;

    /** 建赛、a/b 各1000、b 加时500（v4）、受理申诉并由第一人建议 REMOVE（不推进版本）。 */
    private void seedPendingRemoval() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 1000L, 1, "req-a"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", 1000L, 2, "req-b"));
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                PENALTY, "b", "ADD_TIME", 500L, 3, "req-pen-b"));
        raceService.submitAppeal(RACE, PENALTY,
                new SubmitAppealRequest(APPEAL, 1, "理由", "req-appeal"));
        raceService.submitAppealOpinion(RACE, APPEAL,
                new AppealOpinionRequest("steward-1", "REMOVE", null, null, "req-first"));
    }

    @Test
    void 并发第二人确认仅一个生效且版本只加一() throws Exception {
        seedPendingRemoval();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            raceService.submitAppealOpinion(RACE, APPEAL,
                                    new AppealOpinionRequest("steward-2", "REMOVE", null,
                                            "CONFIRM", "req-confirm-" + i));
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

        // 同一时刻只有一个裁决事务能在 PENDING 上提交，其余全部409。
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        AppealResponse appeal = raceService.getAppeal(RACE, APPEAL);
        assertThat(appeal.status()).isEqualTo(AppealStatus.REMOVED);
        assertThat(appeal.secondStewardId()).isEqualTo("steward-2");
        // 裁决只生成一个新 leaderboardVersion：v4 -> v5。
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
        assertThat(repository.findPenalty(PENALTY).orElseThrow().revoked()).isTrue();
        StandingResponse standing = raceService.getResults(RACE);
        ResultEntryResponse b = standing.entries().stream()
                .filter(e -> e.bib().equals("b")).findFirst().orElseThrow();
        assertThat(b.totalTimeMs()).isEqualTo(1000L);
        assertThat(b.rank()).isEqualTo(1);
        assertThat(b.appealPending()).isFalse();
    }

    @Test
    void 裁决与处罚新增并发时按提交顺序且最终榜单自洽() throws Exception {
        seedPendingRemoval();

        int writers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger decisionSuccess = new AtomicInteger();
        AtomicInteger penaltySuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            // 一个裁决事务 + 多个给 a 加时的普通写，全部以版本4并发提交。
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
            tasks.add(() -> {
                start.await();
                try {
                    raceService.submitAppealOpinion(RACE, APPEAL,
                            new AppealOpinionRequest("steward-2", "REMOVE", null,
                                    "CONFIRM", "req-decision"));
                    decisionSuccess.incrementAndGet();
                } catch (ConflictException ex) {
                    conflicts.incrementAndGet();
                }
                return null;
            });
            for (int i = 0; i < writers; i++) {
                final int index = i;
                tasks.add(() -> {
                    start.await();
                    try {
                        raceService.addPenalty(RACE, new AddPenaltyRequest(
                                "pen-a-" + index, "a", "ADD_TIME", 100L, 4,
                                "req-pen-a-" + index));
                        penaltySuccess.incrementAndGet();
                    } catch (ConflictException ex) {
                        conflicts.incrementAndGet();
                    }
                    return null;
                });
            }
            var futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 裁决必定恰好成功一次；成功写者总数 = 版本增量；其余全部版本冲突。
        assertThat(decisionSuccess.get()).isEqualTo(1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        int successfulWrites = decisionSuccess.get() + penaltySuccess.get();
        assertThat(race.version()).isEqualTo(4 + successfulWrites);
        assertThat(conflicts.get()).isEqualTo((writers + 1) - successfulWrites);

        // 最终一致性：REMOVE 已生效（b 无加时），榜单按最新数据重算，无半截状态。
        AppealResponse appeal = raceService.getAppeal(RACE, APPEAL);
        assertThat(appeal.status()).isEqualTo(AppealStatus.REMOVED);
        StandingResponse standing = raceService.getResults(RACE);
        ResultEntryResponse b = standing.entries().stream()
                .filter(e -> e.bib().equals("b")).findFirst().orElseThrow();
        assertThat(b.penaltyMs()).isZero();
        assertThat(b.totalTimeMs()).isEqualTo(1000L);
        ResultEntryResponse a = standing.entries().stream()
                .filter(e -> e.bib().equals("a")).findFirst().orElseThrow();
        // 每个成功的 a 加时都必须已反映在同一榜单版本中（不会漏算）。
        assertThat(a.penaltyMs()).isEqualTo(100L * penaltySuccess.get());
        assertThat(a.totalTimeMs()).isEqualTo(1000L + 100L * penaltySuccess.get());
        // 裁决重算后的榜单版本必须等于终态赛事版本。
        assertThat(appeal.afterLeaderboard().version()).isEqualTo(race.version());
    }
}
