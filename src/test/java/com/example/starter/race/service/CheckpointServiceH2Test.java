package com.example.starter.race.service;

import com.example.starter.race.api.CheckpointPassResponse;
import com.example.starter.race.api.CheckpointTimingResponse;
import com.example.starter.race.api.CheckpointsConfigResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.RaceRow;
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
 * 分段计时能力的 H2 数据库测试：乱序合法写入、相邻约束422、漏点排名、
 * 封榜固化分段明细与缺失项、封榜竞争、requestId 与 timingId 双重幂等。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class CheckpointServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-cp";

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

    private CheckpointsConfigResponse configure(int expectedVersion, String reqId,
                                                String... codeThenPosition) {
        java.util.List<ConfigureCheckpointsRequest.CheckpointDefinition> defs = new java.util.ArrayList<>();
        for (int i = 0; i < codeThenPosition.length; i += 2) {
            defs.add(new ConfigureCheckpointsRequest.CheckpointDefinition(
                    codeThenPosition[i], Integer.parseInt(codeThenPosition[i + 1])));
        }
        ServiceResult result = raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(defs, expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(201);
        return (CheckpointsConfigResponse) result.body();
    }

    private CheckpointTimingResponse submit(
            String bib, String timingId, String code, long elapsed,
            int expectedVersion, String reqId) {
        ServiceResult result = raceService.submitTiming(RACE, bib,
                new SubmitTimingRequest(timingId, code, elapsed, expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(201);
        return (CheckpointTimingResponse) result.body();
    }

    @Test
    void 配置检查点后版本加一且顺序返回不可再改() {
        createRace();
        // v2
        registerRunner("a", 10_000L, 1, "req-a");
        CheckpointsConfigResponse config = configure(2, "req-cfg", "cp1", "1", "cp3", "3", "cp2", "2");

        assertThat(config.version()).isEqualTo(3);
        assertThat(config.checkpoints()).extracting(c -> c.position() + ":" + c.checkpointCode())
                .containsExactly("1:cp1", "2:cp2", "3:cp3");

        // 重复配置 -> 409
        assertThatThrownBy(() -> configure(3, "req-cfg-again", "cp9", "1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不可修改");
        assertThat(raceService.getResults(RACE).version()).isEqualTo(3);
    }

    @Test
    void 检查点配置参数校验失败分支() {
        createRace();
        // 空列表
        assertThatThrownBy(() -> raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(List.of(), 1, "req-empty")))
                .isInstanceOf(Exception.class);
        // position 不连续
        assertThatThrownBy(() -> raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(List.of(
                        new ConfigureCheckpointsRequest.CheckpointDefinition("a", 1),
                        new ConfigureCheckpointsRequest.CheckpointDefinition("b", 3)),
                        1, "req-gap")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("连续");
        // 代码重复
        assertThatThrownBy(() -> raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(List.of(
                        new ConfigureCheckpointsRequest.CheckpointDefinition("dup", 1),
                        new ConfigureCheckpointsRequest.CheckpointDefinition("dup", 2)),
                        1, "req-dup")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("重复");
        // 失败不推进版本
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void 乱序合法写入最终按顺序严格递增() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");   // v2
        configure(2, "req-cfg", "c1", "1", "c2", "2", "c3", "3"); // v3

        // 先提交 c3、再 c1、再 c2，乱序但每次都满足相邻约束
        submit("a", "t-3", "c3", 900L, 3, "req-t3");   // v4
        submit("a", "t-1", "c1", 100L, 4, "req-t1");   // v5
        submit("a", "t-2", "c2", 500L, 5, "req-t2");   // v6

        RunnerTimingResponse timing = raceService.getRunnerTimings(RACE, "a");
        assertThat(timing.checkpoints()).extracting(CheckpointPassResponse::checkpointCode)
                .containsExactly("c1", "c2", "c3");
        assertThat(timing.checkpoints()).extracting(CheckpointPassResponse::elapsedMillis)
                .containsExactly(100L, 500L, 900L);
        assertThat(timing.version()).isEqualTo(6);
        // 只读查询不改版本
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);
    }

    @Test
    void 违反相邻约束与完赛耗时约束返回422且不写入不推进版本() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");
        configure(2, "req-cfg", "c1", "1", "c2", "2", "c3", "3");

        submit("a", "t-3", "c3", 900L, 3, "req-t3"); // v4

        // c2=950 不严格小于后序 c3=900 -> 422
        assertThatThrownBy(() -> submit("a", "t-2-bad", "c2", 950L, 4, "req-t2-bad"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("后一检查点");

        // c1=900 不严格小于后序 c3=900（相等）-> 422
        assertThatThrownBy(() -> submit("a", "t-1-bad", "c1", 900L, 4, "req-t1-eq"))
                .isInstanceOf(UnprocessableEntityException.class);

        // 分段耗时必须小于完赛耗时：c2=10000 不小于 finish=10000 -> 422
        assertThatThrownBy(() -> submit("a", "t-2-finish", "c2", 10_000L, 4, "req-t2-fin"))
                .isInstanceOf(UnprocessableEntityException.class);

        // 同一检查点重复 -> 422
        assertThatThrownBy(() -> submit("a", "t-3-again", "c3", 800L, 4, "req-t3-again"))
                .isInstanceOf(UnprocessableEntityException.class);

        // 没有任何成功写入：仍只有 c3 一条，版本保持 4
        assertThat(raceService.getRunnerTimings(RACE, "a").checkpoints())
                .filteredOn(p -> p.elapsedMillis() != null).hasSize(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);

        // 合法的 c2 随后可成功（相邻：>无 c1 约束(空)，<c3=900）
        submit("a", "t-2", "c2", 500L, 4, "req-t2-ok");
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
    }

    @Test
    void 无完赛耗时选手可提交途中分段且状态为UNTIMED() {
        createRace();
        registerRunner("u", null, 1, "req-u"); // UNTIMED
        configure(2, "req-cfg", "c1", "1");
        // 途中计时（尚无完赛耗时，可能随后 DNF）允许提交
        submit("u", "t-x", "c1", 100L, 3, "req-tx");
        RunnerTimingResponse timing = raceService.getRunnerTimings(RACE, "u");
        assertThat(timing.checkpoints()).hasSize(1);
        assertThat(timing.checkpoints().getFirst().elapsedMillis()).isEqualTo(100L);
        assertThat(timing.finishTimeMs()).isNull();
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries().getFirst().status()).isEqualTo(EntryStatus.UNTIMED);
        assertThat(standing.entries().getFirst().rank()).isNull();
    }

    @Test
    void 计时修订导致小于已有分段返回422() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");
        configure(2, "req-cfg", "c1", "1");
        submit("a", "t-1", "c1", 5_000L, 3, "req-t1"); // v4
        // 把完赛耗时修订为 4000（小于分段 5000）-> 422
        assertThatThrownBy(() -> raceService.reviseTime(RACE,
                new com.example.starter.race.api.ReviseTimeRequest(
                        "a", 4_000L, 4, "req-rev-bad")))
                .isInstanceOf(UnprocessableEntityException.class);
        // 合法修订（仍严格大于 5000）成功
        raceService.reviseTime(RACE,
                new com.example.starter.race.api.ReviseTimeRequest(
                        "a", 6_000L, 4, "req-rev-ok"));
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
    }

    @Test
    void 漏点选手不参与排名全覆盖后恢复() {
        createRace();
        registerRunner("fast", 2_000L, 1, "req-fast");
        registerRunner("slow", 9_000L, 2, "req-slow"); // v3
        configure(3, "req-cfg", "c1", "1", "c2", "2"); // v4

        // fast 两个检查点都漏；slow 全覆盖
        submit("slow", "s-1", "c1", 100L, 4, "req-s1"); // v5
        submit("slow", "s-2", "c2", 200L, 5, "req-s2"); // v6

        StandingResponse standing = raceService.getResults(RACE);
        // fast 虽完赛耗时更短，但漏点不排名；slow 第1
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("slow", "fast");
        assertThat(standing.entries().get(0).status()).isEqualTo(EntryStatus.RANKED);
        assertThat(standing.entries().get(0).rank()).isEqualTo(1);
        assertThat(standing.entries().get(1).status())
                .isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(standing.entries().get(1).missingCheckpoints()).containsExactly("c1", "c2");
        assertThat(standing.entries().get(1).checkpointCount()).isEqualTo(2);

        // fast 补齐后恢复排名：fast(2000) 反超 slow(9000)
        submit("fast", "f-1", "c1", 100L, 6, "req-f1"); // v7
        StandingResponse stillMissing = raceService.getResults(RACE);
        assertThat(stillMissing.entries().get(1).status())
                .isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        submit("fast", "f-2", "c2", 200L, 7, "req-f2"); // v8
        StandingResponse recovered = raceService.getResults(RACE);
        assertThat(recovered.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("fast", "slow");
        assertThat(recovered.entries()).extracting(ResultEntryResponse::status)
                .containsOnly(EntryStatus.RANKED);
        assertThat(recovered.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2);
    }

    @Test
    void 缺失检查点汇总顺序稳定() {
        createRace();
        registerRunner("b", 9_000L, 1, "req-b");
        registerRunner("a", 8_000L, 2, "req-a"); // v3
        configure(3, "req-cfg", "c1", "1", "c2", "2"); // v4
        submit("a", "a-1", "c1", 100L, 4, "req-a1"); // v5

        MissingCheckpointsResponse summary = raceService.getMissingCheckpoints(RACE);
        assertThat(summary.checkpointCount()).isEqualTo(2);
        assertThat(summary.runners()).extracting(r -> r.bib())
                .containsExactly("a", "b");
        assertThat(summary.runners().get(0).missingCheckpoints()).containsExactly("c2");
        assertThat(summary.runners().get(1).missingCheckpoints()).containsExactly("c1", "c2");
        // 只读不改版本
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
    }

    @Test
    void 封榜固化分段明细与缺失项且封榜后禁止新增分段() {
        createRace();
        registerRunner("a", 9_000L, 1, "req-a");
        registerRunner("b", 8_000L, 2, "req-b"); // v3
        configure(3, "req-cfg", "c1", "1", "c2", "2"); // v4
        submit("a", "a-1", "c1", 100L, 4, "req-a1"); // v5  a 漏 c2

        ServiceResult sealed = raceService.sealRace(RACE,
                new SealRaceRequest(5, "req-seal")); // v6
        assertThat(sealed.status()).isEqualTo(200);
        StandingResponse standing = (StandingResponse) sealed.body();
        assertThat(standing.status()).isEqualTo(RaceStatus.SEALED);

        // a 漏点不排名，b 无任何分段也漏点不排名
        assertThat(standing.entries()).extracting(ResultEntryResponse::status)
                .containsOnly(EntryStatus.MISSING_CHECKPOINT);

        // 单选手分段查询走封榜快照：a 有 c1、缺 c2
        RunnerTimingResponse aTiming = raceService.getRunnerTimings(RACE, "a");
        assertThat(aTiming.checkpoints()).extracting(CheckpointPassResponse::checkpointCode)
                .containsExactly("c1", "c2");
        assertThat(aTiming.checkpoints().get(0).elapsedMillis()).isEqualTo(100L);
        assertThat(aTiming.checkpoints().get(0).timingId()).isEqualTo("a-1");
        assertThat(aTiming.checkpoints().get(1).elapsedMillis()).isNull();
        assertThat(aTiming.checkpoints().get(1).timingId()).isNull();

        // b 两个检查点全部固化为缺失
        RunnerTimingResponse bTiming = raceService.getRunnerTimings(RACE, "b");
        assertThat(bTiming.checkpoints()).extracting(CheckpointPassResponse::elapsedMillis)
                .containsExactly(null, null);

        // 缺失汇总走快照
        MissingCheckpointsResponse summary = raceService.getMissingCheckpoints(RACE);
        assertThat(summary.runners().get(0).bib()).isEqualTo("a");
        assertThat(summary.runners().get(0).missingCheckpoints()).containsExactly("c2");
        assertThat(summary.runners().get(1).missingCheckpoints()).containsExactly("c1", "c2");

        // 封榜后新增分段 409
        assertThatThrownBy(() -> submit("a", "a-2", "c2", 200L, 6, "req-a2-late"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
    }

    @Test
    void 未配置检查点的赛事封榜与排名沿用旧规则() {
        createRace();
        registerRunner("a", 1_000L, 1, "req-a");
        registerRunner("b", 1_000L, 2, "req-b"); // v3
        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(3, "req-seal"));
        StandingResponse standing = (StandingResponse) sealed.body();
        assertThat(standing.entries()).extracting(ResultEntryResponse::status)
                .containsOnly(EntryStatus.RANKED);
        assertThat(standing.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 1);
        // 未配置检查点：单选手查询分段为空列表，不报错
        assertThat(raceService.getRunnerTimings(RACE, "a").checkpoints()).isEmpty();
        assertThat(raceService.getMissingCheckpoints(RACE).checkpointCount()).isZero();
    }

    @Test
    void requestId同参重放原结果异参409且timingId双重幂等() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");
        configure(2, "req-cfg", "c1", "1", "c2", "2");

        SubmitTimingRequest req =
                new SubmitTimingRequest("t-same", "c1", 100L, 3, "req-submit");
        ServiceResult first = raceService.submitTiming(RACE, "a", req);
        ServiceResult replay = raceService.submitTiming(RACE, "a", req);
        assertThat(replay.status()).isEqualTo(first.status());
        // requestId 重放返回的是原响应体 JSON（JsonNode），按 JSON 文本比对
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        // 重放不重复推进版本（仍为 v4）
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);

        // 同 requestId 异参 -> 409
        assertThatThrownBy(() -> raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-other", "c1", 200L, 3, "req-submit")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");

        // 同 timingId 异参（即使换新 requestId）-> 409
        assertThatThrownBy(() -> raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-same", "c2", 200L, 4, "req-submit-other")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("timingId");

        // 同 timingId 同参但新 requestId：作为分段重放返回原结果，不新增记录不推进版本
        ServiceResult timingReplay = raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-same", "c1", 100L, 999, "req-replay-timing"));
        assertThat(timingReplay.status()).isEqualTo(201);
        assertThat(((CheckpointTimingResponse) timingReplay.body()).elapsedMillis()).isEqualTo(100L);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
        assertThat(repository.findAllTimings(RACE)).hasSize(1);
    }

    @Test
    void 业务失败不占用requestId键() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");
        configure(2, "req-cfg", "c1", "1");

        // 错误版本提交，业务失败回滚不占键
        assertThatThrownBy(() -> submit("a", "t-1", "c1", 100L, 999, "req-t1"))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findIdempotency("req-t1")).isEmpty();
        // 同 requestId 用正确版本重试成功
        ServiceResult retry = raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-1", "c1", 100L, 3, "req-t1"));
        assertThat(retry.status()).isEqualTo(201);
    }

    @Test
    void 版本冲突串行化分段与封榜竞争() throws Exception {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");
        configure(2, "req-cfg", "c1", "1"); // 当前版本 3

        int threads = 8;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger timingOk = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger sealOk = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger conflicts = new java.util.concurrent.atomic.AtomicInteger();
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        if (index % 2 == 0) {
                            raceService.submitTiming(RACE, "a", new SubmitTimingRequest(
                                    "t-conc-" + index, "c1", 100L + index, 3, "req-conc-" + index));
                            timingOk.incrementAndGet();
                        } else {
                            raceService.sealRace(RACE, new SealRaceRequest(3, "req-seal-" + index));
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

        // 同一版本只允许一个写成功（要么一条分段，要么封榜）
        assertThat(timingOk.get() + sealOk.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(4);
        if (sealOk.get() == 1) {
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            assertThat(repository.findAllTimings(RACE)).isEmpty();
        } else {
            assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
            assertThat(repository.findAllTimings(RACE)).hasSize(1);
        }
    }
}
