package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.LapPassResponse;
import com.example.starter.race.api.RaceResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.RunnerLapsResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitLapRequest;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.RaceType;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 绕圈赛事圈次提交与封榜的 H2 数据库测试：主流程、完整性核验与失败分支。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class LapServiceH2Test extends AbstractRaceH2Test {

    protected static final String RACE = "race-loop";

    @Autowired
    protected RaceService raceService;

    protected int version() {
        return repository.findRace(RACE).orElseThrow().version();
    }

    protected void createLoopRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "LOOP", 2, "req-create"));
    }

    protected void register(String bib) {
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, null, version(), "req-reg-" + bib));
    }

    protected LapPassResponse submit(String bib, int lap, String point, long elapsed) {
        String id = "lp-" + bib + "-" + lap + "-" + point;
        ServiceResult result = raceService.submitLap(RACE, bib,
                new SubmitLapRequest(id, lap, point, elapsed, "timer-1", version(), "req-" + id));
        assertThat(result.status()).isEqualTo(201);
        return (LapPassResponse) result.body();
    }

    /** 合成数据：两圈齐备，末圈FINISH=120000。 */
    protected void submitFullLaps(String bib) {
        submit(bib, 1, "A", 40_000);
        submit(bib, 1, "B", 50_000);
        submit(bib, 1, "FINISH", 60_000);
        submit(bib, 2, "A", 70_000);
        submit(bib, 2, "B", 80_000);
        submit(bib, 2, "FINISH", 120_000);
    }

    @Test
    void 绕圈主流程_乱序收件两圈齐备末圈FINISH加处罚封榜() {
        createLoopRace();
        register("a");
        // 乱序收件：第2圈A先于第1圈各点到达，允许但标记不完整
        submit("a", 2, "A", 70_000);
        assertThat(raceService.getRunnerLaps(RACE, "a").complete()).isFalse();
        submit("a", 1, "A", 40_000);
        submit("a", 1, "B", 50_000);
        submit("a", 1, "FINISH", 60_000);
        submit("a", 2, "B", 80_000);
        submit("a", 2, "FINISH", 120_000);

        RunnerLapsResponse laps = raceService.getRunnerLaps(RACE, "a");
        assertThat(laps.complete()).isTrue();
        assertThat(laps.totalLaps()).isEqualTo(2);
        assertThat(laps.laps()).hasSize(6);

        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "p-1", "a", "ADD_TIME", 5_000L, version(), "req-pen"));

        StandingResponse live = raceService.getResults(RACE);
        assertThat(live.entries().getFirst().status()).isEqualTo(EntryStatus.RANKED);
        assertThat(live.entries().getFirst().finishTimeMs()).isEqualTo(120_000L);
        assertThat(live.entries().getFirst().totalTimeMs()).isEqualTo(125_000L);

        ServiceResult sealed = raceService.sealRace(RACE,
                new SealRaceRequest(version(), "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
        StandingResponse standing = (StandingResponse) sealed.body();
        assertThat(standing.status()).isEqualTo(RaceStatus.SEALED);
        ResultEntryResponse entry = standing.entries().getFirst();
        assertThat(entry.rank()).isEqualTo(1);
        assertThat(entry.finishTimeMs()).isEqualTo(120_000L);
        assertThat(entry.penaltyMs()).isEqualTo(5_000L);
        assertThat(entry.totalTimeMs()).isEqualTo(125_000L);

        assertThat(raceService.getSnapshot(RACE)).usingRecursiveComparison().isEqualTo(standing);
    }

    @Test
    void 删去第一圈B则封榜失败422且补齐后可封榜() {
        createLoopRace();
        register("a");
        submit("a", 1, "A", 40_000);
        submit("a", 1, "FINISH", 60_000);
        submit("a", 2, "A", 70_000);
        submit("a", 2, "B", 80_000);
        submit("a", 2, "FINISH", 120_000);

        assertThat(raceService.getRunnerLaps(RACE, "a").complete()).isFalse();
        StandingResponse live = raceService.getResults(RACE);
        assertThat(live.entries().getFirst().status()).isEqualTo(EntryStatus.LAP_INCOMPLETE);
        assertThat(live.entries().getFirst().missingCheckpoints()).containsExactly("1:B");

        int versionBefore = version();
        assertThatThrownBy(() -> raceService.sealRace(RACE,
                new SealRaceRequest(version(), "req-seal")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("不完整");
        // 完整性失败不封榜：版本不推进、赛事仍OPEN、requestId不被占用
        assertThat(version()).isEqualTo(versionBefore);
        assertThat(repository.findRace(RACE).orElseThrow().status()).isEqualTo(RaceStatus.OPEN);
        assertThat(repository.findIdempotency("req-seal")).isEmpty();

        submit("a", 1, "B", 50_000);
        ServiceResult sealed = raceService.sealRace(RACE,
                new SealRaceRequest(version(), "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
    }

    @Test
    void 圈号越界400逆序与重复点409负耗时422且失败不占键() {
        createLoopRace();
        register("a");
        submit("a", 1, "A", 10_000);

        assertThatThrownBy(() -> raceService.submitLap(RACE, "a",
                new SubmitLapRequest("lp-x1", 3, "A", 1L, "op", version(), "req-x1")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("圈号越界");
        assertThatThrownBy(() -> raceService.submitLap(RACE, "a",
                new SubmitLapRequest("lp-x2", 0, "A", 1L, "op", version(), "req-x2")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> raceService.submitLap(RACE, "a",
                new SubmitLapRequest("lp-x3", 1, "C", 1L, "op", version(), "req-x3")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> raceService.submitLap(RACE, "a",
                new SubmitLapRequest("lp-x4", 1, "B", -1L, "op", version(), "req-x4")))
                .isInstanceOf(UnprocessableEntityException.class);
        // 同圈 B 不大于 A：逆序422
        assertThatThrownBy(() -> raceService.submitLap(RACE, "a",
                new SubmitLapRequest("lp-x5", 1, "B", 10_000L, "op", version(), "req-x5")))
                .isInstanceOf(UnprocessableEntityException.class);
        // 每点只保留一条当前记录：重复提交须改用更正
        assertThatThrownBy(() -> raceService.submitLap(RACE, "a",
                new SubmitLapRequest("lp-x6", 1, "A", 11_000L, "op", version(), "req-x6")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("更正");
        // 失败不占键：req-x6 以合法参数重试成功
        ServiceResult retry = raceService.submitLap(RACE, "a",
                new SubmitLapRequest("lp-x6", 1, "B", 20_000L, "op", version(), "req-x6"));
        assertThat(retry.status()).isEqualTo(201);
    }

    @Test
    void 绕圈赛事创建校验与标准赛事拒绝圈次操作() {
        assertThatThrownBy(() -> raceService.createRace(
                new CreateRaceRequest("r-1", "LOOP", 1, "req-r1")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> raceService.createRace(
                new CreateRaceRequest("r-2", "LOOP", 11, "req-r2")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> raceService.createRace(
                new CreateRaceRequest("r-3", "LOOP", null, "req-r3")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> raceService.createRace(
                new CreateRaceRequest("r-4", "STANDARD", 2, "req-r4")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> raceService.createRace(
                new CreateRaceRequest("r-5", "UNKNOWN", null, "req-r5")))
                .isInstanceOf(BadRequestException.class);

        RaceResponse loop = (RaceResponse) raceService.createRace(
                new CreateRaceRequest(RACE, "LOOP", 2, "req-create")).body();
        assertThat(loop.raceType()).isEqualTo(RaceType.LOOP);
        assertThat(loop.totalLaps()).isEqualTo(2);

        raceService.createRace(new CreateRaceRequest("race-std", "req-std"));
        raceService.registerRunner("race-std",
                new RegisterRunnerRequest("a", null, 1, "req-std-a"));
        assertThatThrownBy(() -> raceService.submitLap("race-std", "a",
                new SubmitLapRequest("lp-s1", 1, "A", 1L, "op", 2, "req-s1")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("非绕圈赛事");
        assertThatThrownBy(() -> raceService.getRunnerLaps("race-std", "a"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void 绕圈赛事保留未计时与取消资格语义且不阻止封榜() {
        createLoopRace();
        register("a");
        register("b");
        register("c");
        submitFullLaps("a");
        submitFullLaps("c");
        // b 无任何圈次记录 -> UNTIMED；c 取消资格 -> DISQUALIFIED
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "p-dq", "c", "DISQUALIFY", null, version(), "req-dq"));

        StandingResponse live = raceService.getResults(RACE);
        assertThat(live.entries()).extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.UNTIMED,
                        EntryStatus.DISQUALIFIED);

        ServiceResult sealed = raceService.sealRace(RACE,
                new SealRaceRequest(version(), "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
    }
}