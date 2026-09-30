package com.example.starter.race.service;

import com.example.starter.race.api.AccountResponse;
import com.example.starter.race.api.AdjudicateProtestRequest;
import com.example.starter.race.api.ConfigureDepositRequest;
import com.example.starter.race.api.CreateAccountRequest;
import com.example.starter.race.api.CreateProtestRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.LedgerResponse;
import com.example.starter.race.api.ProtestResponse;
import com.example.starter.race.api.RechargeRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.domain.LedgerType;
import com.example.starter.race.domain.ProtestDecision;
import com.example.starter.race.domain.ProtestStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.LedgerRow;
import com.example.starter.race.persistence.ProtestRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 申诉保证金在真实 H2（MODE=MySQL）上的服务级测试：
 * 冻结/退回/没收账务、固定快照金额、余额不足失败不锁成绩、终态409、
 * requestId 幂等重放、封榜拦截与快照依据。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class ProtestDepositH2Test extends AbstractRaceH2Test {

    /** 1元=100分。 */
    private static final long YUAN = 100L;

    @Autowired
    private RaceService raceService;

    private void seedRaceWithDeposit(String raceId, long depositFen) {
        raceService.createRace(new CreateRaceRequest(raceId, "req-create-" + raceId));
        raceService.configureDeposit(raceId,
                new ConfigureDepositRequest(depositFen, 1, "req-deposit-" + raceId));
    }

    private void seedAccount(String accountId, long balanceFen) {
        raceService.createAccount(new CreateAccountRequest(accountId, "选手-" + accountId,
                "req-account-" + accountId));
        raceService.recharge(accountId,
                new RechargeRequest(balanceFen, "req-recharge-" + accountId));
    }

    @Test
    void 余额足够创建冻结100元_ACCEPT只退回一次100元_重复裁决与改判409() {
        String raceId = "race-accept";
        String accountId = "acc-accept";
        seedRaceWithDeposit(raceId, 100 * YUAN);
        seedAccount(accountId, 200 * YUAN);

        ServiceResult created = raceService.createProtest(raceId, new CreateProtestRequest(
                "p-1", accountId, "计时有误", 2, "req-protest-1"));
        assertThat(created.status()).isEqualTo(201);
        ProtestResponse protest = (ProtestResponse) created.body();
        assertThat(protest.status()).isEqualTo(ProtestStatus.PENDING);
        assertThat(protest.depositFen()).isEqualTo(100 * YUAN);
        assertThat(protest.decision()).isNull();

        // 冻结后可用余额 200-100=100元；流水含 RECHARGE 与 FREEZE，金额固定。
        assertThat(raceService.getAccount(accountId).balanceFen()).isEqualTo(100 * YUAN);
        List<LedgerRow> ledgersAfterFreeze = repository.findLedgers(accountId);
        assertThat(ledgersAfterFreeze).extracting(LedgerRow::type)
                .containsExactly(LedgerType.RECHARGE, LedgerType.FREEZE);
        assertThat(ledgersAfterFreeze.get(1).amountFen()).isEqualTo(100 * YUAN);
        assertThat(ledgersAfterFreeze.get(1).balanceAfterFen()).isEqualTo(100 * YUAN);

        // ACCEPT 全额退回：余额恢复到200元（可用=100+退回100），状态与账务同事务提交。
        ServiceResult accepted = raceService.adjudicateProtest("p-1",
                new AdjudicateProtestRequest("ACCEPT", 3, "req-decide-1"));
        assertThat(accepted.status()).isEqualTo(200);
        ProtestResponse decided = (ProtestResponse) accepted.body();
        assertThat(decided.status()).isEqualTo(ProtestStatus.ACCEPTED);
        assertThat(decided.decision()).isEqualTo(ProtestDecision.ACCEPT);
        assertThat(decided.decidedAt()).isNotNull();
        assertThat(raceService.getAccount(accountId).balanceFen()).isEqualTo(200 * YUAN);

        List<LedgerRow> ledgersAfterAccept = repository.findLedgers(accountId);
        assertThat(ledgersAfterAccept).extracting(LedgerRow::type)
                .containsExactly(LedgerType.RECHARGE, LedgerType.FREEZE, LedgerType.REFUND);
        assertThat(ledgersAfterAccept.get(2).amountFen()).isEqualTo(100 * YUAN);
        assertThat(ledgersAfterAccept.get(2).balanceAfterFen()).isEqualTo(200 * YUAN);

        // 重复裁决（同 ACCEPT）与改判（REJECT）均409，且不得再次退款或没收。
        assertThatThrownBy(() -> raceService.adjudicateProtest("p-1",
                new AdjudicateProtestRequest("ACCEPT", 4, "req-decide-again")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.adjudicateProtest("p-1",
                new AdjudicateProtestRequest("REJECT", 4, "req-decide-change")))
                .isInstanceOf(ConflictException.class);

        assertThat(raceService.getAccount(accountId).balanceFen()).isEqualTo(200 * YUAN);
        assertThat(repository.findLedgers(accountId)).hasSize(3);
        assertThat(repository.findLedgers(accountId)).filteredOn(l -> l.type() == LedgerType.REFUND)
                .hasSize(1);
    }

    @Test
    void 余额80元保证金100元时申诉失败_不创建申诉不锁成绩不写冻结流水() {
        String raceId = "race-insufficient";
        String accountId = "acc-poor";
        seedRaceWithDeposit(raceId, 100 * YUAN);
        seedAccount(accountId, 80 * YUAN);

        assertThatThrownBy(() -> raceService.createProtest(raceId, new CreateProtestRequest(
                "p-poor", accountId, "余额不够", 2, "req-protest-poor")))
                .isInstanceOf(UnprocessableEntityException.class);

        // 余额未动；没有申诉、没有冻结流水；赛事版本仍为2。
        assertThat(raceService.getAccount(accountId).balanceFen()).isEqualTo(80 * YUAN);
        assertThat(repository.findProtest("p-poor")).isEmpty();
        assertThat(repository.findProtestsByRace(raceId)).isEmpty();
        assertThat(repository.findLedgers(accountId)).extracting(LedgerRow::type)
                .containsExactly(LedgerType.RECHARGE);
        assertThat(repository.findRace(raceId).orElseThrow().version()).isEqualTo(2);

        // 成绩未被“锁”：登记选手并直接封榜成功（无未裁决申诉）。
        raceService.registerRunner(raceId,
                new RegisterRunnerRequest("a", 1000L, 2, "req-runner-a"));
        ServiceResult sealed = raceService.sealRace(raceId,
                new SealRaceRequest(3, "req-seal-poor"));
        assertThat(((StandingResponse) sealed.body()).status()).isEqualTo(RaceStatus.SEALED);
    }

    @Test
    void REJECT全部没收_余额不恢复仅一笔没收流水() {
        String raceId = "race-reject";
        String accountId = "acc-reject";
        seedRaceWithDeposit(raceId, 100 * YUAN);
        seedAccount(accountId, 200 * YUAN);

        raceService.createProtest(raceId, new CreateProtestRequest(
                "p-r", accountId, "争议", 2, "req-protest-r"));
        assertThat(raceService.getAccount(accountId).balanceFen()).isEqualTo(100 * YUAN);

        ServiceResult rejected = raceService.adjudicateProtest("p-r",
                new AdjudicateProtestRequest("REJECT", 3, "req-decide-r"));
        ProtestResponse protest = (ProtestResponse) rejected.body();
        assertThat(protest.status()).isEqualTo(ProtestStatus.REJECTED);
        assertThat(protest.decision()).isEqualTo(ProtestDecision.REJECT);

        // 没收：可用余额仍是100元，不再加回；仅新增一笔 FORFEIT 固定金额流水。
        assertThat(raceService.getAccount(accountId).balanceFen()).isEqualTo(100 * YUAN);
        List<LedgerRow> ledgers = repository.findLedgers(accountId);
        assertThat(ledgers).extracting(LedgerRow::type)
                .containsExactly(LedgerType.RECHARGE, LedgerType.FREEZE, LedgerType.FORFEIT);
        assertThat(ledgers.get(2).amountFen()).isEqualTo(100 * YUAN);
        assertThat(ledgers.get(2).balanceAfterFen()).isEqualTo(100 * YUAN);
    }

    @Test
    void 保证金配置变化不影响已有申诉金额_新申诉取新配置() {
        String raceId = "race-config";
        String accountId = "acc-config";
        seedAccount(accountId, 500 * YUAN);
        raceService.createRace(new CreateRaceRequest(raceId, "req-create-config"));
        // v1 -> v2：100元
        raceService.configureDeposit(raceId,
                new ConfigureDepositRequest(100 * YUAN, 1, "req-cfg-1"));
        raceService.createProtest(raceId, new CreateProtestRequest(
                "p-old", accountId, "先申诉", 2, "req-p-old"));

        // v2 -> v3：配置涨到200元；旧申诉快照仍是100元。
        raceService.configureDeposit(raceId,
                new ConfigureDepositRequest(200 * YUAN, 3, "req-cfg-2"));
        raceService.createProtest(raceId, new CreateProtestRequest(
                "p-new", accountId, "后申诉", 4, "req-p-new"));

        assertThat(repository.findProtest("p-old").orElseThrow().depositFen())
                .isEqualTo(100 * YUAN);
        assertThat(repository.findProtest("p-new").orElseThrow().depositFen())
                .isEqualTo(200 * YUAN);
        // 200+500-100-200=200元可用
        assertThat(raceService.getAccount(accountId).balanceFen()).isEqualTo(200 * YUAN);

        // 退旧申诉只退100元，与当前200元配置无关。
        raceService.adjudicateProtest("p-old",
                new AdjudicateProtestRequest("ACCEPT", 5, "req-decide-old"));
        assertThat(raceService.getAccount(accountId).balanceFen()).isEqualTo(300 * YUAN);
        assertThat(repository.findLedgers(accountId))
                .filteredOn(l -> l.type() == LedgerType.REFUND)
                .singleElement()
                .satisfies(l -> assertThat(l.amountFen()).isEqualTo(100 * YUAN));
    }

    @Test
    void 创建与裁决同requestId重放首次结果_改申诉或改裁决返回409() {
        String raceId = "race-idem";
        String accountId = "acc-idem";
        seedRaceWithDeposit(raceId, 100 * YUAN);
        seedAccount(accountId, 200 * YUAN);

        CreateProtestRequest first = new CreateProtestRequest(
                "p-idem", accountId, "原申诉", 2, "req-idem-create");
        ServiceResult r1 = raceService.createProtest(raceId, first);
        // 同键同参重放：返回首次201（重放体为原样JSON），不产生第二条申诉/第二笔冻结。
        ServiceResult r2 = raceService.createProtest(raceId, first);
        assertThat(r1.status()).isEqualTo(201);
        assertThat(r2.status()).isEqualTo(201);
        assertThat(repository.findProtest("p-idem")).isPresent();
        assertThat(repository.findProtestsByRace(raceId)).hasSize(1);
        assertThat(repository.findLedgers(accountId))
                .filteredOn(l -> l.type() == LedgerType.FREEZE).hasSize(1);

        // 同键用于另一个申诉（异参）：409。
        assertThatThrownBy(() -> raceService.createProtest(raceId, new CreateProtestRequest(
                "p-other", accountId, "别的申诉", 2, "req-idem-create")))
                .isInstanceOf(ConflictException.class);

        ServiceResult a1 = raceService.adjudicateProtest("p-idem",
                new AdjudicateProtestRequest("ACCEPT", 3, "req-idem-decide"));
        // 同键同参重放裁决：返回首次200，余额与退款流水都只发生一次。
        ServiceResult a2 = raceService.adjudicateProtest("p-idem",
                new AdjudicateProtestRequest("ACCEPT", 3, "req-idem-decide"));
        assertThat(a1.status()).isEqualTo(200);
        assertThat(a2.status()).isEqualTo(200);
        assertThat(raceService.getAccount(accountId).balanceFen()).isEqualTo(200 * YUAN);
        assertThat(repository.findLedgers(accountId))
                .filteredOn(l -> l.type() == LedgerType.REFUND).hasSize(1);

        // 同键改成 REJECT：409。
        assertThatThrownBy(() -> raceService.adjudicateProtest("p-idem",
                new AdjudicateProtestRequest("REJECT", 4, "req-idem-decide")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 未裁决申诉暂缓封榜_终态后封榜并保留申诉裁决依据() {
        String raceId = "race-seal";
        String accountId = "acc-seal";
        seedRaceWithDeposit(raceId, 100 * YUAN);
        seedAccount(accountId, 200 * YUAN);
        raceService.registerRunner(raceId,
                new RegisterRunnerRequest("a", 1000L, 2, "req-runner-seal"));
        // 登记后 v=3；创建申诉 v3 -> v4
        raceService.createProtest(raceId, new CreateProtestRequest(
                "p-seal", accountId, "暂缓", 3, "req-protest-seal"));

        // 有 PENDING：封榜409，成绩不封。
        assertThatThrownBy(() -> raceService.sealRace(raceId,
                new SealRaceRequest(4, "req-seal-blocked")))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findRace(raceId).orElseThrow().status()).isEqualTo(RaceStatus.OPEN);
        assertThat(repository.findSnapshot(raceId)).isEmpty();

        // 裁决退回 v4 -> v5，随后封榜 v5 -> v6。
        raceService.adjudicateProtest("p-seal",
                new AdjudicateProtestRequest("ACCEPT", 4, "req-decide-seal"));
        ServiceResult sealed = raceService.sealRace(raceId,
                new SealRaceRequest(5, "req-seal-ok"));
        StandingResponse standing = (StandingResponse) sealed.body();
        assertThat(standing.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(standing.protests()).hasSize(1);
        assertThat(standing.protests().getFirst().protestId()).isEqualTo("p-seal");
        assertThat(standing.protests().getFirst().depositFen()).isEqualTo(100 * YUAN);
        assertThat(standing.protests().getFirst().decision()).isEqualTo(ProtestDecision.ACCEPT);
        assertThat(standing.protests().getFirst().reason()).isEqualTo("暂缓");

        // 历史封榜记录保留当时依据：直接读快照表。
        assertThat(repository.findSnapshot(raceId).orElseThrow().protests()).hasSize(1);
    }

    @Test
    void 未配置保证金时创建申诉422_封榜后配置保证金409() {
        String raceId = "race-nocfg";
        String accountId = "acc-nocfg";
        raceService.createRace(new CreateRaceRequest(raceId, "req-create-nocfg"));
        seedAccount(accountId, 200 * YUAN);

        assertThatThrownBy(() -> raceService.createProtest(raceId, new CreateProtestRequest(
                "p-nocfg", accountId, "没配置", 1, "req-p-nocfg")))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThat(repository.findProtest("p-nocfg")).isEmpty();

        raceService.sealRace(raceId, new SealRaceRequest(1, "req-seal-nocfg"));
        assertThatThrownBy(() -> raceService.configureDeposit(raceId,
                new ConfigureDepositRequest(100 * YUAN, 2, "req-cfg-sealed")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 查询展示流水与固定金额_账户404与申诉404() {
        String accountId = "acc-query";
        seedAccount(accountId, 200 * YUAN);
        List<LedgerResponse> ledgers = raceService.getLedgers(accountId);
        assertThat(ledgers).hasSize(1);
        assertThat(ledgers.getFirst().type()).isEqualTo(LedgerType.RECHARGE);
        assertThat(ledgers.getFirst().amountFen()).isEqualTo(200 * YUAN);
        AccountResponse account = raceService.getAccount(accountId);
        assertThat(account.balanceFen()).isEqualTo(200 * YUAN);
        assertThat(account.holderName()).isEqualTo("选手-acc-query");

        assertThatThrownBy(() -> raceService.getAccount("missing"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> raceService.getLedgers("missing"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> raceService.getProtest("missing"))
                .isInstanceOf(NotFoundException.class);
    }
}
