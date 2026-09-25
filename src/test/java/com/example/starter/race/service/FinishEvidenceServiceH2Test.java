package com.example.starter.race.service;

import com.example.starter.race.api.AdjudicateEvidenceRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.EvidenceRulingResponse;
import com.example.starter.race.api.FinishEvidenceResponse;
import com.example.starter.race.api.RegisterEvidenceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.RevokeEvidenceRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.WithdrawRunnerRequest;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.EvidenceStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.EvidenceWithdrawalRow;
import com.example.starter.race.persistence.FinishEvidenceRow;
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
 * 冲线证据裁决、名次封榜回滚保护的 H2 数据库测试：
 * 覆盖证据版本、排名重算、封榜冻结、事务回滚与幂等边界。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class FinishEvidenceServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-evidence";
    private static final long T = 1000L;
    private static final long CAPTURED = 1_700_000_000_000L;

    @Autowired
    private RaceService raceService;

    /** 建赛并登记 a/b/c 三名同计时（1000ms）选手；完成后赛事版本为4。 */
    private void seedThreeTiedRunners(String raceId) {
        raceService.createRace(new CreateRaceRequest(raceId, "req-create"));
        raceService.registerRunner(raceId,
                new RegisterRunnerRequest("a", T, 1, "req-a"));
        raceService.registerRunner(raceId,
                new RegisterRunnerRequest("b", T, 2, "req-b"));
        raceService.registerRunner(raceId,
                new RegisterRunnerRequest("c", T, 3, "req-c"));
    }

    private RegisterEvidenceRequest evidenceRequest(
            String evidenceId, int expectedVersion, List<String> order, String requestId) {
        return new RegisterEvidenceRequest(
                evidenceId, T, order, "judge-1", CAPTURED, expectedVersion, requestId);
    }

    @Test
    void 裁决主流程_重排计时组并固化不可变快照() {
        seedThreeTiedRunners(RACE);
        // v5/v6：登记两条建议顺序不同的 PENDING 证据
        raceService.registerEvidence(RACE,
                evidenceRequest("ev-1", 4, List.of("a", "b", "c"), "req-ev1"));
        raceService.registerEvidence(RACE,
                evidenceRequest("ev-2", 5, List.of("c", "b", "a"), "req-ev2"));

        List<FinishEvidenceResponse> evidences = raceService.getEvidences(RACE);
        assertThat(evidences).hasSize(2);
        assertThat(evidences).extracting(FinishEvidenceResponse::status)
                .containsExactly(EvidenceStatus.PENDING, EvidenceStatus.PENDING);
        assertThat(evidences.getFirst().capturedAt()).isEqualTo(CAPTURED);

        // v7：裁判批量裁决，最终顺序 c-a-b
        ServiceResult result = raceService.adjudicateEvidence(RACE,
                new AdjudicateEvidenceRequest("ruling-1", T,
                        List.of("ev-1", "ev-2"), List.of("c", "a", "b"),
                        "judge-1", 6, "req-ruling"));
        assertThat(result.status()).isEqualTo(201);
        EvidenceRulingResponse ruling = (EvidenceRulingResponse) result.body();
        assertThat(ruling.version()).isEqualTo(7);
        assertThat(ruling.orderedBibs()).containsExactly("c", "a", "b");
        assertThat(ruling.evidenceIds()).containsExactly("ev-1", "ev-2");

        // 实时排名：同总耗时组按证据顺序赋 1/2/3 名
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.version()).isEqualTo(7);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("c", "a", "b");
        assertThat(standing.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2, 3);

        // 证据状态已置为 ADJUDICATED 并关联裁决批次
        for (FinishEvidenceResponse evidence : raceService.getEvidences(RACE)) {
            assertThat(evidence.status()).isEqualTo(EvidenceStatus.ADJUDICATED);
            assertThat(evidence.rulingId()).isEqualTo("ruling-1");
        }
        // 裁决快照可按批次查询且内容不可变
        assertThat(raceService.getRuling(RACE, "ruling-1").orderedBibs())
                .containsExactly("c", "a", "b");
    }

    @Test
    void 裁决后加时处罚可重排榜单但不改变证据快照() {
        seedThreeTiedRunners(RACE);
        raceService.registerEvidence(RACE,
                evidenceRequest("ev-1", 4, List.of("a", "b", "c"), "req-ev1"));
        raceService.adjudicateEvidence(RACE, new AdjudicateEvidenceRequest(
                "ruling-1", T, List.of("ev-1"), List.of("c", "a", "b"),
                "judge-1", 5, "req-ruling"));

        // v7：给 c 加时 500ms -> c 总耗时 1500，a/b 仍 1000 并列
        raceService.addPenalty(RACE, new com.example.starter.race.api.AddPenaltyRequest(
                "pen-1", "c", "ADD_TIME", 500L, 6, "req-pen"));

        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.version()).isEqualTo(7);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b", "c");
        // a/b 同总耗时且同属已裁决组，保持证据相对顺序（a 在 b 前），名次不再并列
        assertThat(standing.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2, 3);
        // 裁决快照内容不变
        assertThat(raceService.getRuling(RACE, "ruling-1").orderedBibs())
                .containsExactly("c", "a", "b");
        assertThat(raceService.getRuling(RACE, "ruling-1").version()).isEqualTo(6);
    }

    @Test
    void 登记证据失败_遗漏重复夹带候选人均为422() {
        seedThreeTiedRunners(RACE);

        assertThatThrownBy(() -> raceService.registerEvidence(RACE,
                evidenceRequest("ev-miss", 4, List.of("a", "b"), "req-miss")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("遗漏候选人");

        assertThatThrownBy(() -> raceService.registerEvidence(RACE,
                evidenceRequest("ev-dup", 4, List.of("a", "b", "b"), "req-dup")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("重复候选人");

        assertThatThrownBy(() -> raceService.registerEvidence(RACE,
                evidenceRequest("ev-extra", 4, List.of("a", "b", "c", "a"), "req-extra")))
                .isInstanceOf(UnprocessableEntityException.class);

        // 失败不占版本：当前仍为 v4，成功登记可在 v4 上进行
        ServiceResult ok = raceService.registerEvidence(RACE,
                evidenceRequest("ev-ok", 4, List.of("a", "b", "c"), "req-ok"));
        assertThat(ok.status()).isEqualTo(201);
    }

    @Test
    void 登记证据_不存在或不同计时的候选人为422() {
        seedThreeTiedRunners(RACE);

        assertThatThrownBy(() -> raceService.registerEvidence(RACE,
                evidenceRequest("ev-ghost", 4, List.of("a", "b", "ghost"), "req-ghost")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("不是赛事选手");

        // d 完赛耗时 2000，不属于 1000ms 计时组
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("d", 2000L, 4, "req-d"));
        // 1000ms 计时组仍是 a/b/c，证据登记成功并推进版本
        raceService.registerEvidence(RACE,
                new RegisterEvidenceRequest("ev-time", T, List.of("a", "b", "c"),
                        "judge-1", CAPTURED, 5, "req-time"));
        assertThatThrownBy(() -> raceService.registerEvidence(RACE,
                new RegisterEvidenceRequest("ev-d", T, List.of("a", "b", "d"),
                        "judge-1", CAPTURED, 6, "req-evd")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("不属于该计时组");
    }

    @Test
    void 退赛使候选人失效_裁决422且无部分变更() {
        seedThreeTiedRunners(RACE);
        raceService.registerEvidence(RACE,
                evidenceRequest("ev-1", 4, List.of("a", "b", "c"), "req-ev1"));
        // v6：b 退赛
        raceService.withdrawRunner(RACE, "b",
                new WithdrawRunnerRequest("marshal-1", 5, "req-withdraw"));

        // b 已失效：裁决 422
        assertThatThrownBy(() -> raceService.adjudicateEvidence(RACE,
                new AdjudicateEvidenceRequest("ruling-x", T, List.of("ev-1"),
                        List.of("c", "a", "b"), "judge-1", 6, "req-ruling-x")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("已退赛");

        // 无部分变更：证据仍 PENDING、裁决快照不存在、版本仍为6
        assertThat(raceService.getEvidence(RACE, "ev-1").status())
                .isEqualTo(EvidenceStatus.PENDING);
        assertThat(raceService.getRulings(RACE)).isEmpty();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);

        // 榜单中 b 为 WITHDRAWN 且不排名
        StandingResponse standing = raceService.getResults(RACE);
        ResultEntryResponse bEntry = standing.entries().stream()
                .filter(e -> e.bib().equals("b")).findFirst().orElseThrow();
        assertThat(bEntry.status()).isEqualTo(EntryStatus.WITHDRAWN);
        assertThat(bEntry.rank()).isNull();
    }

    @Test
    void 裁决校验失败_撤回重复裁决顺序错误均422且回滚() {
        seedThreeTiedRunners(RACE);
        raceService.registerEvidence(RACE,
                evidenceRequest("ev-1", 4, List.of("a", "b", "c"), "req-ev1"));
        raceService.registerEvidence(RACE,
                evidenceRequest("ev-2", 5, List.of("a", "b", "c"), "req-ev2"));

        // 证据不存在
        assertThatThrownBy(() -> raceService.adjudicateEvidence(RACE,
                new AdjudicateEvidenceRequest("r1", T, List.of("ev-404"),
                        List.of("a", "b", "c"), "j", 6, "req-r1")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("证据不存在");

        // orderedBibs 遗漏候选人
        assertThatThrownBy(() -> raceService.adjudicateEvidence(RACE,
                new AdjudicateEvidenceRequest("r2", T, List.of("ev-1"),
                        List.of("a", "b"), "j", 6, "req-r2")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("遗漏候选人");

        // orderedBibs 重复
        assertThatThrownBy(() -> raceService.adjudicateEvidence(RACE,
                new AdjudicateEvidenceRequest("r3", T, List.of("ev-1"),
                        List.of("a", "b", "b"), "j", 6, "req-r3")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("重复候选人");

        // 撤回 ev-2 后再裁决包含它的批次 -> 422
        raceService.revokeEvidence(RACE, "ev-2",
                new RevokeEvidenceRequest("judge-1", 6, "req-revoke-ev2"));
        assertThatThrownBy(() -> raceService.adjudicateEvidence(RACE,
                new AdjudicateEvidenceRequest("r4", T, List.of("ev-1", "ev-2"),
                        List.of("a", "b", "c"), "j", 7, "req-r4")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("不是待裁决状态");

        // 全部失败均未产生裁决；ev-1 仍可单独成功裁决
        ServiceResult ok = raceService.adjudicateEvidence(RACE,
                new AdjudicateEvidenceRequest("r5", T, List.of("ev-1"),
                        List.of("a", "b", "c"), "j", 7, "req-r5"));
        assertThat(ok.status()).isEqualTo(201);
        // 重复裁决已裁决证据 -> 422
        assertThatThrownBy(() -> raceService.adjudicateEvidence(RACE,
                new AdjudicateEvidenceRequest("r6", T, List.of("ev-1"),
                        List.of("a", "b", "c"), "j", 8, "req-r6")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("不是待裁决状态");
    }

    @Test
    void 裁决时计时组新增同计时选手_名次重复422() {
        seedThreeTiedRunners(RACE);
        raceService.registerEvidence(RACE,
                evidenceRequest("ev-1", 4, List.of("a", "b", "c"), "req-ev1"));
        // v6：新增同计时选手 d（证据登记后入组）
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("d", T, 5, "req-d"));

        assertThatThrownBy(() -> raceService.adjudicateEvidence(RACE,
                new AdjudicateEvidenceRequest("r1", T, List.of("ev-1"),
                        List.of("a", "b", "c"), "j", 6, "req-r1")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("名次存在重复");
        assertThat(raceService.getRulings(RACE)).isEmpty();
    }

    @Test
    void 撤回证据_未裁决留痕已裁决409() {
        seedThreeTiedRunners(RACE);
        raceService.registerEvidence(RACE,
                evidenceRequest("ev-1", 4, List.of("a", "b", "c"), "req-ev1"));

        ServiceResult revoked = raceService.revokeEvidence(RACE, "ev-1",
                new RevokeEvidenceRequest("judge-2", 5, "req-revoke"));
        assertThat(revoked.status()).isEqualTo(200);
        FinishEvidenceResponse evidence = raceService.getEvidence(RACE, "ev-1");
        assertThat(evidence.status()).isEqualTo(EvidenceStatus.REVOKED);
        assertThat(evidence.revokedAt()).isNotNull();

        // 撤回记录保留
        EvidenceWithdrawalRow withdrawal =
                repository.findWithdrawal("ev-1").orElseThrow();
        assertThat(withdrawal.operator()).isEqualTo("judge-2");
        assertThat(withdrawal.raceId()).isEqualTo(RACE);

        // 重复撤回 409
        assertThatThrownBy(() -> raceService.revokeEvidence(RACE, "ev-1",
                new RevokeEvidenceRequest("judge-2", 6, "req-revoke-again")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已撤回");

        // 已撤回证据不可裁决
        assertThatThrownBy(() -> raceService.adjudicateEvidence(RACE,
                new AdjudicateEvidenceRequest("r1", T, List.of("ev-1"),
                        List.of("a", "b", "c"), "j", 6, "req-r1")))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void 已裁决证据不可撤回() {
        seedThreeTiedRunners(RACE);
        raceService.registerEvidence(RACE,
                evidenceRequest("ev-1", 4, List.of("a", "b", "c"), "req-ev1"));
        raceService.adjudicateEvidence(RACE, new AdjudicateEvidenceRequest(
                "ruling-1", T, List.of("ev-1"), List.of("c", "a", "b"),
                "j", 5, "req-ruling"));

        assertThatThrownBy(() -> raceService.revokeEvidence(RACE, "ev-1",
                new RevokeEvidenceRequest("j", 6, "req-revoke")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已裁决证据不可撤回");
        assertThat(repository.findWithdrawal("ev-1")).isEmpty();
    }

    @Test
    void 封榜后证据登记裁决撤回均409() {
        seedThreeTiedRunners(RACE);
        raceService.registerEvidence(RACE,
                evidenceRequest("ev-1", 4, List.of("a", "b", "c"), "req-ev1"));
        // v6：封榜
        raceService.sealRace(RACE, new SealRaceRequest(5, "req-seal"));
        assertThat(repository.findRace(RACE).orElseThrow().status())
                .isEqualTo(RaceStatus.SEALED);

        assertThatThrownBy(() -> raceService.registerEvidence(RACE,
                evidenceRequest("ev-2", 6, List.of("a", "b", "c"), "req-ev2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已封榜");
        assertThatThrownBy(() -> raceService.adjudicateEvidence(RACE,
                new AdjudicateEvidenceRequest("r1", T, List.of("ev-1"),
                        List.of("a", "b", "c"), "j", 6, "req-r1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已封榜");
        assertThatThrownBy(() -> raceService.revokeEvidence(RACE, "ev-1",
                new RevokeEvidenceRequest("j", 6, "req-revoke")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已封榜");
        // PENDING 证据原样保留
        assertThat(raceService.getEvidence(RACE, "ev-1").status())
                .isEqualTo(EvidenceStatus.PENDING);
    }

    @Test
    void 封榜快照固化证据裁决名次() {
        seedThreeTiedRunners(RACE);
        raceService.registerEvidence(RACE,
                evidenceRequest("ev-1", 4, List.of("a", "b", "c"), "req-ev1"));
        raceService.adjudicateEvidence(RACE, new AdjudicateEvidenceRequest(
                "ruling-1", T, List.of("ev-1"), List.of("c", "a", "b"),
                "j", 5, "req-ruling"));
        // v7 封榜
        raceService.sealRace(RACE, new SealRaceRequest(6, "req-seal"));

        StandingResponse snapshot = raceService.getResults(RACE);
        assertThat(snapshot.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(snapshot.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("c", "a", "b");
        assertThat(snapshot.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2, 3);
    }

    @Test
    void 登记裁决幂等_同键重放异参冲突失败不占键() {
        seedThreeTiedRunners(RACE);
        RegisterEvidenceRequest first =
                evidenceRequest("ev-1", 4, List.of("a", "b", "c"), "req-ev1");
        ServiceResult r1 = raceService.registerEvidence(RACE, first);
        ServiceResult r2 = raceService.registerEvidence(RACE, first);
        assertThat(r1.status()).isEqualTo(r2.status());
        assertThat(repository.findEvidences(RACE)).hasSize(1);

        // 同键异参 409
        assertThatThrownBy(() -> raceService.registerEvidence(RACE,
                evidenceRequest("ev-1", 4, List.of("c", "b", "a"), "req-ev1")))
                .isInstanceOf(ConflictException.class);

        // 失败不占键：先用 req-will-fail 触发一次 422，再用同一 requestId 成功
        assertThatThrownBy(() -> raceService.registerEvidence(RACE,
                evidenceRequest("ev-x", 5, List.of("a", "b"), "req-reuse")))
                .isInstanceOf(UnprocessableEntityException.class);
        ServiceResult reused = raceService.registerEvidence(RACE,
                evidenceRequest("ev-2", 5, List.of("a", "b", "c"), "req-reuse"));
        assertThat(reused.status()).isEqualTo(201);

        // 裁决同键重放
        AdjudicateEvidenceRequest ruling = new AdjudicateEvidenceRequest(
                "ruling-1", T, List.of("ev-1", "ev-2"), List.of("b", "c", "a"),
                "j", 6, "req-ruling");
        ServiceResult a1 = raceService.adjudicateEvidence(RACE, ruling);
        ServiceResult a2 = raceService.adjudicateEvidence(RACE, ruling);
        assertThat(a1.status()).isEqualTo(a2.status()).isEqualTo(201);
        assertThat(raceService.getRulings(RACE)).hasSize(1);
        assertThat(repository.findEvidences(RACE))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.status())
                        .isEqualTo(com.example.starter.race.domain.EvidenceStatus.ADJUDICATED));

        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("b", "c", "a");
        assertThat(standing.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2, 3);
    }

    @Test
    void 证据ID重复登记为409() {
        seedThreeTiedRunners(RACE);
        raceService.registerEvidence(RACE,
                evidenceRequest("ev-1", 4, List.of("a", "b", "c"), "req-ev1"));
        assertThatThrownBy(() -> raceService.registerEvidence(RACE,
                evidenceRequest("ev-1", 5, List.of("a", "b", "c"), "req-ev1-other")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("证据ID已存在");
    }

    @Test
    void 查询不存在的证据与裁决为404() {
        seedThreeTiedRunners(RACE);
        assertThatThrownBy(() -> raceService.getEvidence(RACE, "nope"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> raceService.getRuling(RACE, "nope"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> raceService.getEvidence("other-race", "nope"))
                .isInstanceOf(NotFoundException.class);
    }
}
