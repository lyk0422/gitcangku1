package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReplaceChipRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.WithdrawalResponse;
import com.example.starter.race.api.WithdrawRunnerRequest;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.persistence.SnapshotRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 选手退赛的 H2 数据库测试：退赛主流程、芯片释放、后续写禁止、
 * 成绩与封榜快照中的 WITHDRAWN 状态、requestId 幂等。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class WithdrawalServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-withdraw";
    private static final long T = FixedClockTestConfig.FIXED_INSTANT.toEpochMilli();

    @Autowired
    private RaceService raceService;

    @Autowired
    private ObjectMapper objectMapper;

    private void createRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
    }

    private void register(String bib, Long finishTimeMs, String chipCode, int version, String reqId) {
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, finishTimeMs, chipCode, version, reqId));
    }

    @Test
    void 退赛后释放芯片并禁止后续分段与更换() {
        createRace();
        register("a", 10_000L, "CHIP-A", 1, "req-a");
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(List.of(
                new ConfigureCheckpointsRequest.CheckpointDefinition("cp1", 1)), 2, "req-cfg"));

        ServiceResult result =
                raceService.withdrawRunner(RACE, "a", new WithdrawRunnerRequest(3, "op-1", "req-w1"));

        assertThat(result.status()).isEqualTo(200);
        WithdrawalResponse body = (WithdrawalResponse) result.body();
        assertThat(body.bib()).isEqualTo("a");
        assertThat(body.withdrawnAt()).isEqualTo(T);
        assertThat(body.version()).isEqualTo(4);
        // 芯片释放：全局生效绑定移除、绑定区间关闭
        assertThat(repository.findChipActive("CHIP-A")).isEmpty();
        assertThat(repository.findOpenChipPeriod(RACE, "a")).isEmpty();
        assertThat(repository.findRunner(RACE, "a").orElseThrow().withdrawnAt()).isEqualTo(T);

        // 退赛后禁止提交分段与更换芯片
        assertThatThrownBy(() -> raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t1", "cp1", 1000L, 4, "req-t1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已退赛");
        assertThatThrownBy(() -> raceService.replaceChip(RACE, "a", new ReplaceChipRequest(
                "repl-1", "CHIP-A", "CHIP-B", T, "ref-1", "op-1", 4, "req-r1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已退赛");
        // 重复退赛：409
        assertThatThrownBy(() ->
                raceService.withdrawRunner(RACE, "a", new WithdrawRunnerRequest(4, "op-1", "req-w2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已退赛");
        // 成绩状态为 WITHDRAWN 且不排名
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries().getFirst().status()).isEqualTo(EntryStatus.WITHDRAWN);
        assertThat(standing.entries().getFirst().rank()).isNull();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
    }

    @Test
    void 退赛释放的芯片可绑定给其他选手() {
        createRace();
        register("a", 10_000L, "CHIP-A", 1, "req-a");
        raceService.withdrawRunner(RACE, "a", new WithdrawRunnerRequest(2, "op-1", "req-w1"));

        register("b", 20_000L, "CHIP-A", 3, "req-b");

        assertThat(repository.findChipActive("CHIP-A")).isPresent();
        assertThat(repository.findChipActive("CHIP-A").orElseThrow().bib()).isEqualTo("b");
    }

    @Test
    void 退赛状态进入封榜快照且不随后续操作改写() {
        createRace();
        register("a", 10_000L, null, 1, "req-a");
        register("b", 20_000L, null, 2, "req-b");
        raceService.withdrawRunner(RACE, "a", new WithdrawRunnerRequest(3, "op-1", "req-w1"));
        raceService.sealRace(RACE, new SealRaceRequest(4, "req-seal"));

        SnapshotRow snapshot = repository.findSnapshot(RACE).orElseThrow();
        assertThat(snapshot.entries())
                .filteredOn(entry -> entry.bib().equals("a"))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.status()).isEqualTo(EntryStatus.WITHDRAWN);
                    assertThat(entry.rank()).isNull();
                });
        assertThat(snapshot.entries())
                .filteredOn(entry -> entry.bib().equals("b"))
                .singleElement()
                .extracting(entry -> entry.status())
                .isEqualTo(EntryStatus.RANKED);
        // 封榜后快照只读
        assertThat(raceService.getSnapshot(RACE).entries())
                .filteredOn(entry -> entry.bib().equals("a"))
                .singleElement()
                .extracting(entry -> entry.status())
                .isEqualTo(EntryStatus.WITHDRAWN);
    }

    @Test
    void 退赛requestId幂等_同参重放_异参409() {
        createRace();
        register("a", 10_000L, null, 1, "req-a");

        ServiceResult first =
                raceService.withdrawRunner(RACE, "a", new WithdrawRunnerRequest(2, "op-1", "req-w1"));
        assertThat(first.status()).isEqualTo(200);
        ServiceResult replayed =
                raceService.withdrawRunner(RACE, "a", new WithdrawRunnerRequest(2, "op-1", "req-w1"));
        assertThat(replayed.status()).isEqualTo(200);
        assertThat(replayed.body()).isEqualTo(objectMapper.valueToTree(first.body()));
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);

        // 同 requestId 异参（不同操作者）：409
        assertThatThrownBy(() ->
                raceService.withdrawRunner(RACE, "a", new WithdrawRunnerRequest(2, "op-2", "req-w1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");
    }

    @Test
    void 取消资格与退赛并存时取消资格优先展示() {
        createRace();
        register("a", 10_000L, null, 1, "req-a");
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "pen-1", "a", "DISQUALIFY", null, 2, "req-pen"));
        raceService.withdrawRunner(RACE, "a", new WithdrawRunnerRequest(3, "op-1", "req-w1"));

        assertThat(raceService.getResults(RACE).entries().getFirst().status())
                .isEqualTo(EntryStatus.DISQUALIFIED);
    }
}