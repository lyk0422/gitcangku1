package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.AppealOpinionRequest;
import com.example.starter.race.api.AppealResponse;
import com.example.starter.race.api.AppealSegmentResponse;
import com.example.starter.race.api.AppealsResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitAppealRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.domain.AppealStatus;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 处罚申诉冻结与两干事裁决的 H2 数据库测试：
 * 三类裁决（UPHOLD/REMOVE/REPLACE）、双人约束、封榜门禁、整体回滚、
 * 幂等、冻结证据保留及受理窗口。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class AppealServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-appeal";
    private static final String PENALTY = "pen-b";
    private static final String APPEAL = "appeal-1";
    private static final String STEWARD_1 = "steward-1";
    private static final String STEWARD_2 = "steward-2";

    @Autowired
    private RaceService raceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 建赛 v1、登记 a=1000 v2、b=1000 v3、给 b 加时500 v4。a 第1、b(1500) 第2。 */
    private void seedRaceWithPenalty() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 1000L, 1, "req-a"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", 1000L, 2, "req-b"));
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                PENALTY, "b", "ADD_TIME", 500L, 3, "req-pen-b"));
    }

    private AppealResponse submitAppeal(String requestId) {
        ServiceResult result = raceService.submitAppeal(RACE, PENALTY,
                new SubmitAppealRequest(APPEAL, 1, "计时器误判", requestId));
        assertThat(result.status()).isEqualTo(201);
        return (AppealResponse) result.body();
    }

    private void firstOpinion(String recommendation, Long replacementMs, String requestId) {
        ServiceResult result = raceService.submitAppealOpinion(RACE, APPEAL,
                new AppealOpinionRequest(STEWARD_1, recommendation, replacementMs, null, requestId));
        assertThat(result.status()).isEqualTo(200);
    }

    private ServiceResult secondOpinion(String steward, String action, String recommendation,
                                        Long replacementMs, String requestId) {
        return raceService.submitAppealOpinion(RACE, APPEAL,
                new AppealOpinionRequest(steward, recommendation, replacementMs, action, requestId));
    }

    private ResultEntryResponse entry(StandingResponse standing, String bib) {
        return standing.entries().stream()
                .filter(e -> e.bib().equals(bib))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void 受理申诉冻结且榜单按原处罚计算并标记申诉中_版本不变() {
        seedRaceWithPenalty();

        AppealResponse appeal = submitAppeal("req-appeal");

        assertThat(appeal.status()).isEqualTo(AppealStatus.PENDING);
        assertThat(appeal.penaltyId()).isEqualTo(PENALTY);
        assertThat(appeal.bib()).isEqualTo("b");
        assertThat(appeal.frozenResult().leaderboardVersion()).isEqualTo(4);
        assertThat(appeal.frozenResult().penaltyMs()).isEqualTo(500L);
        assertThat(appeal.frozenResult().totalTimeMs()).isEqualTo(1500L);
        assertThat(appeal.frozenResult().rank()).isEqualTo(2);
        assertThat(appeal.beforeLeaderboard().version()).isEqualTo(4);
        assertThat(appeal.afterLeaderboard()).isNull();

        // 受理不推进版本，公开榜单仍按原处罚计算，b 被标记为申诉中。
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.version()).isEqualTo(4);
        assertThat(standing.status()).isEqualTo(RaceStatus.OPEN);
        ResultEntryResponse b = entry(standing, "b");
        assertThat(b.appealPending()).isTrue();
        assertThat(b.totalTimeMs()).isEqualTo(1500L);
        assertThat(entry(standing, "a").appealPending()).isFalse();

        // 冻结不删除处罚，处罚仍生效。
        PenaltyRow penalty = repository.findPenalty(PENALTY).orElseThrow();
        assertThat(penalty.revoked()).isFalse();
        assertThat(penalty.superseded()).isFalse();
    }

    @Test
    void UPHOLD裁决保留处罚且只推进一个版本() {
        seedRaceWithPenalty();
        submitAppeal("req-appeal");
        firstOpinion("UPHOLD", null, "req-first");

        ServiceResult result = secondOpinion(STEWARD_2, "CONFIRM", "UPHOLD", null, "req-second");
        AppealResponse decided = (AppealResponse) result.body();

        assertThat(result.status()).isEqualTo(200);
        assertThat(decided.status()).isEqualTo(AppealStatus.UPHELD);
        assertThat(decided.firstStewardId()).isEqualTo(STEWARD_1);
        assertThat(decided.secondStewardId()).isEqualTo(STEWARD_2);
        assertThat(decided.newPenaltyId()).isNull();
        assertThat(decided.afterLeaderboard().version()).isEqualTo(5);

        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(5);
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(entry(standing, "b").totalTimeMs()).isEqualTo(1500L);
        assertThat(entry(standing, "b").appealPending()).isFalse();
        assertThat(repository.findPenalty(PENALTY).orElseThrow().revoked()).isFalse();
    }

    @Test
    void REMOVE裁决撤销处罚并重算排名() {
        seedRaceWithPenalty();
        submitAppeal("req-appeal");
        firstOpinion("REMOVE", null, "req-first");

        ServiceResult result = secondOpinion(STEWARD_2, "CONFIRM", "REMOVE", null, "req-second");
        AppealResponse decided = (AppealResponse) result.body();

        assertThat(decided.status()).isEqualTo(AppealStatus.REMOVED);
        assertThat(decided.afterLeaderboard().version()).isEqualTo(5);
        // 撤销后 b 回到1000，与 a 并列第1。
        ResultEntryResponse b = entry(decided.afterLeaderboard(), "b");
        assertThat(b.totalTimeMs()).isEqualTo(1000L);
        assertThat(b.rank()).isEqualTo(1);
        assertThat(entry(decided.afterLeaderboard(), "a").rank()).isEqualTo(1);

        assertThat(repository.findPenalty(PENALTY).orElseThrow().revoked()).isTrue();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
    }

    @Test
    void REPLACE裁决生成关联旧版本的新处罚并重算() {
        seedRaceWithPenalty();
        submitAppeal("req-appeal");
        firstOpinion("REPLACE", 200L, "req-first");

        ServiceResult result = secondOpinion(STEWARD_2, "CONFIRM", "REPLACE", 200L, "req-second");
        AppealResponse decided = (AppealResponse) result.body();

        assertThat(decided.status()).isEqualTo(AppealStatus.REPLACED);
        assertThat(decided.newPenaltyId()).isNotBlank();
        // 旧版本被取代并保留，新版本关联旧版本。
        PenaltyRow oldPenalty = repository.findPenalty(PENALTY).orElseThrow();
        assertThat(oldPenalty.superseded()).isTrue();
        assertThat(oldPenalty.revoked()).isFalse();
        PenaltyRow newPenalty = repository.findPenalty(decided.newPenaltyId()).orElseThrow();
        assertThat(newPenalty.supersedesPenaltyId()).isEqualTo(PENALTY);
        assertThat(newPenalty.version()).isEqualTo(2);
        assertThat(newPenalty.amountMs()).isEqualTo(200L);
        assertThat(newPenalty.bib()).isEqualTo("b");

        // b 净成绩变为1200，仍第2。
        ResultEntryResponse b = entry(decided.afterLeaderboard(), "b");
        assertThat(b.totalTimeMs()).isEqualTo(1200L);
        assertThat(b.penaltyMs()).isEqualTo(200L);
        assertThat(b.rank()).isEqualTo(2);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
    }

    @Test
    void REPLACE支持零替代罚时() {
        seedRaceWithPenalty();
        submitAppeal("req-appeal");
        firstOpinion("REPLACE", 0L, "req-first");

        ServiceResult result = secondOpinion(STEWARD_2, "CONFIRM", "REPLACE", 0L, "req-second");
        AppealResponse decided = (AppealResponse) result.body();

        assertThat(decided.status()).isEqualTo(AppealStatus.REPLACED);
        assertThat(entry(decided.afterLeaderboard(), "b").totalTimeMs()).isEqualTo(1000L);
        PenaltyRow newPenalty = repository.findPenalty(decided.newPenaltyId()).orElseThrow();
        assertThat(newPenalty.amountMs()).isZero();
    }

    @Test
    void 第二人驳回_处罚与榜单不变且状态REJECTED() {
        seedRaceWithPenalty();
        submitAppeal("req-appeal");
        firstOpinion("UPHOLD", null, "req-first");
        int versionBefore = repository.findRace(RACE).orElseThrow().version();

        ServiceResult result = secondOpinion(STEWARD_2, "REJECT", null, null, "req-reject");
        AppealResponse decided = (AppealResponse) result.body();

        assertThat(decided.status()).isEqualTo(AppealStatus.REJECTED);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(versionBefore);
        assertThat(repository.findPenalty(PENALTY).orElseThrow().revoked()).isFalse();
        assertThat(entry(raceService.getResults(RACE), "b").totalTimeMs()).isEqualTo(1500L);
    }

    @Test
    void 双人约束_同一人_建议不一致_替代罚时不一致均失败且仍PENDING() {
        seedRaceWithPenalty();
        submitAppeal("req-appeal");
        firstOpinion("REPLACE", 200L, "req-first");

        // 同一干事不能裁决
        assertThatThrownBy(() -> secondOpinion(STEWARD_1, "CONFIRM", "REPLACE", 200L, "req-same"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("同一人");
        // 建议不一致
        assertThatThrownBy(() -> secondOpinion(STEWARD_2, "CONFIRM", "REMOVE", null, "req-diff-rec"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("不一致");
        // 替代罚时不一致
        assertThatThrownBy(() -> secondOpinion(STEWARD_2, "CONFIRM", "REPLACE", 201L, "req-diff-ms"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("替代罚时");

        // 申诉仍 PENDING，版本不变，意见未写第二人。
        AppealResponse appeal = raceService.getAppeal(RACE, APPEAL);
        assertThat(appeal.status()).isEqualTo(AppealStatus.PENDING);
        assertThat(appeal.secondStewardId()).isNull();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
    }

    @Test
    void 第一人规则_缺替代罚时_非REPLACE带罚时_重复提交_第二人先到() {
        seedRaceWithPenalty();
        submitAppeal("req-appeal");

        // REPLACE 缺 replacementMs → 400
        assertThatThrownBy(() -> raceService.submitAppealOpinion(RACE, APPEAL,
                new AppealOpinionRequest(STEWARD_1, "REPLACE", null, null, "req-no-ms")))
                .isInstanceOf(BadRequestException.class);
        // UPHOLD 带 replacementMs → 400
        assertThatThrownBy(() -> raceService.submitAppealOpinion(RACE, APPEAL,
                new AppealOpinionRequest(STEWARD_1, "UPHOLD", 10L, null, "req-extra-ms")))
                .isInstanceOf(BadRequestException.class);
        // 未知建议
        assertThatThrownBy(() -> raceService.submitAppealOpinion(RACE, APPEAL,
                new AppealOpinionRequest(STEWARD_1, "NOPE", null, null, "req-bad")))
                .isInstanceOf(BadRequestException.class);

        firstOpinion("UPHOLD", null, "req-first");
        // 第一人建议重复提交 → 409
        assertThatThrownBy(() -> raceService.submitAppealOpinion(RACE, APPEAL,
                new AppealOpinionRequest(STEWARD_2, "UPHOLD", null, null, "req-first-again")))
                .isInstanceOf(ConflictException.class);
        // 已裁决后再提交 → 409
        secondOpinion(STEWARD_2, "CONFIRM", "UPHOLD", null, "req-second");
        assertThatThrownBy(() -> raceService.submitAppealOpinion(RACE, APPEAL,
                new AppealOpinionRequest(STEWARD_2, "REJECT", null, null, "req-after")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 第二人先于第一人提交返回409() {
        seedRaceWithPenalty();
        submitAppeal("req-appeal");

        assertThatThrownBy(() -> secondOpinion(STEWARD_2, "CONFIRM", "UPHOLD", null, "req-early"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("第一人尚未");
    }

    @Test
    void 待决申诉期间禁止封榜_裁决后可封榜() {
        seedRaceWithPenalty();
        submitAppeal("req-appeal");

        assertThatThrownBy(() -> raceService.sealRace(RACE, new SealRaceRequest(4, "req-seal")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("待决申诉");

        firstOpinion("UPHOLD", null, "req-first");
        secondOpinion(STEWARD_2, "CONFIRM", "UPHOLD", null, "req-second");

        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(5, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
        assertThat(repository.findRace(RACE).orElseThrow().status()).isEqualTo(RaceStatus.SEALED);
    }

    @Test
    void 待决申诉期间禁止撤销该处罚() {
        seedRaceWithPenalty();
        submitAppeal("req-appeal");

        assertThatThrownBy(() -> raceService.revokePenalty(RACE, PENALTY,
                new RevokePenaltyRequest(4, "req-revoke")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("待决申诉");
        assertThat(repository.findPenalty(PENALTY).orElseThrow().revoked()).isFalse();
    }

    @Test
    void 计时版本变化后裁决整体回滚_申诉仍PENDING_榜单不变() {
        seedRaceWithPenalty();
        submitAppeal("req-appeal");
        firstOpinion("REMOVE", null, "req-first");

        // 受理后选手计时修订（推进计时版本），与裁决并发。
        raceService.reviseTime(RACE, new ReviseTimeRequest("b", 1001L, 4, "req-revise"));
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);

        assertThatThrownBy(() -> secondOpinion(STEWARD_2, "CONFIRM", "REMOVE", null, "req-second"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("计时版本");

        AppealResponse appeal = raceService.getAppeal(RACE, APPEAL);
        assertThat(appeal.status()).isEqualTo(AppealStatus.PENDING);
        // 处罚未撤销、未生成新版本，榜单只反映计时修订那一次推进，无额外裁决推进。
        assertThat(repository.findPenalty(PENALTY).orElseThrow().revoked()).isFalse();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
        assertThat(entry(raceService.getResults(RACE), "b").totalTimeMs()).isEqualTo(1501L);
    }

    @Test
    void 分段判定版本变化后裁决整体回滚() {
        // 配置两个检查点以便受理后新增分段。
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", 10_000L, 1, "req-b"));
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(
                List.of(new ConfigureCheckpointsRequest.CheckpointDefinition("cp1", 1),
                        new ConfigureCheckpointsRequest.CheckpointDefinition("cp2", 2)),
                2, "req-cp"));
        raceService.submitTiming(RACE, "b", new SubmitTimingRequest(
                "t-1", "cp1", 100L, 3, "req-t1"));
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                PENALTY, "b", "ADD_TIME", 500L, 4, "req-pen"));

        submitAppeal("req-appeal");
        firstOpinion("REMOVE", null, "req-first");

        // 受理后补录 cp2，推进分段判定版本。
        raceService.submitTiming(RACE, "b", new SubmitTimingRequest(
                "t-2", "cp2", 200L, 5, "req-t2"));

        assertThatThrownBy(() -> secondOpinion(STEWARD_2, "CONFIRM", "REMOVE", null, "req-second"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("分段判定版本");
        assertThat(raceService.getAppeal(RACE, APPEAL).status()).isEqualTo(AppealStatus.PENDING);
        assertThat(repository.findPenalty(PENALTY).orElseThrow().revoked()).isFalse();
    }

    @Test
    void 处罚版本变化后裁决整体回滚() {
        seedRaceWithPenalty();
        submitAppeal("req-appeal");
        firstOpinion("REMOVE", null, "req-first");

        // 直接模拟被申诉处罚版本被其他 REPLACE 裁决推进（同一处罚的并发改判）。
        int changed = jdbcTemplate.update(
                "UPDATE penalty SET version = version + 1 WHERE penalty_id = ?", PENALTY);
        assertThat(changed).isOne();

        assertThatThrownBy(() -> secondOpinion(STEWARD_2, "CONFIRM", "REMOVE", null, "req-second"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("处罚版本");
        assertThat(raceService.getAppeal(RACE, APPEAL).status()).isEqualTo(AppealStatus.PENDING);
    }

    @Test
    void 封榜后裁决失败且仍PENDING() {
        seedRaceWithPenalty();
        submitAppeal("req-appeal");
        firstOpinion("UPHOLD", null, "req-first");
        // 无法在有待决申诉时正常封榜；直接置为 SEALED 模拟封榜并发先提交。
        jdbcTemplate.update("UPDATE race SET status = 'SEALED' WHERE race_id = ?", RACE);

        assertThatThrownBy(() -> secondOpinion(STEWARD_2, "CONFIRM", "UPHOLD", null, "req-second"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
        assertThat(raceService.getAppeal(RACE, APPEAL).status()).isEqualTo(AppealStatus.PENDING);
    }

    @Test
    void 受理失败分支_版本_生效_重复_窗口_封榜_不存在() {
        seedRaceWithPenalty();

        // 处罚版本不一致
        assertThatThrownBy(() -> raceService.submitAppeal(RACE, PENALTY,
                new SubmitAppealRequest(APPEAL, 9, "r", "req-ver")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("处罚版本冲突");
        // 申诉不存在
        assertThatThrownBy(() -> raceService.getAppeal(RACE, "nope"))
                .isInstanceOf(NotFoundException.class);
        // 处罚不存在
        assertThatThrownBy(() -> raceService.submitAppeal(RACE, "ghost",
                new SubmitAppealRequest(APPEAL, 1, "r", "req-ghost")))
                .isInstanceOf(NotFoundException.class);

        // 超过30分钟窗口
        long expiredAt = FixedClockTestConfig.FIXED_INSTANT.toEpochMilli()
                - (31L * 60L * 1000L);
        jdbcTemplate.update("UPDATE runner SET updated_at = ? WHERE race_id = ? AND bib = 'b'",
                expiredAt, RACE);
        assertThatThrownBy(() -> raceService.submitAppeal(RACE, PENALTY,
                new SubmitAppealRequest(APPEAL, 1, "r", "req-late")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("30分钟");
        jdbcTemplate.update("UPDATE runner SET updated_at = ? WHERE race_id = ? AND bib = 'b'",
                FixedClockTestConfig.FIXED_INSTANT.toEpochMilli(), RACE);

        // 正常受理
        submitAppeal("req-appeal");
        // 同一处罚重复待决申诉
        assertThatThrownBy(() -> raceService.submitAppeal(RACE, PENALTY,
                new SubmitAppealRequest("appeal-2", 1, "r", "req-dup-penalty")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("待决申诉");
        // appealKey 全局唯一（不同处罚也不能复用）
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "pen-a", "a", "ADD_TIME", 10L, 4, "req-pen-a"));
        assertThatThrownBy(() -> raceService.submitAppeal(RACE, "pen-a",
                new SubmitAppealRequest(APPEAL, 1, "r", "req-dup-key")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("appealKey");

        // 封榜后禁止申诉：先裁决解锁、再封榜
        firstOpinion("UPHOLD", null, "req-first");
        secondOpinion(STEWARD_2, "CONFIRM", "UPHOLD", null, "req-second");
        raceService.sealRace(RACE, new SealRaceRequest(6, "req-seal"));
        assertThatThrownBy(() -> raceService.submitAppeal(RACE, "pen-a",
                new SubmitAppealRequest("appeal-3", 1, "r", "req-sealed")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
    }

    @Test
    void 已撤销或已取代处罚不能申诉() {
        seedRaceWithPenalty();
        raceService.revokePenalty(RACE, PENALTY,
                new RevokePenaltyRequest(4, "req-revoke"));
        assertThatThrownBy(() -> raceService.submitAppeal(RACE, PENALTY,
                new SubmitAppealRequest(APPEAL, 1, "r", "req-revoked")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("未生效");
    }

    @Test
    void 申诉幂等_同参重放异参冲突失败不占键() {
        seedRaceWithPenalty();
        SubmitAppealRequest request =
                new SubmitAppealRequest(APPEAL, 1, "理由", "req-appeal");

        ServiceResult first = raceService.submitAppeal(RACE, PENALTY, request);
        ServiceResult replay = raceService.submitAppeal(RACE, PENALTY, request);
        assertThat(replay.status()).isEqualTo(first.status());

        // 异参 409
        assertThatThrownBy(() -> raceService.submitAppeal(RACE, PENALTY,
                new SubmitAppealRequest(APPEAL, 1, "不同理由", "req-appeal")))
                .isInstanceOf(ConflictException.class);

        // 失败不占键：先用错误版本失败，再用相同 requestId 成功
        assertThatThrownBy(() -> raceService.submitAppeal(RACE, "pen-a",
                new SubmitAppealRequest("appeal-x", 1, "r", "req-retry")))
                .isInstanceOf(NotFoundException.class);
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "pen-a", "a", "ADD_TIME", 10L, 4, "req-pen-a"));
        ServiceResult retry = raceService.submitAppeal(RACE, "pen-a",
                new SubmitAppealRequest("appeal-x", 1, "r", "req-retry"));
        assertThat(retry.status()).isEqualTo(201);

        // 意见同参重放（appeal-x 的第一人建议）
        ServiceResult o1 = raceService.submitAppealOpinion(RACE, "appeal-x",
                new AppealOpinionRequest(STEWARD_1, "UPHOLD", null, null, "req-first-x"));
        ServiceResult o1Replay = raceService.submitAppealOpinion(RACE, "appeal-x",
                new AppealOpinionRequest(STEWARD_1, "UPHOLD", null, null, "req-first-x"));
        assertThat(o1Replay.status()).isEqualTo(o1.status());
    }

    @Test
    void 冻结证据保留_分段明细按顺序且证据列表稳定排序() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", 10_000L, 1, "req-b"));
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(
                List.of(new ConfigureCheckpointsRequest.CheckpointDefinition("cp1", 1),
                        new ConfigureCheckpointsRequest.CheckpointDefinition("cp2", 2)),
                2, "req-cp"));
        raceService.submitTiming(RACE, "b", new SubmitTimingRequest(
                "t-1", "cp1", 100L, 3, "req-t1"));
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                PENALTY, "b", "ADD_TIME", 500L, 4, "req-pen"));

        submitAppeal("req-appeal");
        AppealResponse appeal = raceService.getAppeal(RACE, APPEAL);
        List<AppealSegmentResponse> segments = appeal.frozenSegments();
        assertThat(segments).extracting(AppealSegmentResponse::checkpointCode)
                .containsExactly("cp1", "cp2");
        assertThat(segments.get(0).elapsedMillis()).isEqualTo(100L);
        assertThat(segments.get(0).timingId()).isEqualTo("t-1");
        assertThat(segments.get(1).elapsedMillis()).isNull();
        assertThat(segments.get(1).timingId()).isNull();

        firstOpinion("REMOVE", null, "req-first");
        secondOpinion(STEWARD_2, "CONFIRM", "REMOVE", null, "req-second");

        // 裁决后受理冻结、两人意见与前后榜单快照仍保留。
        AppealResponse retained = raceService.getAppeal(RACE, APPEAL);
        assertThat(retained.frozenSegments()).hasSize(2);
        assertThat(retained.beforeLeaderboard().version()).isEqualTo(5);
        assertThat(retained.afterLeaderboard().version()).isEqualTo(6);
        assertThat(retained.firstStewardId()).isEqualTo(STEWARD_1);
        assertThat(retained.secondStewardId()).isEqualTo(STEWARD_2);

        AppealsResponse list = raceService.getAppeals(RACE);
        assertThat(list.appeals()).extracting(AppealResponse::appealKey)
                .containsExactly(APPEAL);
    }

    @Test
    void 冻结成绩支持未排名状态() {
        // 给 b 取消资格：受理时冻结的成绩状态为 DISQUALIFIED。
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", 1000L, 1, "req-b"));
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                PENALTY, "b", "DISQUALIFY", null, 2, "req-dq"));

        AppealResponse appeal = submitAppeal("req-appeal");
        assertThat(appeal.frozenResult().status()).isEqualTo(EntryStatus.DISQUALIFIED);
        assertThat(appeal.frozenResult().rank()).isNull();
        assertThat(appeal.frozenResult().totalTimeMs()).isNull();
        // DISQUALIFY 也可 REMOVE 恢复排名。
        firstOpinion("REMOVE", null, "req-first");
        AppealResponse decided = (AppealResponse) secondOpinion(
                STEWARD_2, "CONFIRM", "REMOVE", null, "req-second").body();
        assertThat(entry(decided.afterLeaderboard(), "b").status())
                .isEqualTo(EntryStatus.RANKED);
    }
}
