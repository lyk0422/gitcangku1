package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RegisterWithdrawalRequest;
import com.example.starter.race.api.RevokeWithdrawalRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.domain.RaceStatus;
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
 * 退赛相关写操作的 H2 真实并发测试：退赛登记、撤销、分段提交与封榜
 * 以赛事 expectedVersion 串行化，互斥操作恰好一方成功。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class WithdrawalConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-wd-concurrent";

    @Autowired
    private RaceService raceService;

    @Test
    void 并发同状态退赛登记仅一条生效且版本只加一() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", null, 1, "req-a"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            raceService.registerWithdrawal(RACE, new RegisterWithdrawalRequest(
                                    "w-" + i, "a", "DNS", "未到场", null, 2, "req-w-" + i));
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

        // 同版本并发写串行化：恰好一个登记成功，其余版本冲突；
        // 最终只存在一条生效退赛记录且版本只加一。
        assertThat(created.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(3);
        assertThat(repository.findWithdrawals(RACE)).hasSize(1);
        assertThat(repository.findActiveWithdrawal(RACE, "a")).isPresent();
    }

    @Test
    void 退赛登记与计时修订并发互斥且最终状态自洽() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", null, 1, "req-a"));
        // 当前版本2：计时修订与 DNS 登记都以 expectedVersion=2 并发
        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reviseSuccess = new AtomicInteger();
        AtomicInteger withdrawalSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, runs)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.reviseTime(RACE, new com.example.starter.race.api
                                        .ReviseTimeRequest("a", 5_000L, 2, "req-t-" + i));
                                reviseSuccess.incrementAndGet();
                            } else {
                                raceService.registerWithdrawal(RACE, new RegisterWithdrawalRequest(
                                        "w-" + i, "a", "DNS", "未到场", null, 2, "req-w-" + i));
                                withdrawalSuccess.incrementAndGet();
                            }
                        } catch (ConflictException ex) {
                            // 版本冲突或退赛后禁止提交完赛计时均为合法失败
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

        // 两类写互斥：恰好一类成功一次，版本只加一，最终状态自洽
        assertThat(reviseSuccess.get() + withdrawalSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(runs - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(3);
        if (reviseSuccess.get() == 1) {
            assertThat(repository.findRunner(RACE, "a").orElseThrow().finishTimeMs())
                    .isEqualTo(5_000L);
            assertThat(repository.findWithdrawals(RACE)).isEmpty();
        } else {
            assertThat(repository.findRunner(RACE, "a").orElseThrow().finishTimeMs()).isNull();
            assertThat(repository.findActiveWithdrawal(RACE, "a")).isPresent();
        }
    }

    @Test
    void 退赛登记与封榜并发互斥() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", null, 1, "req-a"));
        // 当前版本2：退赛登记与封榜都以 expectedVersion=2 并发
        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger withdrawalSuccess = new AtomicInteger();
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, runs)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.registerWithdrawal(RACE, new RegisterWithdrawalRequest(
                                        "w-" + i, "a", "DNS", "未到场", null, 2, "req-w-" + i));
                                withdrawalSuccess.incrementAndGet();
                            } else {
                                raceService.sealRace(RACE,
                                        new SealRaceRequest(2, "req-seal-" + i));
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

        assertThat(withdrawalSuccess.get() + sealSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(runs - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(3);
        if (sealSuccess.get() == 1) {
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            assertThat(repository.findWithdrawals(RACE)).isEmpty();
        } else {
            assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
            assertThat(repository.findActiveWithdrawal(RACE, "a")).isPresent();
            assertThat(repository.findSnapshot(RACE)).isEmpty();
        }
    }

    @Test
    void 撤销退赛与封榜并发互斥() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", null, 1, "req-a"));
        raceService.registerWithdrawal(RACE, new RegisterWithdrawalRequest(
                "w-1", "a", "DNS", "未到场", null, 2, "req-w-1"));
        // 当前版本3：撤销与封榜都以 expectedVersion=3 并发
        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger revokeSuccess = new AtomicInteger();
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, runs)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.revokeWithdrawal(RACE, "w-1",
                                        new RevokeWithdrawalRequest(3, "req-rv-" + i));
                                revokeSuccess.incrementAndGet();
                            } else {
                                raceService.sealRace(RACE,
                                        new SealRaceRequest(3, "req-seal-" + i));
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

        assertThat(revokeSuccess.get() + sealSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(runs - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(4);
        boolean revoked = repository.findWithdrawal("w-1").orElseThrow().revoked();
        if (sealSuccess.get() == 1) {
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            // 封榜生效则撤销未发生，退赛记录仍生效
            assertThat(revoked).isFalse();
        } else {
            assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
            assertThat(revoked).isTrue();
            assertThat(repository.findSnapshot(RACE)).isEmpty();
        }
    }
}
