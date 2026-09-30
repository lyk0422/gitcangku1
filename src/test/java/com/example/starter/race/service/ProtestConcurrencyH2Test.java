package com.example.starter.race.service;

import com.example.starter.race.api.AdjudicateProtestRequest;
import com.example.starter.race.api.ConfigureDepositRequest;
import com.example.starter.race.api.CreateAccountRequest;
import com.example.starter.race.api.CreateProtestRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RechargeRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.domain.LedgerType;
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
 * 申诉与封榜/裁决并发的真实 H2 测试：
 * 赛事行锁串行化创建申诉与封榜，二者不可能出现“未裁决申诉 + 已封榜”并存；
 * 申诉行锁 + 条件终态 UPDATE 保证并发裁决只退款一次。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class ProtestConcurrencyH2Test extends AbstractRaceH2Test {

    private static final long YUAN = 100L;

    @Autowired
    private RaceService raceService;

    @Test
    void 创建申诉与封榜并发时绝不出现未裁决申诉和已封榜并存() throws Exception {
        String raceId = "race-px-seal";
        String accountId = "acc-px";
        raceService.createRace(new CreateRaceRequest(raceId, "req-create"));
        raceService.configureDeposit(raceId,
                new ConfigureDepositRequest(100 * YUAN, 1, "req-deposit"));
        raceService.createAccount(new CreateAccountRequest(
                accountId, "选手", "req-account"));
        raceService.recharge(accountId, new RechargeRequest(500 * YUAN, "req-recharge"));
        // 当前版本2：一半线程创建申诉、一半封榜，全部以 expectedVersion=2 并发提交。
        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger protestSuccess = new AtomicInteger();
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, runs)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.createProtest(raceId, new CreateProtestRequest(
                                        "p-" + i, accountId, "并发申诉" + i, 2,
                                        "req-protest-" + i));
                                protestSuccess.incrementAndGet();
                            } else {
                                raceService.sealRace(raceId,
                                        new SealRaceRequest(2, "req-seal-" + i));
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

        RaceRow race = repository.findRace(raceId).orElseThrow();
        if (race.status() == RaceStatus.SEALED) {
            // 封榜先得手：必然没有任何申诉（创建都被行锁后的 SEALED 检查挡回），快照存在。
            assertThat(protestSuccess.get()).isZero();
            assertThat(sealSuccess.get()).isEqualTo(1);
            assertThat(repository.findProtestsByRace(raceId)).isEmpty();
            assertThat(repository.findSnapshot(raceId)).isPresent();
            assertThat(repository.countPendingProtests(raceId)).isZero();
        } else {
            // 创建申诉先得手：赛事保持 OPEN，至少一个 PENDING，封榜无一成功。
            assertThat(sealSuccess.get()).isZero();
            assertThat(protestSuccess.get()).isGreaterThanOrEqualTo(1);
            assertThat(repository.findSnapshot(raceId)).isEmpty();
            assertThat(repository.countPendingProtests(raceId))
                    .isEqualTo(protestSuccess.get());
        }
        // 每个失败的请求都回滚，幂等占位行随回滚消失，账务余额只按成功申诉冻结。
        long expectedFrozen = (long) protestSuccess.get() * 100 * YUAN;
        assertThat(raceService.getAccount(accountId).balanceFen())
                .isEqualTo(500 * YUAN - expectedFrozen);
    }

    @Test
    void 并发终态裁决只有一次退款() throws Exception {
        String raceId = "race-decide-concurrent";
        String accountId = "acc-decide";
        raceService.createRace(new CreateRaceRequest(raceId, "req-create"));
        raceService.configureDeposit(raceId,
                new ConfigureDepositRequest(100 * YUAN, 1, "req-deposit"));
        raceService.createAccount(new CreateAccountRequest(
                accountId, "选手", "req-account"));
        raceService.recharge(accountId, new RechargeRequest(200 * YUAN, "req-recharge"));
        raceService.createProtest(raceId, new CreateProtestRequest(
                "p-decide", accountId, "并发裁决", 2, "req-protest"));
        // 冻结后100元；当前版本3。

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
                            raceService.adjudicateProtest("p-decide",
                                    new AdjudicateProtestRequest(
                                            "ACCEPT", 3, "req-decide-" + i));
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

        // 恰好一次裁决成功、只退一次100元；其余全部409。
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(raceService.getAccount(accountId).balanceFen()).isEqualTo(200 * YUAN);
        assertThat(repository.findLedgers(accountId))
                .filteredOn(l -> l.type() == LedgerType.REFUND).hasSize(1);
        assertThat(repository.findProtest("p-decide").orElseThrow().status().name())
                .isEqualTo("ACCEPTED");
    }
}
