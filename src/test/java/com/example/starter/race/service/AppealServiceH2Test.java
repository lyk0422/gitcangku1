package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.AppealListResponse;
import com.example.starter.race.api.AppealResponse;
import com.example.starter.race.api.ConfirmAppealRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RecommendAppealRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitAppealRequest;
import com.example.starter.race.domain.AppealStatus;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.PenaltyType;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.AppealRow;
import com.example.starter.race.persistence.PenaltyRevisionRow;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.MutableClock;
import com.example.starter.race.support.MutableClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 处罚申诉的 H2 数据库测试：受理冻结、三类裁决、双人约束、封榜门禁、
 * 版本变化整体回滚、申诉窗口与幂等边界。
 */
@SpringBootTest
@Import(MutableClockTestConfig.class)
class AppealServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-appeal";

    @Autowired
    private RaceService raceService;

    @Autowired
    private MutableClock clock;

    /** 建赛并登记 a/b（均 1000ms），对 a 加时 500ms（p-1）；返回当前赛事版本 4。 */
    private int setupRaceWithPenalty() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 1000L, 1, "req-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", 1000L, 2, "req-b"));
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-1", "a", "ADD_TIME", 500L, 3, "req-p1"));
        return 4;
    }

    private AppealResponse submitAppeal(String appealKey, String bib, String penaltyId,
                                        int penaltyVersion, int expectedVersion, String requestId) {
        ServiceResult result = raceService.submitAppeal(RACE, new SubmitAppealRequest(
                appealKey, bib, penaltyId, penaltyVersion, "判罚有误", expectedVersion, requestId));
        assertThat(result.status()).isEqualTo(201);
        return (AppealResponse) result.body();
    }

    private AppealResponse recommend(String appealKey, String official, String decision,
                                     Long replacementMs, String requestId) {
        ServiceResult result = raceService.recommendAppeal(RACE, appealKey,
                new RecommendAppealRequest(official, decision, replacementMs, requestId));
        assertThat(result.status()).isEqualTo(200);
        return (AppealResponse) result.body();
    }

    private AppealResponse confirm(String appealKey, String official, String decision,
                                   Long replacementMs, String requestId) {
        ServiceResult result = raceService.confirmAppeal(RACE, appealKey,
                new ConfirmAppealRequest(official, "CONFIRM", decision, replacementMs, requestId));
        assertThat(result.status()).isEqualTo(200);
        return (AppealResponse) result.body();
    }

    @Test
    void 受理申诉冻结现场且榜单继续按原处罚计算并标记申诉中() {
        setupRaceWithPenalty();

        AppealResponse appeal = submitAppeal("ak-1", "a", "p-1", 1, 4, "req-ak1");

        assertThat(appeal.status()).isEqualTo(AppealStatus.PENDING);
        assertThat(appeal.penaltyVersion()).isEqualTo(1);
        assertThat(appeal.reason()).isEqualTo("判罚有误");
        // 冻结：处罚、原始/净成绩、分段判定与榜单版本
        assertThat(appeal.freeze().penaltyType()).isEqualTo("ADD_TIME");
        assertThat(appeal.freeze().penaltyAmountMs()).isEqualTo(500L);
        assertThat(appeal.freeze().finishTimeMs()).isEqualTo(1000L);
        assertThat(appeal.freeze().penaltyMs()).isEqualTo(500L);
        assertThat(appeal.freeze().totalTimeMs()).isEqualTo(1500L);
        assertThat(appeal.freeze().rank()).isEqualTo(2);
        assertThat(appeal.freeze().status()).isEqualTo("RANKED");
        assertThat(appeal.freeze().segments()).isEmpty();
        assertThat(appeal.freeze().leaderboardVersion()).isEqualTo(4);
        assertThat(appeal.firstOpinion()).isNull();
        assertThat(appeal.secondOpinion()).isNull();

        // 公开榜单仍按原处罚计算，a 标记“申诉中”，受理不推进榜单版本
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.version()).isEqualTo(4);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("b", "a");
        assertThat(standing.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2);
        assertThat(standing.entries().get(1).penaltyMs()).isEqualTo(500L);
        assertThat(standing.entries().get(1).appealPending()).isTrue();
        assertThat(standing.entries().get(0).appealPending()).isFalse();
        // 处罚本身未冻结删除
        PenaltyRow penalty = repository.findPenalty("p-1").orElseThrow();
        assertThat(penalty.revoked()).isFalse();
        assertThat(penalty.version()).isEqualTo(1);
    }

    @Test
    void 提交申诉失败分支覆盖() {
        setupRaceWithPenalty();

        // 处罚不存在 -> 404
        assertThatThrownBy(() -> raceService.submitAppeal(RACE, new SubmitAppealRequest(
                "ak-x", "a", "p-x", 1, "r", 4, "req-x1")))
                .isInstanceOf(NotFoundException.class);
        // 他人处罚 -> 422
        assertThatThrownBy(() -> raceService.submitAppeal(RACE, new SubmitAppealRequest(
                "ak-x", "b", "p-1", 1, "r", 4, "req-x2")))
                .isInstanceOf(UnprocessableEntityException.class);
        // 处罚版本不一致 -> 409
        assertThatThrownBy(() -> raceService.submitAppeal(RACE, new SubmitAppealRequest(
                "ak-x", "a", "p-1", 2, "r", 4, "req-x3")))
                .isInstanceOf(ConflictException.class);
        // 赛事版本不一致 -> 409
        assertThatThrownBy(() -> raceService.submitAppeal(RACE, new SubmitAppealRequest(
                "ak-x", "a", "p-1", 1, "r", 3, "req-x4")))
                .isInstanceOf(ConflictException.class);
        // 失败不占键：同一 requestId 修正参数后可成功
        AppealResponse ok = submitAppeal("ak-1", "a", "p-1", 1, 4, "req-x3");
        assertThat(ok.appealKey()).isEqualTo("ak-1");

        // 同一处罚重复申诉 -> 409
        assertThatThrownBy(() -> raceService.submitAppeal(RACE, new SubmitAppealRequest(
                "ak-2", "a", "p-1", 1, "r", 4, "req-x5")))
                .isInstanceOf(ConflictException.class);
        // appealKey 重复 -> 409
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-2", "b", "ADD_TIME", 100L, 4, "req-p2"));
        assertThatThrownBy(() -> raceService.submitAppeal(RACE, new SubmitAppealRequest(
                "ak-1", "b", "p-2", 1, "r", 5, "req-x6")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 已撤销处罚与无完赛耗时不可申诉() {
        setupRaceWithPenalty();
        // 无完赛耗时的选手 c
        raceService.registerRunner(RACE, new RegisterRunnerRequest("c", null, 4, "req-c"));
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-2", "c", "ADD_TIME", 100L, 5, "req-p2"));
        assertThatThrownBy(() -> raceService.submitAppeal(RACE, new SubmitAppealRequest(
                "ak-c", "c", "p-2", 1, "r", 6, "req-akc")))
                .isInstanceOf(UnprocessableEntityException.class);

        // 撤销后的处罚不可申诉
        raceService.revokePenalty(RACE, "p-1", new RevokePenaltyRequest(6, "req-revoke"));
        assertThatThrownBy(() -> raceService.submitAppeal(RACE, new SubmitAppealRequest(
                "ak-a", "a", "p-1", 2, "r", 7, "req-aka")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 申诉窗口为finishAt后30分钟含边界() {
        setupRaceWithPenalty();
        // 边界内：恰好30分钟可受理
        clock.advanceMillis(1_800_000L);
        AppealResponse appeal = submitAppeal("ak-1", "a", "p-1", 1, 4, "req-ak1");
        assertThat(appeal.status()).isEqualTo(AppealStatus.PENDING);
    }

    @Test
    void 超过申诉窗口拒绝受理() {
        setupRaceWithPenalty();
        clock.advanceMillis(1_800_001L);
        assertThatThrownBy(() -> raceService.submitAppeal(RACE, new SubmitAppealRequest(
                "ak-1", "a", "p-1", 1, "r", 4, "req-ak1")))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThat(repository.findAppeals(RACE)).isEmpty();
    }

    @Test
    void 已封榜赛事禁止提交申诉() {
        setupRaceWithPenalty();
        raceService.sealRace(RACE, new SealRaceRequest(4, "req-seal"));
        assertThatThrownBy(() -> raceService.submitAppeal(RACE, new SubmitAppealRequest(
                "ak-1", "a", "p-1", 1, "r", 5, "req-ak1")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 待决申诉禁止封榜裁决后可封榜() {
        setupRaceWithPenalty();
        submitAppeal("ak-1", "a", "p-1", 1, 4, "req-ak1");

        assertThatThrownBy(() -> raceService.sealRace(RACE, new SealRaceRequest(4, "req-seal")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("待决申诉");
        assertThat(repository.findRace(RACE).orElseThrow().status()).isEqualTo(RaceStatus.OPEN);

        recommend("ak-1", "off-1", "UPHOLD", null, "req-r1");
        confirm("ak-1", "off-2", "UPHOLD", null, "req-c1");

        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(5, "req-seal2"));
        assertThat(sealed.status()).isEqualTo(200);
        assertThat(repository.findRace(RACE).orElseThrow().status()).isEqualTo(RaceStatus.SEALED);
    }

    @Test
    void 裁决UPHOLD保留处罚并生成新榜单版本() {
        setupRaceWithPenalty();
        submitAppeal("ak-1", "a", "p-1", 1, 4, "req-ak1");

        AppealResponse recommended = recommend("ak-1", "off-1", "UPHOLD", null, "req-r1");
        assertThat(recommended.status()).isEqualTo(AppealStatus.PENDING);
        assertThat(recommended.firstOpinion().officialId()).isEqualTo("off-1");
        assertThat(recommended.firstOpinion().decision()).isEqualTo("UPHOLD");
        // 建议不推进榜单版本
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);

        AppealResponse decided = confirm("ak-1", "off-2", "UPHOLD", null, "req-c1");
        assertThat(decided.status()).isEqualTo(AppealStatus.UPHELD);
        assertThat(decided.secondOpinion().officialId()).isEqualTo("off-2");
        assertThat(decided.secondOpinion().action()).isEqualTo("CONFIRM");
        assertThat(decided.newLeaderboardVersion()).isEqualTo(5);
        assertThat(decided.decidedAt()).isNotNull();
        // 重算前后榜单快照保留且内容一致（处罚未变）
        assertThat(decided.leaderboardBefore()).hasSize(2);
        assertThat(decided.leaderboardAfter()).hasSize(2);
        assertThat(decided.leaderboardAfter())
                .extracting(ResultEntryResponse::bib).containsExactly("b", "a");

        // 只生成一个新榜单版本，处罚保持不变
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
        PenaltyRow penalty = repository.findPenalty("p-1").orElseThrow();
        assertThat(penalty.revoked()).isFalse();
        assertThat(penalty.version()).isEqualTo(1);
        // 裁决后“申诉中”标记解除
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.version()).isEqualTo(5);
        assertThat(standing.entries()).extracting(ResultEntryResponse::appealPending)
                .containsExactly(false, false);
    }

    @Test
    void 裁决REMOVE撤销处罚并重算并列名次() {
        setupRaceWithPenalty();
        submitAppeal("ak-1", "a", "p-1", 1, 4, "req-ak1");
        recommend("ak-1", "off-1", "REMOVE", null, "req-r1");

        AppealResponse decided = confirm("ak-1", "off-2", "REMOVE", null, "req-c1");
        assertThat(decided.status()).isEqualTo(AppealStatus.REMOVED);
        assertThat(decided.newLeaderboardVersion()).isEqualTo(5);

        PenaltyRow penalty = repository.findPenalty("p-1").orElseThrow();
        assertThat(penalty.revoked()).isTrue();
        assertThat(penalty.version()).isEqualTo(2);

        // 重算后 a 恢复与 b 并列第1；快照保留重算前后差异
        assertThat(decided.leaderboardBefore())
                .extracting(ResultEntryResponse::bib).containsExactly("b", "a");
        assertThat(decided.leaderboardAfter())
                .extracting(ResultEntryResponse::bib).containsExactly("a", "b");
        assertThat(decided.leaderboardAfter())
                .extracting(ResultEntryResponse::rank).containsExactly(1, 1);
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 1);
    }

    @Test
    void 裁决REPLACE生成新处罚版本并关联旧版本() {
        setupRaceWithPenalty();
        submitAppeal("ak-1", "a", "p-1", 1, 4, "req-ak1");
        recommend("ak-1", "off-1", "REPLACE", 200L, "req-r1");

        AppealResponse decided = confirm("ak-1", "off-2", "REPLACE", 200L, "req-c1");
        assertThat(decided.status()).isEqualTo(AppealStatus.REPLACED);
        assertThat(decided.newLeaderboardVersion()).isEqualTo(5);

        // 新版本：罚时 200ms、版本 2；旧版本入历史并关联新版本与申诉键
        PenaltyRow penalty = repository.findPenalty("p-1").orElseThrow();
        assertThat(penalty.type()).isEqualTo(PenaltyType.ADD_TIME);
        assertThat(penalty.amountMs()).isEqualTo(200L);
        assertThat(penalty.version()).isEqualTo(2);
        assertThat(penalty.revoked()).isFalse();
        List<PenaltyRevisionRow> revisions = repository.findPenaltyRevisions("p-1");
        assertThat(revisions).hasSize(1);
        assertThat(revisions.getFirst().version()).isEqualTo(1);
        assertThat(revisions.getFirst().amountMs()).isEqualTo(500L);
        assertThat(revisions.getFirst().supersededByVersion()).isEqualTo(2);
        assertThat(revisions.getFirst().appealKey()).isEqualTo("ak-1");

        // 榜单按新罚时重算：a=1200ms 第2，b=1000ms 第1
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("b", "a");
        assertThat(standing.entries().get(1).totalTimeMs()).isEqualTo(1200L);
        assertThat(standing.entries().get(1).penaltyMs()).isEqualTo(200L);
        assertThat(decided.leaderboardAfter().get(1).totalTimeMs()).isEqualTo(1200L);
    }

    @Test
    void 双人约束与驳回后重新建议() {
        setupRaceWithPenalty();
        submitAppeal("ak-1", "a", "p-1", 1, 4, "req-ak1");

        // 无第一人建议时不能确认或驳回 -> 409
        assertThatThrownBy(() -> raceService.confirmAppeal(RACE, "ak-1",
                new ConfirmAppealRequest("off-2", "CONFIRM", "UPHOLD", null, "req-c0")))
                .isInstanceOf(ConflictException.class);

        recommend("ak-1", "off-1", "UPHOLD", null, "req-r1");
        // 已有第一人建议时不能重复建议 -> 409
        assertThatThrownBy(() -> raceService.recommendAppeal(RACE, "ak-1",
                new RecommendAppealRequest("off-3", "REMOVE", null, "req-r2")))
                .isInstanceOf(ConflictException.class);
        // 同一裁决人 -> 422
        assertThatThrownBy(() -> raceService.confirmAppeal(RACE, "ak-1",
                new ConfirmAppealRequest("off-1", "CONFIRM", "UPHOLD", null, "req-c1")))
                .isInstanceOf(UnprocessableEntityException.class);
        // 建议不一致 -> 422
        assertThatThrownBy(() -> raceService.confirmAppeal(RACE, "ak-1",
                new ConfirmAppealRequest("off-2", "CONFIRM", "REMOVE", null, "req-c2")))
                .isInstanceOf(UnprocessableEntityException.class);
        // 驳回：回到 PENDING，第一人建议清空，驳回意见留痕
        ServiceResult rejected = raceService.confirmAppeal(RACE, "ak-1",
                new ConfirmAppealRequest("off-2", "REJECT", null, null, "req-c3"));
        assertThat(rejected.status()).isEqualTo(200);
        AppealResponse afterReject = (AppealResponse) rejected.body();
        assertThat(afterReject.status()).isEqualTo(AppealStatus.PENDING);
        assertThat(afterReject.firstOpinion()).isNull();
        assertThat(afterReject.secondOpinion().action()).isEqualTo("REJECT");
        assertThat(afterReject.secondOpinion().officialId()).isEqualTo("off-2");

        // 驳回后可重新建议并由另一人确认
        recommend("ak-1", "off-1", "REPLACE", 0L, "req-r3");
        AppealResponse decided = confirm("ak-1", "off-2", "REPLACE", 0L, "req-c4");
        assertThat(decided.status()).isEqualTo(AppealStatus.REPLACED);
        assertThat(repository.findPenalty("p-1").orElseThrow().amountMs()).isEqualTo(0L);
    }

    @Test
    void REPLACE建议必须携带非负替代罚时() {
        setupRaceWithPenalty();
        submitAppeal("ak-1", "a", "p-1", 1, 4, "req-ak1");
        // REPLACE 缺少替代罚时 -> 400
        assertThatThrownBy(() -> raceService.recommendAppeal(RACE, "ak-1",
                new RecommendAppealRequest("off-1", "REPLACE", null, "req-r1")))
                .isInstanceOf(BadRequestException.class);
        // UPHOLD 携带替代罚时 -> 400
        assertThatThrownBy(() -> raceService.recommendAppeal(RACE, "ak-1",
                new RecommendAppealRequest("off-1", "UPHOLD", 100L, "req-r2")))
                .isInstanceOf(BadRequestException.class);
        // 确认时替代罚时与第一人不一致 -> 422
        recommend("ak-1", "off-1", "REPLACE", 200L, "req-r3");
        assertThatThrownBy(() -> raceService.confirmAppeal(RACE, "ak-1",
                new ConfirmAppealRequest("off-2", "CONFIRM", "REPLACE", 300L, "req-c1")))
                .isInstanceOf(UnprocessableEntityException.class);
        // 失败不占键：修正后可复用同一 requestId
        AppealResponse decided = confirm("ak-1", "off-2", "REPLACE", 200L, "req-c1");
        assertThat(decided.status()).isEqualTo(AppealStatus.REPLACED);
    }

    @Test
    void 受理后赛事版本变化则确认整体失败且现场不变() {
        setupRaceWithPenalty();
        submitAppeal("ak-1", "a", "p-1", 1, 4, "req-ak1");
        recommend("ak-1", "off-1", "REMOVE", null, "req-r1");

        // 受理后发生计时修订（榜单版本推进到5）
        raceService.reviseTime(RACE, new ReviseTimeRequest("b", 900L, 4, "req-tb"));

        assertThatThrownBy(() -> raceService.confirmAppeal(RACE, "ak-1",
                new ConfirmAppealRequest("off-2", "CONFIRM", "REMOVE", null, "req-c1")))
                .isInstanceOf(ConflictException.class);
        // 申诉仍 PENDING、处罚未撤销、榜单版本未再推进、榜单内容未重算
        AppealRow appeal = repository.findAppeal("ak-1").orElseThrow();
        assertThat(appeal.status()).isEqualTo(AppealStatus.PENDING);
        assertThat(appeal.leaderboardBefore()).isNull();
        PenaltyRow penalty = repository.findPenalty("p-1").orElseThrow();
        assertThat(penalty.revoked()).isFalse();
        assertThat(penalty.version()).isEqualTo(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("b", "a");
        assertThat(standing.entries().get(1).appealPending()).isTrue();
    }

    @Test
    void 受理后处罚被撤销则确认失败且申诉仍待决() {
        setupRaceWithPenalty();
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-2", "b", "ADD_TIME", 100L, 4, "req-p2"));
        submitAppeal("ak-2", "b", "p-2", 1, 5, "req-ak2");
        recommend("ak-2", "off-1", "UPHOLD", null, "req-r1");

        raceService.revokePenalty(RACE, "p-2", new RevokePenaltyRequest(5, "req-revoke"));

        assertThatThrownBy(() -> raceService.confirmAppeal(RACE, "ak-2",
                new ConfirmAppealRequest("off-2", "CONFIRM", "UPHOLD", null, "req-c1")))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findAppeal("ak-2").orElseThrow().status())
                .isEqualTo(AppealStatus.PENDING);
    }

    @Test
    void 已封榜后禁止建议与确认() {
        setupRaceWithPenalty();
        submitAppeal("ak-1", "a", "p-1", 1, 4, "req-ak1");
        recommend("ak-1", "off-1", "UPHOLD", null, "req-r1");
        confirm("ak-1", "off-2", "UPHOLD", null, "req-c1");
        raceService.sealRace(RACE, new SealRaceRequest(5, "req-seal"));

        // 已裁决申诉再次确认 -> 409（状态非 PENDING）
        assertThatThrownBy(() -> raceService.confirmAppeal(RACE, "ak-1",
                new ConfirmAppealRequest("off-3", "CONFIRM", "UPHOLD", null, "req-c2")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.recommendAppeal(RACE, "ak-1",
                new RecommendAppealRequest("off-3", "UPHOLD", null, "req-r2")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 幂等重放与异参冲突() {
        setupRaceWithPenalty();
        // 提交重放：同键同参返回首次响应（重放体为 JsonNode），仅一条申诉
        AppealResponse first = submitAppeal("ak-1", "a", "p-1", 1, 4, "req-ak1");
        ServiceResult replayed = raceService.submitAppeal(RACE, new SubmitAppealRequest(
                "ak-1", "a", "p-1", 1, "判罚有误", 4, "req-ak1"));
        assertThat(replayed.status()).isEqualTo(201);
        assertThat(((com.fasterxml.jackson.databind.JsonNode) replayed.body())
                .get("appealKey").asText()).isEqualTo(first.appealKey());
        assertThat(repository.findAppeals(RACE)).hasSize(1);
        // 同键异参 -> 409
        assertThatThrownBy(() -> raceService.submitAppeal(RACE, new SubmitAppealRequest(
                "ak-1", "a", "p-1", 1, "另一个理由", 4, "req-ak1")))
                .isInstanceOf(ConflictException.class);

        // 建议与确认重放：榜单版本只推进一次
        recommend("ak-1", "off-1", "UPHOLD", null, "req-r1");
        ServiceResult recommendReplay = raceService.recommendAppeal(RACE, "ak-1",
                new RecommendAppealRequest("off-1", "UPHOLD", null, "req-r1"));
        assertThat(recommendReplay.status()).isEqualTo(200);
        confirm("ak-1", "off-2", "UPHOLD", null, "req-c1");
        ServiceResult confirmReplay = raceService.confirmAppeal(RACE, "ak-1",
                new ConfirmAppealRequest("off-2", "CONFIRM", "UPHOLD", null, "req-c1"));
        assertThat(confirmReplay.status()).isEqualTo(200);
        assertThat(((com.fasterxml.jackson.databind.JsonNode) confirmReplay.body())
                .get("status").asText()).isEqualTo("UPHELD");
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
        // 确认同键异参 -> 409
        assertThatThrownBy(() -> raceService.confirmAppeal(RACE, "ak-1",
                new ConfirmAppealRequest("off-2", "CONFIRM", "REMOVE", null, "req-c1")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 申诉证据查询只读且稳定排序() {
        setupRaceWithPenalty();
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-2", "b", "ADD_TIME", 100L, 4, "req-p2"));
        submitAppeal("ak-2", "b", "p-2", 1, 5, "req-ak2");
        submitAppeal("ak-1", "a", "p-1", 1, 5, "req-ak1");
        recommend("ak-1", "off-1", "REMOVE", null, "req-r1");
        confirm("ak-1", "off-2", "REMOVE", null, "req-c1");

        int versionBefore = repository.findRace(RACE).orElseThrow().version();
        AppealListResponse list = raceService.listAppeals(RACE);
        // 同一时刻受理，按申诉键字典序稳定排列
        assertThat(list.appeals()).extracting(AppealResponse::appealKey)
                .containsExactly("ak-1", "ak-2");
        AppealResponse decided = list.appeals().getFirst();
        assertThat(decided.status()).isEqualTo(AppealStatus.REMOVED);
        assertThat(decided.firstOpinion().officialId()).isEqualTo("off-1");
        assertThat(decided.secondOpinion().officialId()).isEqualTo("off-2");
        assertThat(decided.leaderboardBefore()).isNotEmpty();
        assertThat(decided.leaderboardAfter()).isNotEmpty();
        assertThat(list.appeals().get(1).status()).isEqualTo(AppealStatus.PENDING);

        AppealResponse single = raceService.getAppeal(RACE, "ak-1");
        assertThat(single.freeze().leaderboardVersion()).isEqualTo(5);
        // 只读：查询不推进版本
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(versionBefore);

        assertThatThrownBy(() -> raceService.getAppeal(RACE, "ak-x"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> raceService.listAppeals("race-x"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 取消资格处罚经REPLACE转为加时并恢复排名() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 1000L, 1, "req-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", 2000L, 2, "req-b"));
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-1", "a", "DISQUALIFY", null, 3, "req-p1"));
        // 取消资格生效中：a 不排名
        assertThat(raceService.getResults(RACE).entries().get(1).status())
                .isEqualTo(EntryStatus.DISQUALIFIED);

        submitAppeal("ak-1", "a", "p-1", 1, 4, "req-ak1");
        recommend("ak-1", "off-1", "REPLACE", 500L, "req-r1");
        AppealResponse decided = confirm("ak-1", "off-2", "REPLACE", 500L, "req-c1");

        assertThat(decided.status()).isEqualTo(AppealStatus.REPLACED);
        PenaltyRow penalty = repository.findPenalty("p-1").orElseThrow();
        assertThat(penalty.type()).isEqualTo(PenaltyType.ADD_TIME);
        assertThat(penalty.amountMs()).isEqualTo(500L);
        // a 恢复排名：1000+500=1500 第1，b=2000 第2
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b");
        assertThat(standing.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2);
        // 旧版本为取消资格
        List<PenaltyRevisionRow> revisions = repository.findPenaltyRevisions("p-1");
        assertThat(revisions.getFirst().type()).isEqualTo(PenaltyType.DISQUALIFY);
        assertThat(revisions.getFirst().amountMs()).isNull();
    }
}
