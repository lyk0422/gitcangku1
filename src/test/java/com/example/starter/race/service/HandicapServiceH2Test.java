package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.HandicapFactorResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SetHandicapFactorRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ScoringMode;
import com.example.starter.race.api.RaceResponse;
import com.example.starter.race.persistence.HandicapFactorRow;
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
 * HANDICAP 让分模式的 H2 数据库测试：合成场景、版本竞争、封榜冻结与幂等边界。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class HandicapServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-handicap";

    @Autowired
    private RaceService raceService;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 建 HANDICAP 赛事并登记 A=1000、B=999（版本推进到3）。 */
    private void seedHandicapRace() {
        RaceResponse race = (RaceResponse) raceService.createRace(
                new CreateRaceRequest(RACE, "req-create", "HANDICAP")).body();
        assertThat(race.scoringMode()).isEqualTo(ScoringMode.HANDICAP);
        raceService.registerRunner(RACE, new RegisterRunnerRequest("A", 1000L, 1, "req-reg-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("B", 999L, 2, "req-reg-b"));
    }

    @Test
    void 合成场景_展示均333但B先A后_并列后跳号() {
        seedHandicapRace();
        // v4/v5: 登记系数 1/3（等比例写法 2/6 与 1/3 视为同参，存储为约分值）
        ServiceResult factorA = raceService.setHandicapFactor(RACE, "A",
                new SetHandicapFactorRequest(2, 6, 3, "req-f-a"));
        assertThat(factorA.status()).isEqualTo(200);
        assertThat(((HandicapFactorResponse) factorA.body()).p()).isEqualTo(1);
        assertThat(((HandicapFactorResponse) factorA.body()).q()).isEqualTo(3);
        assertThat(((HandicapFactorResponse) factorA.body()).version()).isEqualTo(4);
        raceService.setHandicapFactor(RACE, "B", new SetHandicapFactorRequest(1, 3, 4, "req-f-b"));

        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("B", "A");
        assertThat(standing.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2);
        // 展示均 333 毫秒，但展示相同不代表并列
        assertThat(standing.entries()).extracting(ResultEntryResponse::effectiveDisplayMs)
                .containsExactly(333L, 333L);
        assertThat(standing.entries().get(0).effectiveNum()).isEqualTo(333L);
        assertThat(standing.entries().get(0).effectiveDen()).isEqualTo(1L);
        assertThat(standing.entries().get(1).effectiveNum()).isEqualTo(1000L);
        assertThat(standing.entries().get(1).effectiveDen()).isEqualTo(3L);
        assertThat(standing.entries().get(1).factorP()).isEqualTo(1);
        assertThat(standing.entries().get(1).factorQ()).isEqualTo(3);

        // v6: A 也改为 999 -> 两者并列 1、1
        raceService.reviseTime(RACE, new ReviseTimeRequest("A", 999L, 5, "req-rev-a"));
        // v7: 登记 C=1000 并配系数 -> 名次 3
        raceService.registerRunner(RACE, new RegisterRunnerRequest("C", 1000L, 6, "req-reg-c"));
        raceService.setHandicapFactor(RACE, "C", new SetHandicapFactorRequest(1, 3, 7, "req-f-c"));

        StandingResponse tied = raceService.getResults(RACE);
        assertThat(tied.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("A", "B", "C");
        assertThat(tied.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 1, 3);
    }

    @Test
    void 缺系数不排名_取消资格与漏计时优先() {
        seedHandicapRace();
        // 只给 A 配系数：B 已计时但缺系数 -> MISSING_FACTOR
        raceService.setHandicapFactor(RACE, "A", new SetHandicapFactorRequest(1, 2, 3, "req-f-a"));
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("A", "B");
        assertThat(standing.entries().get(1).status()).isEqualTo(EntryStatus.MISSING_FACTOR);
        assertThat(standing.entries().get(1).rank()).isNull();

        // B 被取消资格：DISQUALIFIED 优先于缺系数
        raceService.addPenalty(RACE, new com.example.starter.race.api.AddPenaltyRequest(
                "p-dq", "B", "DISQUALIFY", null, 4, "req-dq-b"));
        StandingResponse dq = raceService.getResults(RACE);
        assertThat(dq.entries().get(1).status()).isEqualTo(EntryStatus.DISQUALIFIED);
    }

    @Test
    void 系数修改携带版本且仅OPEN可改_封榜后409() {
        seedHandicapRace();
        // 版本不匹配 -> 409，且不占 requestId
        assertThatThrownBy(() -> raceService.setHandicapFactor(RACE, "A",
                new SetHandicapFactorRequest(1, 2, 99, "req-f-a")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("版本冲突");
        assertThat(repository.findIdempotency("req-f-a")).isEmpty();

        raceService.setHandicapFactor(RACE, "A", new SetHandicapFactorRequest(1, 2, 3, "req-f-a"));
        // 修改系数同样走版本竞争：旧版本 3 已失效
        assertThatThrownBy(() -> raceService.setHandicapFactor(RACE, "A",
                new SetHandicapFactorRequest(1, 4, 3, "req-f-a2")))
                .isInstanceOf(ConflictException.class);
        raceService.setHandicapFactor(RACE, "A", new SetHandicapFactorRequest(1, 4, 4, "req-f-a2"));
        HandicapFactorRow factor = repository.findHandicapFactors(RACE).getFirst();
        assertThat(factor.p()).isEqualTo(1);
        assertThat(factor.q()).isEqualTo(4);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);

        // 封榜后修改系数 -> 409，快照保持只读
        raceService.sealRace(RACE, new SealRaceRequest(5, "req-seal"));
        assertThatThrownBy(() -> raceService.setHandicapFactor(RACE, "A",
                new SetHandicapFactorRequest(1, 8, 6, "req-f-a3")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
        assertThat(repository.findHandicapFactors(RACE).getFirst().q()).isEqualTo(4);
    }

    @Test
    void 封榜冻结系数精确分数展示值与名次() {
        seedHandicapRace();
        raceService.setHandicapFactor(RACE, "A", new SetHandicapFactorRequest(1, 3, 3, "req-f-a"));
        raceService.setHandicapFactor(RACE, "B", new SetHandicapFactorRequest(1, 3, 4, "req-f-b"));
        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(5, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);

        StandingResponse snapshot = raceService.getSnapshot(RACE);
        assertThat(snapshot.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(snapshot.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("B", "A");
        assertThat(snapshot.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2);
        assertThat(snapshot.entries().get(0).factorP()).isEqualTo(1);
        assertThat(snapshot.entries().get(0).factorQ()).isEqualTo(3);
        assertThat(snapshot.entries().get(0).effectiveNum()).isEqualTo(333L);
        assertThat(snapshot.entries().get(0).effectiveDen()).isEqualTo(1L);
        assertThat(snapshot.entries().get(0).effectiveDisplayMs()).isEqualTo(333L);
        assertThat(snapshot.entries().get(1).effectiveNum()).isEqualTo(1000L);
        assertThat(snapshot.entries().get(1).effectiveDen()).isEqualTo(3L);
        // 封榜后即时成绩即只读快照
        assertThat(raceService.getResults(RACE)).usingRecursiveComparison().isEqualTo(snapshot);
    }

    @Test
    void 幂等_同键同参重放首次响应_等比例系数视为同参() {
        seedHandicapRace();
        SetHandicapFactorRequest first = new SetHandicapFactorRequest(1, 3, 3, "req-f-a");
        ServiceResult created = raceService.setHandicapFactor(RACE, "A", first);
        // 同键同参重放：返回首次完整响应，版本不再推进
        ServiceResult replay = raceService.setHandicapFactor(RACE, "A", first);
        assertThat(replay.status()).isEqualTo(created.status());
        assertThat(json(replay.body())).isEqualTo(json(created.body()));
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);

        // 等比例 2/6 与 1/3 约分后同参：同键重放首次响应（保留首次字段）
        ServiceResult proportional = raceService.setHandicapFactor(RACE, "A",
                new SetHandicapFactorRequest(2, 6, 3, "req-f-a"));
        assertThat(json(proportional.body())).isEqualTo(json(created.body()));
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
        assertThat(repository.findHandicapFactors(RACE)).hasSize(1);
    }

    @Test
    void 幂等_同键异参409_失败不占键() {
        seedHandicapRace();
        raceService.setHandicapFactor(RACE, "A", new SetHandicapFactorRequest(1, 3, 3, "req-f-a"));
        // 同键异参（系数不同）-> 409
        assertThatThrownBy(() -> raceService.setHandicapFactor(RACE, "A",
                new SetHandicapFactorRequest(1, 4, 3, "req-f-a")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");
        // 同键异参（期望版本不同）-> 409
        assertThatThrownBy(() -> raceService.setHandicapFactor(RACE, "A",
                new SetHandicapFactorRequest(1, 3, 9, "req-f-a")))
                .isInstanceOf(ConflictException.class);

        // 失败不占键：错误版本失败后，同 requestId 以正确参数重试成功
        assertThatThrownBy(() -> raceService.setHandicapFactor(RACE, "B",
                new SetHandicapFactorRequest(1, 2, 99, "req-f-b")))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findIdempotency("req-f-b")).isEmpty();
        ServiceResult retry = raceService.setHandicapFactor(RACE, "B",
                new SetHandicapFactorRequest(1, 2, 4, "req-f-b"));
        assertThat(retry.status()).isEqualTo(200);
    }

    @Test
    void 非HANDICAP赛事与越界系数返回400() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("A", 1000L, 1, "req-reg-a"));

        // STANDARD 赛事不能登记系数
        assertThatThrownBy(() -> raceService.setHandicapFactor(RACE, "A",
                new SetHandicapFactorRequest(1, 2, 2, "req-f-a")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("HANDICAP");
        // 越界系数
        assertThatThrownBy(() -> raceService.setHandicapFactor(RACE, "A",
                new SetHandicapFactorRequest(0, 2, 2, "req-f-a0")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> raceService.setHandicapFactor(RACE, "A",
                new SetHandicapFactorRequest(1, 1001, 2, "req-f-a1")))
                .isInstanceOf(BadRequestException.class);
        // 选手不存在
        assertThatThrownBy(() -> raceService.setHandicapFactor(RACE, "ghost",
                new SetHandicapFactorRequest(1, 2, 2, "req-f-g")))
                .isInstanceOf(NotFoundException.class);
        // 全部失败均不改变版本与数据
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(2);
        assertThat(repository.findHandicapFactors(RACE)).isEmpty();
    }

    @Test
    void 普通赛事计算不受让分模式影响() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("A", 1000L, 1, "req-reg-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("B", 999L, 2, "req-reg-b"));

        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("B", "A");
        assertThat(standing.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2);
        assertThat(standing.entries().get(0).factorP()).isNull();
        assertThat(standing.entries().get(0).effectiveNum()).isNull();
        assertThat(standing.entries().get(0).effectiveDisplayMs()).isNull();
        List<String> statuses = standing.entries().stream()
                .map(entry -> entry.status().name()).toList();
        assertThat(statuses).containsExactly("RANKED", "RANKED");
    }
}
