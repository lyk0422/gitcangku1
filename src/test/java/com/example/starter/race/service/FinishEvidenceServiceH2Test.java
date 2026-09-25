package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.AdjudicateFinishEvidenceRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.FinishAdjudicationResponse;
import com.example.starter.race.api.FinishEvidenceResponse;
import com.example.starter.race.api.RegisterFinishEvidenceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.WithdrawFinishEvidenceRequest;
import com.example.starter.race.domain.EvidenceStatus;
import com.example.starter.race.domain.RaceStatus;
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
 * 冲线证据登记、裁决、撤回与封榜冻结的 H2 数据库测试：
 * 主流程、失败分支（422/404/409）、事务回滚与 finishKey 幂等边界。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class FinishEvidenceServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-evidence";
    private static final long CAPTURED = 1_759_000_000_000L;

    @Autowired
    private RaceService raceService;

    /** 建赛并登记 a/b/c 同计时 1000ms、d 为 2000ms；返回当前版本5。 */
    private int createRaceWithTiedRunners() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 1000L, 1, "req-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", 1000L, 2, "req-b"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("c", 1000L, 3, "req-c"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("d", 2000L, 4, "req-d"));
        return 5;
    }

    private FinishEvidenceResponse registerEvidence(
            String evidenceId, List<String> order, int expectedVersion, String requestId) {
        ServiceResult result = raceService.registerFinishEvidence(RACE,
                new RegisterFinishEvidenceRequest(
                        evidenceId, 1000L, order, CAPTURED, "photo-judge",
                        expectedVersion, requestId));
        assertThat(result.status()).isEqualTo(201);
        return (FinishEvidenceResponse) result.body();
    }

    @Test
    void 登记证据主流程与查询() {
        createRaceWithTiedRunners();

        FinishEvidenceResponse body = registerEvidence("ev-1", List.of("b", "a", "c"), 5, "req-ev-1");
        assertThat(body.evidenceId()).isEqualTo("ev-1");
        assertThat(body.raceId()).isEqualTo(RACE);
        assertThat(body.finishTimeMs()).isEqualTo(1000L);
        assertThat(body.suggestedOrder()).containsExactly("b", "a", "c");
        assertThat(body.capturedAt()).isEqualTo(CAPTURED);
        assertThat(body.operator()).isEqualTo("photo-judge");
        assertThat(body.finishKey()).hasSize(64);
        assertThat(body.status()).isEqualTo(EvidenceStatus.PENDING);
        assertThat(body.adjudicatedAt()).isNull();
        assertThat(body.withdrawnAt()).isNull();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);

        List<FinishEvidenceResponse> listed = raceService.listFinishEvidence(RACE);
        assertThat(listed).hasSize(1);
        assertThat(listed.getFirst().evidenceId()).isEqualTo("ev-1");
        assertThat(raceService.listFinishAdjudications(RACE)).isEmpty();
    }

    @Test
    void 建议顺序校验失败且失败不占键() {
        createRaceWithTiedRunners();

        // 重复候选人
        assertThatThrownBy(() -> raceService.registerFinishEvidence(RACE,
                new RegisterFinishEvidenceRequest("ev-dup", 1000L, List.of("a", "a", "c"),
                        CAPTURED, "op", 5, "req-dup")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("重复");
        // 遗漏同计时候选人 c
        assertThatThrownBy(() -> raceService.registerFinishEvidence(RACE,
                new RegisterFinishEvidenceRequest("ev-miss", 1000L, List.of("a", "b"),
                        CAPTURED, "op", 5, "req-miss")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("遗漏");
        // 包含计时不一致的选手 d
        assertThatThrownBy(() -> raceService.registerFinishEvidence(RACE,
                new RegisterFinishEvidenceRequest("ev-mixed", 1000L, List.of("a", "b", "c", "d"),
                        CAPTURED, "op", 5, "req-mixed")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("不一致");
        // 候选人不存在
        assertThatThrownBy(() -> raceService.registerFinishEvidence(RACE,
                new RegisterFinishEvidenceRequest("ev-unknown", 1000L, List.of("a", "b", "zzz"),
                        CAPTURED, "op", 5, "req-unknown")))
                .isInstanceOf(NotFoundException.class);
        // 少于2名候选人
        assertThatThrownBy(() -> raceService.registerFinishEvidence(RACE,
                new RegisterFinishEvidenceRequest("ev-single", 1000L, List.of("a"),
                        CAPTURED, "op", 5, "req-single")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("至少");

        // 全部失败均不落库、不推进版本
        assertThat(raceService.listFinishEvidence(RACE)).isEmpty();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);

        // 失败不占键：同一 requestId 修正参数后可重新提交成功
        assertThatThrownBy(() -> raceService.registerFinishEvidence(RACE,
                new RegisterFinishEvidenceRequest("ev-reuse", 1000L, List.of("a", "b"),
                        CAPTURED, "op", 5, "req-reuse")))
                .isInstanceOf(UnprocessableEntityException.class);
        FinishEvidenceResponse fixed = registerEvidence(
                "ev-reuse", List.of("a", "b", "c"), 5, "req-reuse");
        assertThat(fixed.suggestedOrder()).containsExactly("a", "b", "c");
    }

    @Test
    void finishKey同键重放且证据ID冲突返回409() {
        createRaceWithTiedRunners();
        FinishEvidenceResponse first = registerEvidence("ev-1", List.of("b", "a", "c"), 5, "req-ev-1");

        // 同 requestId 重放：原样返回，不推进版本
        ServiceResult sameRequest = raceService.registerFinishEvidence(RACE,
                new RegisterFinishEvidenceRequest("ev-1", 1000L, List.of("b", "a", "c"),
                        CAPTURED, "photo-judge", 5, "req-ev-1"));
        assertThat(sameRequest.status()).isEqualTo(201);

        // 不同 requestId 但 finishKey 相同（同版本、同证据、同候选、同操作者）：重放原证据
        ServiceResult sameKey = raceService.registerFinishEvidence(RACE,
                new RegisterFinishEvidenceRequest("ev-1", 1000L, List.of("b", "a", "c"),
                        CAPTURED, "photo-judge", 5, "req-ev-1-again"));
        assertThat(sameKey.status()).isEqualTo(201);
        FinishEvidenceResponse replayed = (FinishEvidenceResponse) sameKey.body();
        assertThat(replayed.finishKey()).isEqualTo(first.finishKey());
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);
        assertThat(raceService.listFinishEvidence(RACE)).hasSize(1);

        // 同证据ID但捕获时刻不同（指纹不同）→ 409
        assertThatThrownBy(() -> raceService.registerFinishEvidence(RACE,
                new RegisterFinishEvidenceRequest("ev-1", 1000L, List.of("b", "a", "c"),
                        CAPTURED + 1, "photo-judge", 6, "req-ev-1-other")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("证据ID已存在");
    }

    @Test
    void 裁决主流程重排计时组并写入不可变快照() {
        createRaceWithTiedRunners();
        registerEvidence("ev-1", List.of("b", "a", "c"), 5, "req-ev-1");
        registerEvidence("ev-2", List.of("a", "c", "b"), 6, "req-ev-2");

        // 裁决前：三人并列第1
        StandingResponse before = raceService.getResults(RACE);
        assertThat(before.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 1, 1, 4);

        ServiceResult result = raceService.adjudicateFinishEvidence(RACE,
                new AdjudicateFinishEvidenceRequest("adj-1", List.of("ev-1", "ev-2"),
                        List.of("c", "a", "b"), "chief-judge", 7, "req-adj-1"));
        assertThat(result.status()).isEqualTo(201);
        FinishAdjudicationResponse snapshot = (FinishAdjudicationResponse) result.body();
        assertThat(snapshot.adjudicationId()).isEqualTo("adj-1");
        assertThat(snapshot.finishTimeMs()).isEqualTo(1000L);
        assertThat(snapshot.evidenceIds()).containsExactly("ev-1", "ev-2");
        assertThat(snapshot.finalOrder()).containsExactly("c", "a", "b");
        assertThat(snapshot.operator()).isEqualTo("chief-judge");
        assertThat(snapshot.raceVersion()).isEqualTo(8);

        // 证据置为已裁决
        List<FinishEvidenceResponse> evidence = raceService.listFinishEvidence(RACE);
        assertThat(evidence).extracting(FinishEvidenceResponse::status)
                .containsExactly(EvidenceStatus.ADJUDICATED, EvidenceStatus.ADJUDICATED);
        assertThat(evidence.getFirst().adjudicatedAt()).isNotNull();

        // 实时排名按裁决顺序重排，名次互不重复
        StandingResponse after = raceService.getResults(RACE);
        assertThat(after.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("c", "a", "b", "d");
        assertThat(after.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2, 3, 4);

        // 裁决快照可查询且只有一份
        List<FinishAdjudicationResponse> snapshots = raceService.listFinishAdjudications(RACE);
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.getFirst().finalOrder()).containsExactly("c", "a", "b");
    }

    @Test
    void 裁决校验失败全部回滚不留半成品() {
        createRaceWithTiedRunners();
        registerEvidence("ev-1", List.of("b", "a", "c"), 5, "req-ev-1");
        // v7: c 计时修订为 1500ms，候选人不再同计时
        raceService.reviseTime(RACE, new ReviseTimeRequest("c", 1500L, 6, "req-revise-c"));

        assertThatThrownBy(() -> raceService.adjudicateFinishEvidence(RACE,
                new AdjudicateFinishEvidenceRequest("adj-x", List.of("ev-1"),
                        List.of("c", "a", "b"), "judge", 7, "req-adj-x")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("计时已变更");

        // 无任何部分变更：版本、证据状态、裁决快照、排名均保持
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(7);
        assertThat(raceService.listFinishEvidence(RACE).getFirst().status())
                .isEqualTo(EvidenceStatus.PENDING);
        assertThat(raceService.listFinishAdjudications(RACE)).isEmpty();
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b", "c", "d");
        assertThat(standing.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 1, 3, 4);
    }

    @Test
    void 候选人被取消资格或名次顺序非法时裁决失败() {
        createRaceWithTiedRunners();
        registerEvidence("ev-1", List.of("b", "a", "c"), 5, "req-ev-1");

        // 名次顺序重复
        assertThatThrownBy(() -> raceService.adjudicateFinishEvidence(RACE,
                new AdjudicateFinishEvidenceRequest("adj-dup", List.of("ev-1"),
                        List.of("c", "a", "a"), "judge", 6, "req-adj-dup")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("重复");
        // 名次顺序遗漏
        assertThatThrownBy(() -> raceService.adjudicateFinishEvidence(RACE,
                new AdjudicateFinishEvidenceRequest("adj-miss", List.of("ev-1"),
                        List.of("c", "a"), "judge", 6, "req-adj-miss")))
                .isInstanceOf(UnprocessableEntityException.class);
        // 名次顺序含非候选人
        assertThatThrownBy(() -> raceService.adjudicateFinishEvidence(RACE,
                new AdjudicateFinishEvidenceRequest("adj-extra", List.of("ev-1"),
                        List.of("c", "a", "d"), "judge", 6, "req-adj-extra")))
                .isInstanceOf(UnprocessableEntityException.class);
        // 证据不存在
        assertThatThrownBy(() -> raceService.adjudicateFinishEvidence(RACE,
                new AdjudicateFinishEvidenceRequest("adj-none", List.of("ev-x"),
                        List.of("c", "a", "b"), "judge", 6, "req-adj-none")))
                .isInstanceOf(NotFoundException.class);

        // v7: b 被取消资格 → 候选人失效
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "p-dq", "b", "DISQUALIFY", null, 6, "req-p-dq"));
        assertThatThrownBy(() -> raceService.adjudicateFinishEvidence(RACE,
                new AdjudicateFinishEvidenceRequest("adj-dq", List.of("ev-1"),
                        List.of("c", "a", "b"), "judge", 7, "req-adj-dq")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("取消资格");

        assertThat(raceService.listFinishAdjudications(RACE)).isEmpty();
        assertThat(raceService.listFinishEvidence(RACE).getFirst().status())
                .isEqualTo(EvidenceStatus.PENDING);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(7);
    }

    @Test
    void 跨计时组证据不可合并裁决() {
        createRaceWithTiedRunners();
        // v6: 再登记 e 与 d 同计时 2000ms
        raceService.registerRunner(RACE, new RegisterRunnerRequest("e", 2000L, 5, "req-e"));
        registerEvidence("ev-1", List.of("b", "a", "c"), 6, "req-ev-1");
        ServiceResult second = raceService.registerFinishEvidence(RACE,
                new RegisterFinishEvidenceRequest("ev-2", 2000L, List.of("d", "e"),
                        CAPTURED, "photo-judge", 7, "req-ev-2"));
        assertThat(second.status()).isEqualTo(201);

        assertThatThrownBy(() -> raceService.adjudicateFinishEvidence(RACE,
                new AdjudicateFinishEvidenceRequest("adj-mix", List.of("ev-1", "ev-2"),
                        List.of("c", "a", "b"), "judge", 8, "req-adj-mix")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("同一计时组");
        assertThat(raceService.listFinishAdjudications(RACE)).isEmpty();
    }

    @Test
    void 撤回未裁决证据保留记录且已裁决证据不可撤回() {
        createRaceWithTiedRunners();
        registerEvidence("ev-1", List.of("b", "a", "c"), 5, "req-ev-1");
        registerEvidence("ev-2", List.of("a", "c", "b"), 6, "req-ev-2");

        // 撤回 ev-1：保留撤回记录
        ServiceResult withdrawn = raceService.withdrawFinishEvidence(RACE, "ev-1",
                new WithdrawFinishEvidenceRequest(7, "req-wd-1"));
        assertThat(withdrawn.status()).isEqualTo(200);
        FinishEvidenceResponse withdrawnBody = (FinishEvidenceResponse) withdrawn.body();
        assertThat(withdrawnBody.status()).isEqualTo(EvidenceStatus.WITHDRAWN);
        assertThat(withdrawnBody.withdrawnAt()).isNotNull();
        assertThat(raceService.listFinishEvidence(RACE)).hasSize(2);

        // 重复撤回 → 409
        assertThatThrownBy(() -> raceService.withdrawFinishEvidence(RACE, "ev-1",
                new WithdrawFinishEvidenceRequest(8, "req-wd-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已撤回");
        // 已撤回证据不可裁决 → 422
        assertThatThrownBy(() -> raceService.adjudicateFinishEvidence(RACE,
                new AdjudicateFinishEvidenceRequest("adj-wd", List.of("ev-1"),
                        List.of("c", "a", "b"), "judge", 8, "req-adj-wd")))
                .isInstanceOf(UnprocessableEntityException.class);

        // 裁决 ev-2 后不可撤回 → 409
        raceService.adjudicateFinishEvidence(RACE,
                new AdjudicateFinishEvidenceRequest("adj-1", List.of("ev-2"),
                        List.of("c", "a", "b"), "judge", 8, "req-adj-1"));
        assertThatThrownBy(() -> raceService.withdrawFinishEvidence(RACE, "ev-2",
                new WithdrawFinishEvidenceRequest(9, "req-wd-3")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已裁决");
    }

    @Test
    void 裁决后新增处罚按既有规则重排但不改写证据快照() {
        createRaceWithTiedRunners();
        registerEvidence("ev-1", List.of("b", "a", "c"), 5, "req-ev-1");
        raceService.adjudicateFinishEvidence(RACE,
                new AdjudicateFinishEvidenceRequest("adj-1", List.of("ev-1"),
                        List.of("c", "a", "b"), "judge", 6, "req-adj-1"));

        // v8: c 加时 500ms → 总耗时 1500ms 落到组外，a、b 仍按裁决相对顺序
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "p-1", "c", "ADD_TIME", 500L, 7, "req-p-1"));
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b", "c", "d");
        assertThat(standing.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2, 3, 4);
        assertThat(standing.entries().get(2).totalTimeMs()).isEqualTo(1500L);

        // 证据快照不被改写
        List<FinishAdjudicationResponse> snapshots = raceService.listFinishAdjudications(RACE);
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.getFirst().finalOrder()).containsExactly("c", "a", "b");
        assertThat(snapshots.getFirst().raceVersion()).isEqualTo(7);
    }

    @Test
    void 封榜后禁止登记裁决与撤回证据且快照保留裁决名次() {
        createRaceWithTiedRunners();
        registerEvidence("ev-1", List.of("b", "a", "c"), 5, "req-ev-1");
        registerEvidence("ev-2", List.of("a", "c", "b"), 6, "req-ev-2");
        raceService.adjudicateFinishEvidence(RACE,
                new AdjudicateFinishEvidenceRequest("adj-1", List.of("ev-1"),
                        List.of("c", "a", "b"), "judge", 7, "req-adj-1"));

        // v9: 封榜
        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(8, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
        StandingResponse sealedStanding = (StandingResponse) sealed.body();
        assertThat(sealedStanding.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(sealedStanding.version()).isEqualTo(9);
        // 封榜快照固化裁决名次
        assertThat(sealedStanding.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("c", "a", "b", "d");
        assertThat(sealedStanding.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2, 3, 4);

        // 封榜后登记、裁决、撤回证据均 409
        assertThatThrownBy(() -> raceService.registerFinishEvidence(RACE,
                new RegisterFinishEvidenceRequest("ev-3", 1000L, List.of("a", "b", "c"),
                        CAPTURED, "op", 9, "req-ev-3")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
        assertThatThrownBy(() -> raceService.adjudicateFinishEvidence(RACE,
                new AdjudicateFinishEvidenceRequest("adj-2", List.of("ev-2"),
                        List.of("a", "b", "c"), "judge", 9, "req-adj-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
        assertThatThrownBy(() -> raceService.withdrawFinishEvidence(RACE, "ev-2",
                new WithdrawFinishEvidenceRequest(9, "req-wd-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");

        // 封榜后证据与裁决快照仍可查询，实时排名返回封榜版本
        assertThat(raceService.listFinishEvidence(RACE)).hasSize(2);
        assertThat(raceService.listFinishAdjudications(RACE)).hasSize(1);
        StandingResponse live = raceService.getResults(RACE);
        assertThat(live.version()).isEqualTo(9);
        assertThat(live.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2, 3, 4);
    }

    @Test
    void 查询不存在赛事的证据与裁决返回404() {
        assertThatThrownBy(() -> raceService.listFinishEvidence("no-such-race"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> raceService.listFinishAdjudications("no-such-race"))
                .isInstanceOf(NotFoundException.class);
    }
}
