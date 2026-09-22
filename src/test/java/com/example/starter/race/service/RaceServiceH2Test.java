package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.PenaltyResponse;
import com.example.starter.race.api.RaceResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.RunnerResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.IdempotencyRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RaceServiceImpl} 的 H2 数据库测试：主流程、失败分支与幂等边界。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class RaceServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-1";

    @Autowired
    private RaceService raceService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void 完整主流程_登记加时取消撤销到封榜快照() {
        RaceResponse race = (RaceResponse) raceService.createRace(
                new CreateRaceRequest(RACE, "req-create")).body();
        assertThat(race.version()).isEqualTo(1);
        assertThat(race.status()).isEqualTo(RaceStatus.OPEN);

        // v2: 登记选手 a，无成绩
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", null, 1, "req-reg-a"));
        // v3: 登记选手 b=1000ms
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", 1000L, 2, "req-reg-b"));
        // v4: 登记选手 c=1000ms
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("c", 1000L, 3, "req-reg-c"));

        StandingResponse beforeRevise = raceService.getResults(RACE);
        assertThat(beforeRevise.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("b", "c", "a");
        assertThat(beforeRevise.entries()).extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.RANKED, EntryStatus.UNTIMED);

        // v5: a 计时修订 1000ms，三人并列
        raceService.reviseTime(RACE, new ReviseTimeRequest("a", 1000L, 4, "req-time-a"));
        StandingResponse tied = raceService.getResults(RACE);
        assertThat(tied.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b", "c");
        assertThat(tied.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 1, 1);
        assertThat(tied.version()).isEqualTo(5);

        // v6: b 加时 500ms -> 1500ms 排第3
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "p-1", "b", "ADD_TIME", 500L, 5, "req-pen-b"));
        StandingResponse afterPenalty = raceService.getResults(RACE);
        assertThat(afterPenalty.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "c", "b");
        assertThat(afterPenalty.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 1, 3);
        assertThat(afterPenalty.entries().get(2).penaltyMs()).isEqualTo(500L);
        assertThat(afterPenalty.entries().get(2).totalTimeMs()).isEqualTo(1500L);

        // v7: c 取消资格 -> b 升为第2，c 不排名
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "p-2", "c", "DISQUALIFY", null, 6, "req-dq-c"));
        StandingResponse dq = raceService.getResults(RACE);
        assertThat(dq.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b", "c");
        assertThat(dq.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2, null);
        assertThat(dq.entries().get(2).status()).isEqualTo(EntryStatus.DISQUALIFIED);

        // v8: 撤销 c 的取消资格 -> c 恢复且与 a 并列第1
        raceService.revokePenalty(RACE, "p-2",
                new RevokePenaltyRequest(7, "req-revoke-dq"));
        StandingResponse restored = raceService.getResults(RACE);
        assertThat(restored.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "c", "b");
        assertThat(restored.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 1, 3);

        // v9: 封榜
        ServiceResult sealed = raceService.sealRace(RACE,
                new SealRaceRequest(8, "req-seal"));
        StandingResponse sealedStanding = (StandingResponse) sealed.body();
        assertThat(sealed.status()).isEqualTo(200);
        assertThat(sealedStanding.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(sealedStanding.sealedAt()).isEqualTo(FixedClockTestConfig.FIXED_INSTANT.toEpochMilli());
        assertThat(sealedStanding.version()).isEqualTo(9);

        StandingResponse snapshot = raceService.getSnapshot(RACE);
        assertThat(snapshot).usingRecursiveComparison().isEqualTo(sealedStanding);
    }

    @Test
    void 封榜后写操作全部409() {
        seedSealedRace();

        assertThatThrownBy(() -> raceService.registerRunner(RACE,
                new RegisterRunnerRequest("z", 1L, 2, "req-reg-z")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");

        assertThatThrownBy(() -> raceService.reviseTime(RACE,
                new ReviseTimeRequest("a", 2L, 2, "req-rev")))
                .isInstanceOf(ConflictException.class);

        assertThatThrownBy(() -> raceService.addPenalty(RACE,
                new AddPenaltyRequest("p", "a", "ADD_TIME", 1L, 2, "req-add")))
                .isInstanceOf(ConflictException.class);

        assertThatThrownBy(() -> raceService.sealRace(RACE,
                new SealRaceRequest(2, "req-seal-again")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 封榜后即时成绩返回只读快照且不再随后续变化() {
        seedSealedRace();
        StandingResponse snapshot = raceService.getResults(RACE);

        // 直接在库内写入“封榜后才出现”的选手：只读快照与查询结果都不应受影响
        jdbcTemplate.update(
                "INSERT INTO runner (race_id, bib, finish_time_ms, created_at, updated_at) "
                        + "VALUES (?, 'late', 1, 100, 100)", RACE);

        StandingResponse results = raceService.getResults(RACE);
        StandingResponse snapshotAgain = raceService.getSnapshot(RACE);
        assertThat(results).usingRecursiveComparison().isEqualTo(snapshot);
        assertThat(snapshotAgain).usingRecursiveComparison().isEqualTo(snapshot);
        assertThat(results.entries()).extracting(ResultEntryResponse::bib)
                .doesNotContain("late");
    }

    @Test
    void 版本不匹配返回409且版本不变() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 100L, 1, "req-a"));

        assertThatThrownBy(() -> raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", 100L, 1, "req-b-stale")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("版本冲突");

        assertThat(raceService.getResults(RACE).version()).isEqualTo(2);
    }

    @Test
    void 不存在与重复与参数错误分别返回对应异常() {
        assertThatThrownBy(() -> raceService.getResults("missing"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> raceService.getSnapshot(RACE))
                .isInstanceOf(NotFoundException.class);

        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 100L, 1, "req-a"));

        // 参赛号重复
        assertThatThrownBy(() -> raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 100L, 2, "req-a-dup")))
                .isInstanceOf(ConflictException.class);

        // 赛事ID重复
        assertThatThrownBy(() -> raceService.createRace(
                new CreateRaceRequest(RACE, "req-create-dup")))
                .isInstanceOf(ConflictException.class);

        // 修订不存在的选手
        assertThatThrownBy(() -> raceService.reviseTime(RACE,
                new ReviseTimeRequest("ghost", 100L, 2, "req-ghost")))
                .isInstanceOf(NotFoundException.class);

        // 加时越界
        assertThatThrownBy(() -> raceService.addPenalty(RACE,
                new AddPenaltyRequest("p1", "a", "ADD_TIME", 3_600_001L, 2, "req-p1")))
                .isInstanceOf(BadRequestException.class);
        // 加时缺时长
        assertThatThrownBy(() -> raceService.addPenalty(RACE,
                new AddPenaltyRequest("p2", "a", "ADD_TIME", null, 2, "req-p2")))
                .isInstanceOf(BadRequestException.class);
        // 取消资格带时长
        assertThatThrownBy(() -> raceService.addPenalty(RACE,
                new AddPenaltyRequest("p3", "a", "DISQUALIFY", 1L, 2, "req-p3")))
                .isInstanceOf(BadRequestException.class);
        // 未知类型
        assertThatThrownBy(() -> raceService.addPenalty(RACE,
                new AddPenaltyRequest("p4", "a", "UNKNOWN", null, 2, "req-p4")))
                .isInstanceOf(BadRequestException.class);
        // 处罚不存在 / 重复ID
        assertThatThrownBy(() -> raceService.revokePenalty(RACE, "nope",
                new RevokePenaltyRequest(2, "req-rev-nope")))
                .isInstanceOf(NotFoundException.class);
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p5", "a", "ADD_TIME", 10L, 2, "req-p5"));
        assertThatThrownBy(() -> raceService.addPenalty(RACE,
                new AddPenaltyRequest("p5", "a", "ADD_TIME", 10L, 3, "req-p5-dup")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 重复撤销返回409且历史处罚不被覆盖() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 100L, 1, "req-a"));
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p1", "a", "ADD_TIME", 10L, 2, "req-p1"));
        raceService.revokePenalty(RACE, "p1",
                new RevokePenaltyRequest(3, "req-rev1"));

        assertThatThrownBy(() -> raceService.revokePenalty(RACE, "p1",
                new RevokePenaltyRequest(4, "req-rev2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已撤销");
    }

    @Test
    void 同键同参重放原成功结果且不重复变更() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        RegisterRunnerRequest register =
                new RegisterRunnerRequest("a", 100L, 1, "req-reg-a");

        ServiceResult first = raceService.registerRunner(RACE, register);
        ServiceResult replay = raceService.registerRunner(RACE, register);

        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(first.status());
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        assertThat(raceService.getResults(RACE).version()).isEqualTo(2);
        assertThat(raceService.getResults(RACE).entries()).hasSize(1);

        // 封榜重放返回首次的封榜响应
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p1", "a", "ADD_TIME", 10L, 2, "req-p1"));
        SealRaceRequest seal = new SealRaceRequest(3, "req-seal");
        ServiceResult sealed1 = raceService.sealRace(RACE, seal);
        ServiceResult sealed2 = raceService.sealRace(RACE, seal);
        assertThat(sealed2.status()).isEqualTo(sealed1.status());
        assertThat(json(sealed2.body())).isEqualTo(json(sealed1.body()));
    }

    @Test
    void 同键异参返回409() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 100L, 1, "req-reg-a"));

        assertThatThrownBy(() -> raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", 100L, 2, "req-reg-a")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");
        // expectedVersion 不同也算异参
        assertThatThrownBy(() -> raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 100L, 9, "req-reg-a")))
                .isInstanceOf(ConflictException.class);
        // b 未被登记
        assertThat(raceService.getResults(RACE).entries()).hasSize(1);
    }

    @Test
    void 业务失败不占用requestId键() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));

        // 首次以错误版本请求，业务失败并随事务回滚，不占用 requestId
        assertThatThrownBy(() -> raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 100L, 999, "req-a")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("版本冲突");
        assertThat(repository.findIdempotency("req-a")).isEmpty();

        // 同一 requestId 以正确版本与参数重试应成功
        ServiceResult retry = raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 100L, 1, "req-a"));
        assertThat(retry.status()).isEqualTo(201);

        // 幂等表中只留下最终成功记录，无进行中占位
        Optional<IdempotencyRow> row = repository.findIdempotency("req-a");
        assertThat(row).isPresent();
        assertThat(row.get().responseStatus()).isEqualTo(201);
    }

    private void seedSealedRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 100L, 1, "req-a"));
        raceService.sealRace(RACE, new SealRaceRequest(2, "req-seal"));
    }
}
