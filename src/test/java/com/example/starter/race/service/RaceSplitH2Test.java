package com.example.starter.race.service;

import com.example.starter.race.api.CheckpointConfigResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RecordSplitRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.RunnerSplitsResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SplitDetailResponse;
import com.example.starter.race.api.SplitTimeResponse;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分段计时与漏点判定的 H2 数据库测试：检查点配置、乱序写入、相邻约束、
 * timingId/requestId 双重幂等、漏点排名、封榜固化与只读查询。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class RaceSplitH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-split";

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

    /** 建赛（v1）并配置 cp1..cp3（v2）。 */
    private void createRaceWithCheckpoints(String raceId) {
        raceService.createRace(new CreateRaceRequest(raceId, "req-create-" + raceId));
        raceService.configureCheckpoints(raceId, new ConfigureCheckpointsRequest(
                List.of("cp1", "cp2", "cp3"), 1, "req-cp-" + raceId));
    }

    @Test
    void 配置检查点成功且版本加一顺序连续() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));

        ServiceResult result = raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(List.of("cp1", "cp2", "cp3"), 1, "req-cp"));

        assertThat(result.status()).isEqualTo(201);
        CheckpointConfigResponse body = (CheckpointConfigResponse) result.body();
        assertThat(body.version()).isEqualTo(2);
        assertThat(body.checkpoints()).extracting("checkpointCode")
                .containsExactly("cp1", "cp2", "cp3");
        assertThat(body.checkpoints()).extracting("seq")
                .containsExactly(1, 2, 3);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(2);
        assertThat(repository.findCheckpoints(RACE)).hasSize(3);
    }

    @Test
    void 配置检查点失败分支_重复配置_数量越界_编码重复_版本冲突() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(List.of("cp1"), 1, "req-cp"));

        // 已配置后不可修改
        assertThatThrownBy(() -> raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(List.of("cpA"), 2, "req-cp-again")))
                .isInstanceOf(ConflictException.class);
        // 版本冲突
        assertThatThrownBy(() -> raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(List.of("cpA"), 99, "req-cp-ver")))
                .isInstanceOf(ConflictException.class);

        raceService.createRace(new CreateRaceRequest("race-cfg2", "req-create-2"));
        // 数量为0或超过20
        assertThatThrownBy(() -> raceService.configureCheckpoints("race-cfg2",
                new ConfigureCheckpointsRequest(List.of(), 1, "req-cp-empty")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> raceService.configureCheckpoints("race-cfg2",
                new ConfigureCheckpointsRequest(
                        IntStream.rangeClosed(1, 21).mapToObj(i -> "cp" + i).toList(),
                        1, "req-cp-toomany")))
                .isInstanceOf(BadRequestException.class);
        // 编码重复或空白
        assertThatThrownBy(() -> raceService.configureCheckpoints("race-cfg2",
                new ConfigureCheckpointsRequest(List.of("cp1", "cp1"), 1, "req-cp-dup")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> raceService.configureCheckpoints("race-cfg2",
                new ConfigureCheckpointsRequest(List.of("cp1", " "), 1, "req-cp-blank")))
                .isInstanceOf(BadRequestException.class);
        // 全部失败均不推进版本、不写入检查点
        assertThat(repository.findRace("race-cfg2").orElseThrow().version()).isEqualTo(1);
        assertThat(repository.findCheckpoints("race-cfg2")).isEmpty();
    }

    @Test
    void 分段记录允许乱序到达且按检查点顺序严格递增() {
        createRaceWithCheckpoints(RACE);
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 10000L, 2, "req-reg-a"));

        // 乱序提交：cp3 -> cp1 -> cp2，耗时按检查点顺序递增
        assertThat(raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp3", 9000L, 3, "t-3", "req-s3")).status())
                .isEqualTo(201);
        assertThat(raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp1", 1000L, 4, "t-1", "req-s1")).status())
                .isEqualTo(201);
        assertThat(raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp2", 5000L, 5, "t-2", "req-s2")).status())
                .isEqualTo(201);

        RunnerSplitsResponse splits = raceService.getRunnerSplits(RACE, "a");
        assertThat(splits.splits()).extracting(SplitDetailResponse::checkpointCode)
                .containsExactly("cp1", "cp2", "cp3");
        assertThat(splits.splits()).extracting(SplitDetailResponse::elapsedMillis)
                .containsExactly(1000L, 5000L, 9000L);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);
    }

    @Test
    void 违反相邻约束返回422且不写入数据不推进版本() {
        createRaceWithCheckpoints(RACE);
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 10000L, 2, "req-reg-a"));
        raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp2", 5000L, 3, "t-2", "req-s2"));

        // 前邻约束：cp1 耗时不得大于等于 cp2
        assertThatThrownBy(() -> raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp1", 6000L, 4, "t-1a", "req-s1a")))
                .isInstanceOf(UnprocessableException.class);
        // 严格递增：相等也违反
        assertThatThrownBy(() -> raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp1", 5000L, 4, "t-1b", "req-s1b")))
                .isInstanceOf(UnprocessableException.class);
        // 后邻约束：cp3 耗时不得小于等于 cp2
        assertThatThrownBy(() -> raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp3", 4000L, 4, "t-3a", "req-s3a")))
                .isInstanceOf(UnprocessableException.class);

        // 三次422均未写入、未推进版本
        assertThat(repository.findSplitsForRunner(RACE, "a")).hasSize(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
        // 422 失败不占 timingId 键：同一 timingId 修正参数后可成功
        assertThat(raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp1", 4999L, 4, "t-1a", "req-s1c")).status())
                .isEqualTo(201);
    }

    @Test
    void 分段耗时必须小于原始完赛耗时且在取值范围内() {
        createRaceWithCheckpoints(RACE);
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 10000L, 2, "req-reg-a"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", null, 3, "req-reg-b"));

        // 等于完赛耗时 -> 422
        assertThatThrownBy(() -> raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp1", 10000L, 4, "t-eq", "req-eq")))
                .isInstanceOf(UnprocessableException.class);
        // 超过完赛耗时 -> 422
        assertThatThrownBy(() -> raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp1", 20000L, 4, "t-gt", "req-gt")))
                .isInstanceOf(UnprocessableException.class);
        // 取值范围 1~86400000 -> 400
        assertThatThrownBy(() -> raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp1", 0L, 4, "t-zero", "req-zero")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp1", 86400001L, 4, "t-max", "req-max")))
                .isInstanceOf(BadRequestException.class);
        // 未完赛选手不受完赛耗时约束
        assertThat(raceService.recordSplit(RACE, "b",
                new RecordSplitRequest("cp1", 80000000L, 4, "t-b1", "req-b1")).status())
                .isEqualTo(201);
        assertThat(repository.findSplitsForRace(RACE)).hasSize(1);
    }

    @Test
    void 同选手同检查点最多一条且未知对象返回404() {
        createRaceWithCheckpoints(RACE);
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 10000L, 2, "req-reg-a"));
        raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp1", 1000L, 3, "t-1", "req-s1"));

        // 同选手同检查点重复（不同 timingId）-> 409
        assertThatThrownBy(() -> raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp1", 2000L, 4, "t-1x", "req-s1x")))
                .isInstanceOf(ConflictException.class);
        // 未知检查点编码 -> 404
        assertThatThrownBy(() -> raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cpX", 2000L, 4, "t-x", "req-x")))
                .isInstanceOf(NotFoundException.class);
        // 未知选手 -> 404
        assertThatThrownBy(() -> raceService.recordSplit(RACE, "ghost",
                new RecordSplitRequest("cp2", 2000L, 4, "t-g", "req-g")))
                .isInstanceOf(NotFoundException.class);
        // 未配置检查点的赛事 -> 404（检查点不存在）
        raceService.createRace(new CreateRaceRequest("race-nocp", "req-create-nc"));
        raceService.registerRunner("race-nocp",
                new RegisterRunnerRequest("a", 10000L, 1, "req-reg-nc"));
        assertThatThrownBy(() -> raceService.recordSplit("race-nocp", "a",
                new RecordSplitRequest("cp1", 1000L, 2, "t-nc", "req-nc")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void timingId同参重放原结果异参409() {
        createRaceWithCheckpoints(RACE);
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 10000L, 2, "req-reg-a"));

        ServiceResult first = raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp1", 1000L, 3, "t-1", "req-s1"));
        assertThat(first.status()).isEqualTo(201);
        SplitTimeResponse firstBody = (SplitTimeResponse) first.body();

        // 同参重放：即使 expectedVersion 已过期，仍返回原结果且不推进版本
        ServiceResult replayed = raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp1", 1000L, 3, "t-1", "req-s1-retry"));
        assertThat(replayed.status()).isEqualTo(201);
        SplitTimeResponse replayedBody = (SplitTimeResponse) replayed.body();
        assertThat(replayedBody).isEqualTo(firstBody);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
        assertThat(repository.findSplitsForRace(RACE)).hasSize(1);

        // 异参：不同耗时或不同检查点 -> 409
        assertThatThrownBy(() -> raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp1", 2000L, 4, "t-1", "req-s1-diff")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp2", 1000L, 4, "t-1", "req-s1-diff2")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void requestId幂等重放且失败不占键() {
        createRaceWithCheckpoints(RACE);
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 10000L, 2, "req-reg-a"));

        ServiceResult first = raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp2", 5000L, 3, "t-2", "req-s2"));
        // 同 requestId 同参 -> 重放原响应体（重放体为 JsonNode，按 JSON 内容比较）
        ServiceResult replayed = raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp2", 5000L, 3, "t-2", "req-s2"));
        assertThat(replayed.status()).isEqualTo(201);
        assertThat(json(replayed.body())).isEqualTo(json(first.body()));
        // 同 requestId 异参 -> 409
        assertThatThrownBy(() -> raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp2", 6000L, 4, "t-2x", "req-s2")))
                .isInstanceOf(ConflictException.class);
        // 业务失败（422）不占 requestId 键：修正参数后可复用同一 requestId
        assertThatThrownBy(() -> raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp1", 9000L, 4, "t-1", "req-s1")))
                .isInstanceOf(UnprocessableException.class);
        assertThat(raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp1", 1000L, 4, "t-1", "req-s1")).status())
                .isEqualTo(201);
        assertThat(repository.findSplitsForRace(RACE)).hasSize(2);
    }

    @Test
    void 漏点选手不参与排名覆盖全部检查点后恢复排名() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(List.of("cp1", "cp2"), 1, "req-cp"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 10000L, 2, "req-reg-a"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", 9000L, 3, "req-reg-b"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("c", null, 4, "req-reg-c"));

        // a、b 已完赛但无任何分段 -> MISSING_CHECKPOINT；c 无完赛 -> UNTIMED
        StandingResponse initial = raceService.getResults(RACE);
        assertThat(initial.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b", "c");
        assertThat(initial.entries()).extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.MISSING_CHECKPOINT,
                        EntryStatus.MISSING_CHECKPOINT, EntryStatus.UNTIMED);
        assertThat(initial.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(null, null, null);

        // a 覆盖全部检查点 -> 恢复 RANKED 第1
        raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp1", 1000L, 5, "t-a1", "req-a1"));
        raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp2", 2000L, 6, "t-a2", "req-a2"));
        StandingResponse covered = raceService.getResults(RACE);
        assertThat(covered.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b", "c");
        assertThat(covered.entries()).extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.RANKED,
                        EntryStatus.MISSING_CHECKPOINT, EntryStatus.UNTIMED);
        assertThat(covered.entries().get(0).rank()).isEqualTo(1);
        // 分段明细随榜单返回
        assertThat(covered.entries().get(0).splits())
                .extracting(SplitDetailResponse::elapsedMillis)
                .containsExactly(1000L, 2000L);
        assertThat(covered.entries().get(1).splits())
                .extracting(SplitDetailResponse::elapsedMillis)
                .containsExactly(null, null);

        // b 也覆盖后按总耗时排名：b(9000) 在 a(10000) 前
        raceService.recordSplit(RACE, "b",
                new RecordSplitRequest("cp1", 1000L, 7, "t-b1", "req-b1"));
        raceService.recordSplit(RACE, "b",
                new RecordSplitRequest("cp2", 2000L, 8, "t-b2", "req-b2"));
        StandingResponse ranked = raceService.getResults(RACE);
        assertThat(ranked.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("b", "a", "c");
        assertThat(ranked.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2, null);
    }

    @Test
    void 未配置检查点的赛事沿用原排名规则() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 10000L, 1, "req-reg-a"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", null, 2, "req-reg-b"));

        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.UNTIMED);
        assertThat(standing.entries().get(0).rank()).isEqualTo(1);
        // 未配置检查点时响应不携带 splits 字段
        assertThat(standing.entries().get(0).splits()).isNull();
        // 缺失汇总为空
        assertThat(raceService.getMissingCheckpoints(RACE).runners()).isEmpty();
    }

    @Test
    void 封榜固化分段明细与缺失检查点且封榜后禁止新增分段() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(List.of("cp1", "cp2"), 1, "req-cp"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 10000L, 2, "req-reg-a"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", 9000L, 3, "req-reg-b"));
        // a 只覆盖 cp1（漏 cp2），b 全覆盖
        raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp1", 1000L, 4, "t-a1", "req-a1"));
        raceService.recordSplit(RACE, "b",
                new RecordSplitRequest("cp1", 1000L, 5, "t-b1", "req-b1"));
        raceService.recordSplit(RACE, "b",
                new RecordSplitRequest("cp2", 2000L, 6, "t-b2", "req-b2"));

        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(7, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
        StandingResponse snapshot = (StandingResponse) sealed.body();
        assertThat(snapshot.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(snapshot.version()).isEqualTo(8);
        assertThat(snapshot.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("b", "a");
        assertThat(snapshot.entries()).extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.MISSING_CHECKPOINT);
        // 快照固化分段明细与缺失检查点
        assertThat(snapshot.entries().get(0).splits())
                .extracting(SplitDetailResponse::elapsedMillis)
                .containsExactly(1000L, 2000L);
        assertThat(snapshot.entries().get(1).splits())
                .extracting(SplitDetailResponse::elapsedMillis)
                .containsExactly(1000L, null);
        assertThat(repository.findSnapshotSplits(RACE)).hasSize(4);

        // 封榜后即时成绩与快照均来自固化数据
        assertThat(raceService.getResults(RACE).entries())
                .extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.MISSING_CHECKPOINT);
        assertThat(raceService.getSnapshot(RACE).entries().get(1).splits())
                .extracting(SplitDetailResponse::elapsedMillis)
                .containsExactly(1000L, null);

        // 封榜后禁止新增分段
        assertThatThrownBy(() -> raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp2", 5000L, 8, "t-a2", "req-a2")))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findSplitsForRace(RACE)).hasSize(3);
    }

    @Test
    void 单选手分段查询与缺失汇总顺序稳定且只读不改版本() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(List.of("cp1", "cp2", "cp3"), 1, "req-cp"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", 10000L, 2, "req-reg-b"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 9000L, 3, "req-reg-a"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("c", null, 4, "req-reg-c"));
        raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp2", 2000L, 5, "t-a2", "req-a2"));

        int versionBefore = repository.findRace(RACE).orElseThrow().version();

        // 单选手分段查询：按检查点顺序，缺失为 null
        RunnerSplitsResponse splitsA = raceService.getRunnerSplits(RACE, "a");
        assertThat(splitsA.splits()).extracting(SplitDetailResponse::checkpointCode)
                .containsExactly("cp1", "cp2", "cp3");
        assertThat(splitsA.splits()).extracting(SplitDetailResponse::elapsedMillis)
                .containsExactly(null, 2000L, null);

        // 缺失汇总：已完赛但未全覆盖的选手，按参赛号字典序，漏点按检查点顺序
        MissingCheckpointsResponse missing = raceService.getMissingCheckpoints(RACE);
        assertThat(missing.runners()).extracting("bib").containsExactly("a", "b");
        assertThat(missing.runners().get(0).missingCheckpoints())
                .containsExactly("cp1", "cp3");
        assertThat(missing.runners().get(1).missingCheckpoints())
                .containsExactly("cp1", "cp2", "cp3");

        // 只读查询不修改版本
        raceService.getResults(RACE);
        assertThat(repository.findRace(RACE).orElseThrow().version())
                .isEqualTo(versionBefore);

        // 未知赛事/选手 -> 404
        assertThatThrownBy(() -> raceService.getRunnerSplits("ghost-race", "a"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> raceService.getRunnerSplits(RACE, "ghost"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> raceService.getMissingCheckpoints("ghost-race"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 分段提交版本冲突返回409且不写入() {
        createRaceWithCheckpoints(RACE);
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 10000L, 2, "req-reg-a"));

        assertThatThrownBy(() -> raceService.recordSplit(RACE, "a",
                new RecordSplitRequest("cp1", 1000L, 99, "t-1", "req-s1")))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findSplitsForRace(RACE)).isEmpty();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
    }
}
