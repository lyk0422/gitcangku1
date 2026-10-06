package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.HandicapFactorResponse;
import com.example.starter.race.api.RaceResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SelectScoringModeRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.UpsertHandicapFactorRequest;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ScoringMode;
import com.example.starter.race.persistence.SnapshotEntryRow;
import com.example.starter.race.persistence.SnapshotRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 让分赛（HANDICAP）主流程的 H2 数据库测试：
 * 选模式、登记系数、精确分数排名、缺系数不排名、封榜冻结与封榜后只读。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class HandicapServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-handicap";

    @Autowired
    private RaceService raceService;

    private void createHandicapRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.selectScoringMode(RACE,
                new SelectScoringModeRequest("HANDICAP", 1, "req-mode"));
    }

    @Test
    void 让分赛主流程_展示相同不并列_封榜冻结系数与精确分数() {
        createHandicapRace();
        // v3/v4: 合成数据 A 原时1000、B 原时999
        raceService.registerRunner(RACE, new RegisterRunnerRequest("A", 1000L, 2, "req-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("B", 999L, 3, "req-b"));
        // v5/v6: 系数均1/3（B 以等比例 2/6 登记，存储约分后的 1/3）
        raceService.upsertHandicapFactor(RACE, "A",
                new UpsertHandicapFactorRequest(1, 3, 4, "req-fa"));
        ServiceResult factorB = raceService.upsertHandicapFactor(RACE, "B",
                new UpsertHandicapFactorRequest(2, 6, 5, "req-fb"));
        HandicapFactorResponse factorBody = (HandicapFactorResponse) factorB.body();
        assertThat(factorBody.p()).isEqualTo(1);
        assertThat(factorBody.q()).isEqualTo(3);
        assertThat(factorBody.version()).isEqualTo(6);

        StandingResponse live = raceService.getResults(RACE);
        assertThat(live.scoringMode()).isEqualTo(ScoringMode.HANDICAP);
        // 展示均333毫秒但精确分数不同：B 先 A 后
        assertThat(live.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("B", "A");
        assertThat(live.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2);
        assertThat(live.entries()).extracting(ResultEntryResponse::totalTimeMs)
                .containsExactly(333L, 333L);
        assertThat(live.entries().get(0).factorP()).isEqualTo(1);
        assertThat(live.entries().get(0).factorQ()).isEqualTo(3);
        assertThat(live.entries().get(0).scoreNumerator()).isEqualTo(333L);
        assertThat(live.entries().get(0).scoreDenominator()).isEqualTo(1L);
        assertThat(live.entries().get(1).scoreNumerator()).isEqualTo(1000L);
        assertThat(live.entries().get(1).scoreDenominator()).isEqualTo(3L);

        // v7: 封榜，快照冻结系数、精确分子分母、展示值与名次
        raceService.sealRace(RACE, new SealRaceRequest(6, "req-seal"));
        SnapshotRow snapshot = repository.findSnapshot(RACE).orElseThrow();
        assertThat(snapshot.scoringMode()).isEqualTo(ScoringMode.HANDICAP);
        SnapshotEntryRow snapB = snapshot.entries().get(0);
        SnapshotEntryRow snapA = snapshot.entries().get(1);
        assertThat(snapB.bib()).isEqualTo("B");
        assertThat(snapB.rank()).isEqualTo(1);
        assertThat(snapB.factorP()).isEqualTo(1);
        assertThat(snapB.factorQ()).isEqualTo(3);
        assertThat(snapB.scoreNumerator()).isEqualTo(333L);
        assertThat(snapB.scoreDenominator()).isEqualTo(1L);
        assertThat(snapB.totalTimeMs()).isEqualTo(333L);
        assertThat(snapA.rank()).isEqualTo(2);
        assertThat(snapA.scoreNumerator()).isEqualTo(1000L);
        assertThat(snapA.scoreDenominator()).isEqualTo(3L);

        StandingResponse sealed = raceService.getResults(RACE);
        assertThat(sealed.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(sealed.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("B", "A");

        // 封榜后修改系数/模式均 409，只读快照不可重排
        assertThatThrownBy(() -> raceService.upsertHandicapFactor(RACE, "A",
                new UpsertHandicapFactorRequest(1, 2, 7, "req-fa2")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.selectScoringMode(RACE,
                new SelectScoringModeRequest("HANDICAP", 7, "req-mode2")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 缺系数选手不排名并说明MISSING_FACTOR() {
        createHandicapRace();
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 400L, 2, "req-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", 300L, 3, "req-b"));
        raceService.upsertHandicapFactor(RACE, "a",
                new UpsertHandicapFactorRequest(1, 2, 4, "req-fa"));

        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b");
        assertThat(standing.entries()).extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.MISSING_FACTOR);
        assertThat(standing.entries().get(1).rank()).isNull();
        assertThat(standing.entries().get(1).totalTimeMs()).isNull();

        // 补登记系数后恢复排名
        raceService.upsertHandicapFactor(RACE, "b",
                new UpsertHandicapFactorRequest(1, 1, 5, "req-fb"));
        StandingResponse restored = raceService.getResults(RACE);
        assertThat(restored.entries()).extracting(ResultEntryResponse::status)
                .containsOnly(EntryStatus.RANKED);
        // a: 400×1/2=200 排第1；b: 300×1/1=300 排第2
        assertThat(restored.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b");
        assertThat(restored.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2);
    }

    @Test
    void 模式与系数约束_未选模式_已计时_重复选择_非法系数() {
        // 未选 HANDICAP 的标准赛事写系数 -> 409
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", null, 1, "req-a"));
        assertThatThrownBy(() -> raceService.upsertHandicapFactor(RACE, "a",
                new UpsertHandicapFactorRequest(1, 3, 2, "req-fa")))
                .isInstanceOf(ConflictException.class);

        // 已有完赛耗时后选择模式 -> 409（首次计时前才可选择）
        raceService.reviseTime(RACE, new ReviseTimeRequest("a", 1000L, 2, "req-t"));
        assertThatThrownBy(() -> raceService.selectScoringMode(RACE,
                new SelectScoringModeRequest("HANDICAP", 3, "req-mode")))
                .isInstanceOf(ConflictException.class);

        // 未知模式 -> 400；选择 STANDARD -> 400
        assertThatThrownBy(() -> raceService.selectScoringMode(RACE,
                new SelectScoringModeRequest("BOGUS", 3, "req-mode-x")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> raceService.selectScoringMode(RACE,
                new SelectScoringModeRequest("STANDARD", 3, "req-mode-s")))
                .isInstanceOf(BadRequestException.class);

        // 选择后重复选择 -> 409
        raceService.createRace(new CreateRaceRequest("race-dup", "req-create2"));
        raceService.selectScoringMode("race-dup",
                new SelectScoringModeRequest("HANDICAP", 1, "req-mode-d"));
        assertThatThrownBy(() -> raceService.selectScoringMode("race-dup",
                new SelectScoringModeRequest("HANDICAP", 2, "req-mode-d2")))
                .isInstanceOf(ConflictException.class);

        // 非法系数 -> 400
        raceService.registerRunner("race-dup",
                new RegisterRunnerRequest("b", null, 2, "req-b"));
        assertThatThrownBy(() -> raceService.upsertHandicapFactor("race-dup", "b",
                new UpsertHandicapFactorRequest(0, 3, 3, "req-f0")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> raceService.upsertHandicapFactor("race-dup", "b",
                new UpsertHandicapFactorRequest(1, 1001, 3, "req-f1001")))
                .isInstanceOf(BadRequestException.class);
        // 版本冲突 -> 409
        assertThatThrownBy(() -> raceService.upsertHandicapFactor("race-dup", "b",
                new UpsertHandicapFactorRequest(1, 3, 99, "req-fv")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 普通赛事计算与响应不变() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        RaceResponse created = (RaceResponse) raceService.createRace(
                new CreateRaceRequest("race-std", "req-create2")).body();
        assertThat(created.scoringMode()).isEqualTo(ScoringMode.STANDARD);
        raceService.registerRunner("race-std",
                new RegisterRunnerRequest("a", 1000L, 1, "req-a"));

        StandingResponse standing = raceService.getResults("race-std");
        assertThat(standing.scoringMode()).isEqualTo(ScoringMode.STANDARD);
        assertThat(standing.entries().get(0).totalTimeMs()).isEqualTo(1000L);
        assertThat(standing.entries().get(0).factorP()).isNull();
        assertThat(standing.entries().get(0).factorQ()).isNull();
        assertThat(standing.entries().get(0).scoreNumerator()).isNull();
        assertThat(standing.entries().get(0).scoreDenominator()).isNull();
    }
}
