package com.example.starter.race.service;

import com.example.starter.race.api.ConfigureDepositRequest;
import com.example.starter.race.api.CreateProtestRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.DecideProtestRequest;
import com.example.starter.race.api.ProtestResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.TopUpWalletRequest;
import com.example.starter.race.api.WalletResponse;
import com.example.starter.race.api.WalletTransactionResponse;
import com.example.starter.race.domain.ProtestStatus;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.WalletTransactionRow;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 申诉保证金的真实 H2 数据库测试：冻结/退回/没收账务、余额不足失败分支、
 * requestId 与第二层幂等键重放、终态重复裁决、配置快照、裁决与封榜并发互斥。
 * 金额单位均为人民币元。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class ProtestDepositH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-protest";
    private static final String BIB = "a";

    @Autowired
    private RaceService raceService;

    /** 建赛(v1) → 配置保证金100(v2) → 登记选手(v3)。 */
    private void seedRaceWithDeposit100() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.configureDeposit(RACE,
                new ConfigureDepositRequest(100, 1, "req-deposit"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(BIB, 1000L, 2, "req-reg"));
    }

    private void assertBalance(int expected) {
        assertThat(raceService.getWallet(RACE, BIB).balance()).isEqualTo(expected);
    }

    @Test
    void 余额不足创建申诉失败且不冻结不锁成绩() {
        seedRaceWithDeposit100();
        // v4：充值80，余额80 < 保证金100（充值也推进版本：1→2→3→4）
        raceService.topUpWallet(RACE, BIB,
                new TopUpWalletRequest("topup-1", 80, 3, "req-topup-80"));
        assertBalance(80);

        assertThatThrownBy(() -> raceService.createProtest(RACE, BIB,
                new CreateProtestRequest("protest-x", "对判罚有异议", 4, "req-protest-x")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("余额不足");

        // 不创建申诉、不扣余额、版本不前进
        assertThat(raceService.getProtests(RACE).protests()).isEmpty();
        assertBalance(80);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
        // 不锁成绩：没有未裁决申诉，封榜正常成功（v4→v5）
        raceService.sealRace(RACE, new SealRaceRequest(4, "req-seal"));
        assertThat(repository.findRace(RACE).orElseThrow().status())
                .isEqualTo(com.example.starter.race.domain.RaceStatus.SEALED);
    }

    @Test
    void 冻结后ACCEPT全额退回且只能退回一次() {
        seedRaceWithDeposit100();
        raceService.topUpWallet(RACE, BIB,
                new TopUpWalletRequest("topup-80", 80, 3, "req-topup-80"));
        // 第二次充值凑足100（上一次充值后版本为4）
        raceService.topUpWallet(RACE, BIB,
                new TopUpWalletRequest("topup-20", 20, 4, "req-topup-20"));
        assertBalance(100);

        // v6：创建申诉，冻结100（v5）
        ProtestResponse created = (ProtestResponse) raceService.createProtest(RACE, BIB,
                new CreateProtestRequest("protest-1", "对判罚有异议", 5, "req-protest-1"))
                .body();
        assertThat(created.status()).isEqualTo("PENDING");
        assertThat(created.depositAmount()).isEqualTo(100);
        assertBalance(0);

        // 存在未裁决申诉：封榜409，成绩暂缓（携带正确版本6以命中暂缓分支）
        assertThatThrownBy(() -> raceService.sealRace(RACE, new SealRaceRequest(6, "req-seal")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("未裁决申诉");
        assertThat(repository.findSnapshot(RACE)).isEmpty();

        // v7：ACCEPT 全额退回
        ProtestResponse accepted = (ProtestResponse) raceService.decideProtest(
                RACE, "protest-1",
                new DecideProtestRequest("ACCEPT", "申诉成立", 6, "req-decide-1"))
                .body();
        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertThat(accepted.decidedAt()).isNotNull();
        assertBalance(100);

        // 终态重复裁决（携带正确新版本7也不行）：409，不再次退款
        assertThatThrownBy(() -> raceService.decideProtest(
                RACE, "protest-1",
                new DecideProtestRequest("ACCEPT", "再次裁决", 7, "req-decide-again-1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("终态");
        assertThatThrownBy(() -> raceService.decideProtest(
                RACE, "protest-1",
                new DecideProtestRequest("ACCEPT", "再次裁决", 7, "req-decide-again-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("终态");
        // 终态改判 REJECT：同样409
        assertThatThrownBy(() -> raceService.decideProtest(
                RACE, "protest-1",
                new DecideProtestRequest("REJECT", "改判", 7, "req-decide-change")))
                .isInstanceOf(ConflictException.class);
        assertBalance(100);

        List<WalletTransactionRow> rows = repository.findWalletTransactions(RACE, BIB);
        assertThat(rows).extracting(WalletTransactionRow::type).containsExactly(
                com.example.starter.race.domain.WalletTransactionType.TOPUP,
                com.example.starter.race.domain.WalletTransactionType.TOPUP,
                com.example.starter.race.domain.WalletTransactionType.FREEZE,
                com.example.starter.race.domain.WalletTransactionType.REFUND);
        assertThat(rows).extracting(WalletTransactionRow::amount)
                .containsExactly(80, 20, 100, 100);
        assertThat(rows).extracting(WalletTransactionRow::balanceAfter)
                .containsExactly(80, 100, 0, 100);
        assertThat(rows.stream().filter(r -> r.type()
                == com.example.starter.race.domain.WalletTransactionType.REFUND).count())
                .isEqualTo(1);

        // 裁决后无未裁决申诉：封榜成功，快照保留当时申诉与裁决依据
        raceService.sealRace(RACE, new SealRaceRequest(7, "req-seal"));
        var snapshot = repository.findSnapshot(RACE).orElseThrow();
        assertThat(snapshot.protests()).hasSize(1);
        assertThat(snapshot.protests().getFirst().status()).isEqualTo(ProtestStatus.ACCEPTED);
        assertThat(snapshot.protests().getFirst().depositAmount()).isEqualTo(100);
        assertThat(snapshot.protests().getFirst().reason()).isEqualTo("对判罚有异议");
        assertThat(snapshot.protests().getFirst().decisionReason()).isEqualTo("申诉成立");
    }

    @Test
    void REJECT全部没收且余额不恢复() {
        seedRaceWithDeposit100();
        raceService.topUpWallet(RACE, BIB,
                new TopUpWalletRequest("topup-100", 100, 3, "req-topup-100"));
        raceService.createProtest(RACE, BIB,
                new CreateProtestRequest("protest-r", "无理申诉", 4, "req-protest-r"));
        assertBalance(0);

        // v5：REJECT 没收
        ProtestResponse rejected = (ProtestResponse) raceService.decideProtest(
                RACE, "protest-r",
                new DecideProtestRequest("REJECT", null, 5, "req-decide-r"))
                .body();
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertBalance(0);

        List<WalletTransactionRow> rows = repository.findWalletTransactions(RACE, BIB);
        assertThat(rows).extracting(WalletTransactionRow::type).containsExactly(
                com.example.starter.race.domain.WalletTransactionType.TOPUP,
                com.example.starter.race.domain.WalletTransactionType.FREEZE,
                com.example.starter.race.domain.WalletTransactionType.FORFEIT);
        assertThat(rows).extracting(WalletTransactionRow::amount).containsExactly(100, 100, 100);
        // 没收不改变可用余额
        assertThat(rows.getLast().type())
                .isEqualTo(com.example.starter.race.domain.WalletTransactionType.FORFEIT);
        assertThat(rows.getLast().balanceAfter()).isZero();

        // 封榜成功，快照保留 REJECTED 依据
        raceService.sealRace(RACE, new SealRaceRequest(6, "req-seal"));
        assertThat(repository.findSnapshot(RACE).orElseThrow().protests().getFirst().status())
                .isEqualTo(ProtestStatus.REJECTED);
    }

    @Test
    void 保证金配置变化不影响已有申诉快照金额() {
        seedRaceWithDeposit100();
        raceService.topUpWallet(RACE, BIB,
                new TopUpWalletRequest("topup-200", 300, 3, "req-topup-200"));
        // v4：按100冻结
        raceService.createProtest(RACE, BIB,
                new CreateProtestRequest("protest-snap", "理由", 4, "req-protest-snap"));
        assertBalance(200);
        // v5：保证金配置改为200
        raceService.configureDeposit(RACE,
                new ConfigureDepositRequest(200, 5, "req-deposit-200"));
        // v6：裁决仍按快照100退回
        raceService.decideProtest(RACE, "protest-snap",
                new DecideProtestRequest("ACCEPT", null, 6, "req-decide-snap"));
        assertBalance(300);
        WalletTransactionRow refund = repository.findWalletTransactions(RACE, BIB).stream()
                .filter(r -> r.type() == com.example.starter.race.domain.WalletTransactionType.REFUND)
                .findFirst().orElseThrow();
        assertThat(refund.amount()).isEqualTo(100);
        assertThat(repository.findProtest("protest-snap").orElseThrow().depositAmount())
                .isEqualTo(100);
    }

    @Test
    void 未配置保证金时创建申诉返回422() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(BIB, 1000L, 1, "req-reg"));
        assertThatThrownBy(() -> raceService.createProtest(RACE, BIB,
                new CreateProtestRequest("protest-nodep", "理由", 2, "req-p")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("尚未配置申诉保证金");
        assertThat(raceService.getProtests(RACE).protests()).isEmpty();
    }

    @Test
    void 创建申诉requestId与protestId重放首次结果异参409() {
        seedRaceWithDeposit100();
        raceService.topUpWallet(RACE, BIB,
                new TopUpWalletRequest("topup-100", 100, 3, "req-topup-100"));

        var request = new CreateProtestRequest("protest-replay", "理由", 4, "req-create-replay");
        var first = (ProtestResponse) raceService.createProtest(RACE, BIB, request).body();
        // 同 requestId 同参重放（重放体为缓存JSON，这里只断言不报错、不重复冻结，状态以查询为准）
        raceService.createProtest(RACE, BIB, request);
        assertThat(raceService.getProtest(RACE, "protest-replay").protestId())
                .isEqualTo(first.protestId());
        assertBalance(0);
        // 同 requestId 异参409
        assertThatThrownBy(() -> raceService.createProtest(RACE, BIB,
                new CreateProtestRequest("protest-replay", "不同理由", 4, "req-create-replay")))
                .isInstanceOf(ConflictException.class);

        // 同 protestId 不同 requestId、同参数：走第二层幂等重放，不重复冻结
        raceService.createProtest(RACE, BIB,
                new CreateProtestRequest("protest-replay", "理由", 4, "req-other-key"));
        assertThat(raceService.getProtest(RACE, "protest-replay").status())
                .isEqualTo("PENDING");
        assertBalance(0);
        // 同 protestId 不同理由（改申诉）：409
        assertThatThrownBy(() -> raceService.createProtest(RACE, BIB,
                new CreateProtestRequest("protest-replay", "又一个理由", 4, "req-other-reason")))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findWalletTransactions(RACE, BIB)).hasSize(2);
    }

    @Test
    void 裁决requestId重放首次账务结果改裁决409() {
        seedRaceWithDeposit100();
        raceService.topUpWallet(RACE, BIB,
                new TopUpWalletRequest("topup-100", 100, 3, "req-topup-100"));
        raceService.createProtest(RACE, BIB,
                new CreateProtestRequest("protest-decide", "理由", 4, "req-protest-d"));

        var decide = new DecideProtestRequest("ACCEPT", "成立", 5, "req-decide-replay");
        raceService.decideProtest(RACE, "protest-decide", decide);
        assertBalance(100);
        // 同 requestId 同参重放：不报错、仍只退一次（重放体为缓存JSON，状态以查询为准）
        raceService.decideProtest(RACE, "protest-decide", decide);
        assertThat(raceService.getProtest(RACE, "protest-decide").status())
                .isEqualTo("ACCEPTED");
        assertBalance(100);
        assertThat(repository.findWalletTransactions(RACE, BIB).stream()
                .filter(r -> r.type() == com.example.starter.race.domain.WalletTransactionType.REFUND)
                .count()).isEqualTo(1);
        // 同 requestId 改为 REJECT：409
        assertThatThrownBy(() -> raceService.decideProtest(RACE, "protest-decide",
                new DecideProtestRequest("REJECT", null, 5, "req-decide-replay")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 充值transactionId重放首次余额异参409() {
        seedRaceWithDeposit100();
        var topup = new TopUpWalletRequest("topup-fixed", 50, 3, "req-topup-fixed");
        WalletResponse first = (WalletResponse) raceService.topUpWallet(RACE, BIB, topup).body();
        assertThat(first.balance()).isEqualTo(50);
        // 重放首次账务结果（入账后余额50，而非当前余额累加；重放体为缓存JSON，以查询核对）
        raceService.topUpWallet(RACE, BIB, topup);
        assertBalance(50);
        // 同 transactionId 不同金额：409
        assertThatThrownBy(() -> raceService.topUpWallet(RACE, BIB,
                new TopUpWalletRequest("topup-fixed", 60, 3, "req-topup-fixed")))
                .isInstanceOf(ConflictException.class);
        // 同 transactionId 用于其他选手：409
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", 1000L, 4, "req-reg-b"));
        assertThatThrownBy(() -> raceService.topUpWallet(RACE, "b",
                new TopUpWalletRequest("topup-fixed", 50, 4, "req-topup-other-bib")))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findWalletTransactions(RACE, BIB)).hasSize(1);
    }

    @Test
    void 并发重复裁决恰好一次退款() throws Exception {
        seedRaceWithDeposit100();
        raceService.topUpWallet(RACE, BIB,
                new TopUpWalletRequest("topup-100", 100, 3, "req-topup-100"));
        raceService.createProtest(RACE, BIB,
                new CreateProtestRequest("protest-conc", "理由", 4, "req-protest-conc"));
        // 当前版本5：6个不同 requestId 并发 ACCEPT
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
                            raceService.decideProtest(RACE, "protest-conc",
                                    new DecideProtestRequest("ACCEPT", null, 5,
                                            "req-decide-conc-" + i));
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
        assertBalance(100);
        assertThat(repository.findProtest("protest-conc").orElseThrow().status())
                .isEqualTo(ProtestStatus.ACCEPTED);
        assertThat(repository.findWalletTransactions(RACE, BIB).stream()
                .filter(r -> r.type() == com.example.starter.race.domain.WalletTransactionType.REFUND)
                .count()).isEqualTo(1);
    }

    @Test
    void 裁决与封榜并发时不会同时出现未裁决申诉和已封榜成绩() throws Exception {
        seedRaceWithDeposit100();
        raceService.topUpWallet(RACE, BIB,
                new TopUpWalletRequest("topup-100", 100, 3, "req-topup-100"));
        raceService.createProtest(RACE, BIB,
                new CreateProtestRequest("protest-seal", "理由", 4, "req-protest-seal"));
        // 当前版本5：裁决(ACCEPT)与封榜各4个线程、同 expectedVersion=5 并发
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger decideSuccess = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.sealRace(RACE,
                                        new SealRaceRequest(5, "req-seal-conc-" + i));
                                sealSuccess.incrementAndGet();
                            } else {
                                raceService.decideProtest(RACE, "protest-seal",
                                        new DecideProtestRequest("ACCEPT", null, 5,
                                                "req-decide-seal-" + i));
                                decideSuccess.incrementAndGet();
                            }
                        } catch (ConflictException ex) {
                            // 版本冲突或暂缓封榜
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

        // 存在PENDING时封榜必被拒；同版本竞争下裁决最多成功一次，封榜本次不会成功
        assertThat(sealSuccess.get()).isZero();
        assertThat(decideSuccess.get()).isEqualTo(1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.status()).isEqualTo(com.example.starter.race.domain.RaceStatus.OPEN);
        assertThat(repository.findSnapshot(RACE)).isEmpty();
        assertThat(repository.findProtest("protest-seal").orElseThrow().status())
                .isEqualTo(ProtestStatus.ACCEPTED);
        assertBalance(100);

        // 裁决完成后再封榜：成功，且快照中申诉为终态——不变量“封榜时无未裁决申诉”成立
        raceService.sealRace(RACE, new SealRaceRequest(6, "req-seal-after"));
        assertThat(repository.countPendingProtests(RACE)).isZero();
        var snapshot = repository.findSnapshot(RACE).orElseThrow();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.protests()).extracting(p -> p.status())
                .doesNotContain(ProtestStatus.PENDING);
    }
}
