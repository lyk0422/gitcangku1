package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.CheckpointPassResponse;
import com.example.starter.race.api.CheckpointTimingResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.ConfigureNeutralLegsRequest;
import com.example.starter.race.api.CorrectTimingRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.NeutralLegsConfigResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.SnapshotNeutralLegRow;
import com.example.starter.race.persistence.SnapshotRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 中立赛段模式的 H2 数据库测试：区间固定与校验、净竞技用时排名、
 * 分段更正重算、封榜冻结、幂等与并发。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class NeutralLegServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-neutral";

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

    private void createRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
    }

    private void registerRunner(String bib, Long finishTimeMs, int expectedVersion, String reqId) {
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, finishTimeMs, expectedVersion, reqId));
    }

    private void configureCheckpoints(int expectedVersion, String reqId, String... codes) {
        List<ConfigureCheckpointsRequest.CheckpointDefinition> defs = new ArrayList<>();
        for (int i = 0; i < codes.length; i++) {
            defs.add(new ConfigureCheckpointsRequest.CheckpointDefinition(codes[i], i + 1));
        }
        ServiceResult result = raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(defs, expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(201);
    }

    private NeutralLegsConfigResponse configureLegs(
            int expectedVersion, String reqId, int... startThenEnd) {
        List<ConfigureNeutralLegsRequest.NeutralLegDefinition> defs = new ArrayList<>();
        for (int i = 0; i < startThenEnd.length; i += 2) {
            defs.add(new ConfigureNeutralLegsRequest.NeutralLegDefinition(
                    startThenEnd[i], startThenEnd[i + 1]));
        }
        ServiceResult result = raceService.configureNeutralLegs(RACE,
                new ConfigureNeutralLegsRequest(defs, expectedVersion, reqId, "tester"));
        assertThat(result.status()).isEqualTo(201);
        return (NeutralLegsConfigResponse) result.body();
    }

    private CheckpointTimingResponse submit(
            String bib, String timingId, String code, long elapsed,
            int expectedVersion, String reqId) {
        ServiceResult result = raceService.submitTiming(RACE, bib,
                new SubmitTimingRequest(timingId, code, elapsed, expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(201);
        return (CheckpointTimingResponse) result.body();
    }

    private CheckpointTimingResponse correct(
            String bib, String code, long elapsed, int expectedVersion, String reqId) {
        ServiceResult result = raceService.correctTiming(RACE,
                new CorrectTimingRequest(bib, code, elapsed, expectedVersion, reqId, "tester"));
        assertThat(result.status()).isEqualTo(200);
        return (CheckpointTimingResponse) result.body();
    }

    @Test
    void 配置中立区间成功且查询一致启用后不可再改() {
        createRace();
        registerRunner("a", 100L, 1, "req-a"); // v2
        configureCheckpoints(2, "req-cfg", "cp1", "cp2", "cp3"); // v3

        NeutralLegsConfigResponse config = configureLegs(3, "req-legs", 1, 3, 0, 1); // v4
        // 乱序提交按起点排序固化，相接合法
        assertThat(config.version()).isEqualTo(4);
        assertThat(config.legs()).extracting(l -> l.legIndex() + ":" + l.startPosition() + "->" + l.endPosition())
                .containsExactly("1:0->1", "2:1->3");

        // 只读查询一致且不推进版本
        NeutralLegsConfigResponse queried = raceService.getNeutralLegs(RACE);
        assertThat(queried.legs()).hasSize(2);
        assertThat(queried.version()).isEqualTo(4);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);

        // 重复配置 -> 409
        assertThatThrownBy(() -> configureLegs(4, "req-legs-again", 0, 2))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不再变更");
    }

    @Test
    void 配置校验失败分支且不推进版本() {
        createRace();
        // 未配置检查点 -> 400
        assertThatThrownBy(() -> configureLegs(1, "req-legs-nocp", 0, 1))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("必须先配置检查点");
        configureCheckpoints(1, "req-cfg", "cp1", "cp2"); // v2
        // 交叠 -> 400
        assertThatThrownBy(() -> configureLegs(2, "req-legs-ov", 0, 2, 1, 2))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("不得交叠");
        // 超过5个 -> 400
        assertThatThrownBy(() -> configureLegs(2, "req-legs-6",
                0, 1, 1, 2, 0, 1, 1, 2, 0, 1, 1, 2))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("1~5");
        // 终点越界 -> 400
        assertThatThrownBy(() -> configureLegs(2, "req-legs-oob", 0, 3))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("必经检查点");
        // 终点不大于起点 -> 400
        assertThatThrownBy(() -> configureLegs(2, "req-legs-rev", 1, 1))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("严格大于起点");
        // 全部失败不推进版本
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(2);
        assertThat(repository.findNeutralLegs(RACE)).isEmpty();
    }

    @Test
    void 已有分段记录后不能再启用中立模式() {
        createRace();
        registerRunner("a", 100L, 1, "req-a"); // v2
        configureCheckpoints(2, "req-cfg", "cp1"); // v3
        submit("a", "t-1", "cp1", 50L, 3, "req-t1"); // v4

        assertThatThrownBy(() -> configureLegs(4, "req-legs-late", 0, 1))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已存在分段记录");
        assertThat(repository.findNeutralLegs(RACE)).isEmpty();
    }

    @Test
    void 合成样例第一至第二点中立净50更正后净60() {
        createRace();
        registerRunner("a", 100L, 1, "req-a"); // v2
        configureCheckpoints(2, "req-cfg", "cp1", "cp2"); // v3
        configureLegs(3, "req-legs", 1, 2); // v4：第一至第二点中立
        submit("a", "t-1", "cp1", 20L, 4, "req-t1"); // v5
        submit("a", "t-2", "cp2", 70L, 5, "req-t2"); // v6

        StandingResponse standing = raceService.getResults(RACE);
        ResultEntryResponse a = standing.entries().get(0);
        assertThat(a.status()).isEqualTo(EntryStatus.RANKED);
        assertThat(a.rank()).isEqualTo(1);
        assertThat(a.finishTimeMs()).isEqualTo(100L);
        assertThat(a.totalTimeMs()).isEqualTo(100L);
        assertThat(a.neutralDeductionMs()).isEqualTo(50L);
        assertThat(a.netTimeMs()).isEqualTo(50L);

        // 第二点更正为 60 -> 扣时 40、净用时 60
        CheckpointTimingResponse corrected = correct("a", "cp2", 60L, 6, "req-fix"); // v7
        assertThat(corrected.elapsedMillis()).isEqualTo(60L);
        assertThat(corrected.timingId()).isEqualTo("t-2");
        StandingResponse after = raceService.getResults(RACE);
        ResultEntryResponse fixed = after.entries().get(0);
        assertThat(fixed.neutralDeductionMs()).isEqualTo(40L);
        assertThat(fixed.netTimeMs()).isEqualTo(60L);

        // 原累计时刻不改写：cp1 仍为 20，原始完赛耗时仍为 100
        RunnerTimingResponse timings = raceService.getRunnerTimings(RACE, "a");
        assertThat(timings.checkpoints()).extracting(CheckpointPassResponse::elapsedMillis)
                .containsExactly(20L, 60L);
        assertThat(fixed.finishTimeMs()).isEqualTo(100L);
    }

    @Test
    void 更正重算完整扣时与名次且原处罚不改写() {
        createRace();
        registerRunner("a", 100L, 1, "req-a"); // v2
        registerRunner("b", 95L, 2, "req-b"); // v3
        configureCheckpoints(3, "req-cfg", "cp1", "cp2"); // v4
        configureLegs(4, "req-legs", 1, 2); // v5
        submit("a", "t-a1", "cp1", 20L, 5, "req-ta1"); // v6
        submit("a", "t-a2", "cp2", 70L, 6, "req-ta2"); // v7  a 净 50
        submit("b", "t-b1", "cp1", 10L, 7, "req-tb1"); // v8
        submit("b", "t-b2", "cp2", 15L, 8, "req-tb2"); // v9  b 净 90
        // a 加时 5 -> 净 55，仍领先 b(90)
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-1", "a", "ADD_TIME", 5L, 9, "req-p1")); // v10

        StandingResponse before = raceService.getResults(RACE);
        assertThat(before.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b");
        assertThat(before.entries().get(0).netTimeMs()).isEqualTo(55L);
        assertThat(before.entries().get(1).netTimeMs()).isEqualTo(90L);

        // 更正 a 的 cp2 为 95：扣时 75，净 100-75+5=30
        correct("a", "cp2", 95L, 10, "req-fix-a"); // v11
        assertThat(raceService.getResults(RACE).entries().get(0).netTimeMs()).isEqualTo(30L);
        // 更正 b 的 cp2 为 90：扣时 80，净 95-80=15，b 反超
        correct("b", "cp2", 90L, 11, "req-fix-b"); // v12
        StandingResponse after = raceService.getResults(RACE);
        assertThat(after.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("b", "a");
        assertThat(after.entries().get(0).netTimeMs()).isEqualTo(15L);
        assertThat(after.entries().get(1).netTimeMs()).isEqualTo(30L);

        // 原处罚不改写：p-1 仍生效、金额不变并继续计入净值
        PenaltyRow penalty = repository.findPenalty("p-1").orElseThrow();
        assertThat(penalty.revoked()).isFalse();
        assertThat(penalty.amountMs()).isEqualTo(5L);
        assertThat(after.entries().get(1).penaltyMs()).isEqualTo(5L);
        // 未更正的分段与完赛耗时不改写
        assertThat(raceService.getRunnerTimings(RACE, "a").checkpoints())
                .extracting(CheckpointPassResponse::elapsedMillis)
                .containsExactly(20L, 95L);
        assertThat(after.entries().get(1).finishTimeMs()).isEqualTo(100L);
    }

    @Test
    void 更正校验失败分支且不推进版本() {
        createRace();
        registerRunner("a", 100L, 1, "req-a"); // v2
        registerRunner("c", 200L, 2, "req-c"); // v3（无分段记录）
        configureCheckpoints(3, "req-cfg", "cp1", "cp2", "cp3"); // v4
        configureLegs(4, "req-legs", 1, 2); // v5
        submit("a", "t-1", "cp1", 20L, 5, "req-t1"); // v6
        submit("a", "t-2", "cp2", 70L, 6, "req-t2"); // v7
        submit("a", "t-3", "cp3", 90L, 7, "req-t3"); // v8

        // 更正破坏严格递增：cp2 改为 10 不大于 cp1=20 -> 422
        assertThatThrownBy(() -> correct("a", "cp2", 10L, 8, "req-fix-low"))
                .isInstanceOf(UnprocessableEntityException.class);
        // 更正破坏后序约束：cp2 改为 95 不小于 cp3=90 -> 422
        assertThatThrownBy(() -> correct("a", "cp2", 95L, 8, "req-fix-high"))
                .isInstanceOf(UnprocessableEntityException.class);
        // 更正为不小于完赛耗时 -> 422
        assertThatThrownBy(() -> correct("a", "cp3", 100L, 8, "req-fix-fin"))
                .isInstanceOf(UnprocessableEntityException.class);
        // 检查点不存在 -> 404
        assertThatThrownBy(() -> raceService.correctTiming(RACE,
                new CorrectTimingRequest("a", "cp9", 50L, 8, "req-fix-nocp", "tester")))
                .isInstanceOf(NotFoundException.class);
        // 选手无该检查点分段记录 -> 404
        assertThatThrownBy(() -> raceService.correctTiming(RACE,
                new CorrectTimingRequest("c", "cp1", 50L, 8, "req-fix-notiming", "tester")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("分段记录不存在");
        // 选手不存在 -> 404
        assertThatThrownBy(() -> raceService.correctTiming(RACE,
                new CorrectTimingRequest("ghost", "cp1", 50L, 8, "req-fix-ghost", "tester")))
                .isInstanceOf(NotFoundException.class);

        // 全部失败不推进版本、不改写数据
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(8);
        assertThat(raceService.getRunnerTimings(RACE, "a").checkpoints())
                .extracting(CheckpointPassResponse::elapsedMillis)
                .containsExactly(20L, 70L, 90L);
    }

    @Test
    void 中立端点漏点不排名且原最终用时入口不能绕过端点检查() {
        createRace();
        // direct 直接登记完赛耗时（原最终用时入口），无任何分段
        registerRunner("direct", 80L, 1, "req-direct"); // v2
        registerRunner("full", 900L, 2, "req-full"); // v3
        configureCheckpoints(3, "req-cfg", "cp1", "cp2"); // v4
        configureLegs(4, "req-legs", 1, 2); // v5
        submit("full", "t-f1", "cp1", 100L, 5, "req-f1"); // v6
        submit("full", "t-f2", "cp2", 800L, 6, "req-f2"); // v7

        StandingResponse standing = raceService.getResults(RACE);
        // direct 虽有完赛耗时，但中立端点无当前有效计时：漏点不排名，不做估计扣除
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("full", "direct");
        ResultEntryResponse direct = standing.entries().get(1);
        assertThat(direct.status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(direct.neutralDeductionMs()).isNull();
        assertThat(direct.netTimeMs()).isNull();
        assertThat(standing.entries().get(0).netTimeMs()).isEqualTo(200L);
    }

    @Test
    void 未启用中立模式的普通赛事保留原排名() {
        createRace();
        registerRunner("a", 100L, 1, "req-a"); // v2
        registerRunner("b", 90L, 2, "req-b"); // v3
        configureCheckpoints(3, "req-cfg", "cp1", "cp2"); // v4
        submit("a", "t-a1", "cp1", 20L, 4, "req-ta1"); // v5
        submit("a", "t-a2", "cp2", 70L, 5, "req-ta2"); // v6
        submit("b", "t-b1", "cp1", 10L, 6, "req-tb1"); // v7
        submit("b", "t-b2", "cp2", 15L, 7, "req-tb2"); // v8

        StandingResponse standing = raceService.getResults(RACE);
        // 按原总耗时排名：b(90) 在 a(100) 前，无扣时与净值字段
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("b", "a");
        assertThat(standing.entries()).allSatisfy(entry -> {
            assertThat(entry.neutralDeductionMs()).isNull();
            assertThat(entry.netTimeMs()).isNull();
        });
        assertThat(raceService.getNeutralLegs(RACE).legs()).isEmpty();
    }

    @Test
    void 封榜冻结原点扣时区间各段差值与净值() {
        createRace();
        registerRunner("a", 100L, 1, "req-a"); // v2
        registerRunner("b", 95L, 2, "req-b"); // v3
        configureCheckpoints(3, "req-cfg", "cp1", "cp2"); // v4
        configureLegs(4, "req-legs", 1, 2); // v5
        submit("a", "t-a1", "cp1", 20L, 5, "req-ta1"); // v6
        submit("a", "t-a2", "cp2", 70L, 6, "req-ta2"); // v7
        submit("b", "t-b1", "cp1", 10L, 7, "req-tb1"); // v8
        submit("b", "t-b2", "cp2", 15L, 8, "req-tb2"); // v9

        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(9, "req-seal")); // v10
        assertThat(sealed.status()).isEqualTo(200);
        StandingResponse standing = (StandingResponse) sealed.body();
        assertThat(standing.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b");
        assertThat(standing.entries().get(0).neutralDeductionMs()).isEqualTo(50L);
        assertThat(standing.entries().get(0).netTimeMs()).isEqualTo(50L);
        assertThat(standing.entries().get(1).netTimeMs()).isEqualTo(90L);

        // 快照冻结：扣时区间、端点时刻与各段差值
        SnapshotRow snapshot = repository.findSnapshot(RACE).orElseThrow();
        assertThat(snapshot.neutralLegs()).hasSize(2);
        SnapshotNeutralLegRow aLeg = snapshot.neutralLegs().stream()
                .filter(leg -> leg.bib().equals("a")).findFirst().orElseThrow();
        assertThat(aLeg.legIndex()).isEqualTo(1);
        assertThat(aLeg.startPosition()).isEqualTo(1);
        assertThat(aLeg.endPosition()).isEqualTo(2);
        assertThat(aLeg.startElapsedMillis()).isEqualTo(20L);
        assertThat(aLeg.endElapsedMillis()).isEqualTo(70L);
        assertThat(aLeg.deductionMs()).isEqualTo(50L);
        SnapshotNeutralLegRow bLeg = snapshot.neutralLegs().stream()
                .filter(leg -> leg.bib().equals("b")).findFirst().orElseThrow();
        assertThat(bLeg.deductionMs()).isEqualTo(5L);

        // 封榜后更正与启用中立模式均 409
        assertThatThrownBy(() -> correct("a", "cp2", 60L, 10, "req-fix-late"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
        assertThatThrownBy(() -> configureLegs(10, "req-legs-late", 0, 1))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");

        // 封榜后成绩与快照均返回冻结净值
        assertThat(raceService.getResults(RACE).entries().get(0).netTimeMs()).isEqualTo(50L);
        assertThat(raceService.getSnapshot(RACE).entries().get(0).neutralDeductionMs())
                .isEqualTo(50L);
    }

    @Test
    void 配置与更正幂等同键重放异参409失败不占键() {
        createRace();
        registerRunner("a", 100L, 1, "req-a"); // v2
        configureCheckpoints(2, "req-cfg", "cp1", "cp2"); // v3

        ConfigureNeutralLegsRequest legsReq = new ConfigureNeutralLegsRequest(
                List.of(new ConfigureNeutralLegsRequest.NeutralLegDefinition(1, 2)),
                3, "req-legs", "tester");
        ServiceResult first = raceService.configureNeutralLegs(RACE, legsReq); // v4
        ServiceResult replay = raceService.configureNeutralLegs(RACE, legsReq);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        // 重放不推进版本、不重复写入
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
        assertThat(repository.findNeutralLegs(RACE)).hasSize(1);
        // 同键异参 -> 409
        assertThatThrownBy(() -> raceService.configureNeutralLegs(RACE,
                new ConfigureNeutralLegsRequest(
                        List.of(new ConfigureNeutralLegsRequest.NeutralLegDefinition(0, 2)),
                        3, "req-legs", "tester")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");
        // 失败不占键：非法区间失败后同键可换参重试
        assertThatThrownBy(() -> raceService.configureNeutralLegs(RACE,
                new ConfigureNeutralLegsRequest(
                        List.of(new ConfigureNeutralLegsRequest.NeutralLegDefinition(2, 2)),
                        4, "req-bad", "tester")))
                .isInstanceOf(BadRequestException.class);
        assertThat(repository.findIdempotency("req-bad")).isEmpty();

        submit("a", "t-1", "cp1", 20L, 4, "req-t1"); // v5
        submit("a", "t-2", "cp2", 70L, 5, "req-t2"); // v6
        CorrectTimingRequest fixReq =
                new CorrectTimingRequest("a", "cp2", 60L, 6, "req-fix", "tester");
        ServiceResult fixFirst = raceService.correctTiming(RACE, fixReq); // v7
        ServiceResult fixReplay = raceService.correctTiming(RACE, fixReq);
        assertThat(fixReplay.status()).isEqualTo(200);
        assertThat(json(fixReplay.body())).isEqualTo(json(fixFirst.body()));
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(7);
        // 更正同键异参 -> 409
        assertThatThrownBy(() -> raceService.correctTiming(RACE,
                new CorrectTimingRequest("a", "cp2", 65L, 6, "req-fix", "tester")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");
    }

    @Test
    void 旧键重放返回首次证据不被后来更正重新解释() {
        createRace();
        registerRunner("a", 100L, 1, "req-a"); // v2
        configureCheckpoints(2, "req-cfg", "cp1", "cp2"); // v3
        configureLegs(3, "req-legs", 1, 2); // v4
        submit("a", "t-1", "cp1", 20L, 4, "req-t1"); // v5
        SubmitTimingRequest original = new SubmitTimingRequest("t-2", "cp2", 70L, 5, "req-t2");
        ServiceResult firstEvidence = raceService.submitTiming(RACE, "a", original); // v6

        // 更正端点 cp2 为 60
        correct("a", "cp2", 60L, 6, "req-fix"); // v7

        // 旧键同参重放：返回首次响应（70），不按更正后的端点重新解释历史
        ServiceResult replayed = raceService.submitTiming(RACE, "a", original);
        assertThat(replayed.status()).isEqualTo(201);
        assertThat(json(replayed.body())).isEqualTo(json(firstEvidence.body()));
        assertThat(json(replayed.body())).contains("\"elapsedMillis\":70");
        // 当前数据仍是更正后的 60，重放不改写数据不推进版本
        assertThat(raceService.getRunnerTimings(RACE, "a").checkpoints().get(1).elapsedMillis())
                .isEqualTo(60L);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(7);
        assertThat(raceService.getResults(RACE).entries().get(0).netTimeMs()).isEqualTo(60L);
    }

    @Test
    void 并发配置中立区间同版本只有一个成功() throws Exception {
        createRace();
        configureCheckpoints(1, "req-cfg", "cp1", "cp2"); // v2

        int threads = 8;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger success = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger conflicts = new java.util.concurrent.atomic.AtomicInteger();
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        raceService.configureNeutralLegs(RACE, new ConfigureNeutralLegsRequest(
                                List.of(new ConfigureNeutralLegsRequest.NeutralLegDefinition(1, 2)),
                                2, "req-legs-" + index, "tester"));
                        success.incrementAndGet();
                    } catch (ConflictException ex) {
                        conflicts.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 同一版本只允许一个配置成功，区间只写入一份
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
        assertThat(repository.findNeutralLegs(RACE)).hasSize(1);
    }

    @Test
    void 并发更正与封榜竞争只有一个成功() throws Exception {
        createRace();
        registerRunner("a", 100L, 1, "req-a"); // v2
        configureCheckpoints(2, "req-cfg", "cp1", "cp2"); // v3
        configureLegs(3, "req-legs", 1, 2); // v4
        submit("a", "t-1", "cp1", 20L, 4, "req-t1"); // v5
        submit("a", "t-2", "cp2", 70L, 5, "req-t2"); // v6

        int threads = 8;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger correctOk = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger sealOk = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger conflicts = new java.util.concurrent.atomic.AtomicInteger();
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        if (index % 2 == 0) {
                            raceService.correctTiming(RACE, new CorrectTimingRequest(
                                    "a", "cp2", 60L, 6, "req-fix-" + index, "tester"));
                            correctOk.incrementAndGet();
                        } else {
                            raceService.sealRace(RACE, new SealRaceRequest(6, "req-seal-" + index));
                            sealOk.incrementAndGet();
                        }
                    } catch (ConflictException ex) {
                        conflicts.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 同一版本只允许一个写成功（要么一次更正，要么封榜）
        assertThat(correctOk.get() + sealOk.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(7);
        if (sealOk.get() == 1) {
            // 封榜胜出：快照冻结更正前的净值 50
            assertThat(repository.findRace(RACE).orElseThrow().status()).isEqualTo(RaceStatus.SEALED);
            assertThat(raceService.getSnapshot(RACE).entries().get(0).netTimeMs()).isEqualTo(50L);
        } else {
            // 更正胜出：净值重算为 60，赛事仍可写
            assertThat(repository.findRace(RACE).orElseThrow().status()).isEqualTo(RaceStatus.OPEN);
            assertThat(raceService.getResults(RACE).entries().get(0).netTimeMs()).isEqualTo(60L);
        }
    }
}
