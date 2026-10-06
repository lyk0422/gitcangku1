package com.example.starter.race.service;

import com.example.starter.race.api.AdjudicateRequest;
import com.example.starter.race.api.AdjudicationResponse;
import com.example.starter.race.api.CheckpointPassResponse;
import com.example.starter.race.api.CheckpointsConfigResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.ReviseSensorReadingRequest;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SensorReadingBasis;
import com.example.starter.race.api.SensorReadingResponse;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitSensorReadingRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.AdjudicationRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.SnapshotRow;
import com.example.starter.race.persistence.SnapshotSensorReadingRow;
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
 * 三源计时共识的 H2 数据库测试：采样、修订、显式裁定（中位值写回原分段计时）、
 * 旧裁定失效与排名排除、封榜冻结三读数依据、入口隔离、幂等重放与并发竞争。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class SensorQuorumH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-sensor";

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

    private CheckpointsConfigResponse configure(
            int expectedVersion, String reqId,
            List<ConfigureCheckpointsRequest.CheckpointDefinition> defs) {
        ServiceResult result = raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(defs, expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(201);
        return (CheckpointsConfigResponse) result.body();
    }

    private SensorReadingResponse submitReading(
            String bib, String code, String sensor, long elapsed,
            int expectedVersion, String reqId) {
        ServiceResult result = raceService.submitSensorReading(RACE, bib,
                new SubmitSensorReadingRequest(code, sensor, elapsed, expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(201);
        return (SensorReadingResponse) result.body();
    }

    private SensorReadingResponse reviseReading(
            String bib, String code, String sensor, long elapsed,
            int raceVersion, int sensorRevision, String reqId) {
        ServiceResult result = raceService.reviseSensorReading(RACE, bib,
                new ReviseSensorReadingRequest(
                        code, sensor, elapsed, raceVersion, sensorRevision, reqId));
        assertThat(result.status()).isEqualTo(200);
        return (SensorReadingResponse) result.body();
    }

    private AdjudicationResponse adjudicate(
            String bib, String adjudicationId, String code, int expectedVersion, String reqId) {
        ServiceResult result = raceService.adjudicate(RACE, bib,
                new AdjudicateRequest(adjudicationId, code, expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(201);
        return (AdjudicationResponse) result.body();
    }

    /** 准备：赛事 + 选手a(完赛10000) + 三源点c1 + 三条读数100/110/120，推进到 v6。 */
    private void setupRaceWithThreeReadings() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a"); // v2
        CheckpointsConfigResponse config = configure(2, "req-cfg", List.of(
                new ConfigureCheckpointsRequest.CheckpointDefinition("c1", 1, true))); // v3
        assertThat(config.checkpoints().get(0).threeSource()).isTrue();
        SensorReadingResponse r1 = submitReading("a", "c1", "s1", 100L, 3, "req-r1"); // v4
        assertThat(r1.revision()).isEqualTo(1);
        submitReading("a", "c1", "s2", 110L, 4, "req-r2"); // v5
        submitReading("a", "c1", "s3", 120L, 5, "req-r3"); // v6
    }

    @Test
    void 三源读数齐全且差值不超20毫秒时裁定中位值写回原分段计时() {
        setupRaceWithThreeReadings();

        AdjudicationResponse adjudication = adjudicate("a", "adj-1", "c1", 6, "req-adj1"); // v7
        // 合成100、110、120 -> 中位值110
        assertThat(adjudication.medianMillis()).isEqualTo(110L);
        assertThat(adjudication.valid()).isTrue();
        assertThat(adjudication.readings()).extracting(SensorReadingBasis::sensorId)
                .containsExactly("s1", "s2", "s3");
        assertThat(adjudication.readings()).extracting(SensorReadingBasis::revision)
                .containsExactly(1, 1, 1);
        assertThat(adjudication.readings()).extracting(SensorReadingBasis::elapsedMillis)
                .containsExactly(100L, 110L, 120L);

        // 中位值写入原分段计时，分段记录ID即裁定ID
        RunnerTimingResponse timing = raceService.getRunnerTimings(RACE, "a");
        CheckpointPassResponse c1 = timing.checkpoints().get(0);
        assertThat(c1.elapsedMillis()).isEqualTo(110L);
        assertThat(c1.timingId()).isEqualTo("adj-1");
        assertThat(c1.adjudicationId()).isEqualTo("adj-1");
        assertThat(repository.findTiming("adj-1")).isPresent();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(7);

        // 全覆盖后参与排名
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.RANKED);
    }

    @Test
    void 修订使旧裁定失效重新裁定超差422且不能凭旧中位值封榜() {
        setupRaceWithThreeReadings();
        adjudicate("a", "adj-1", "c1", 6, "req-adj1"); // v7，中位110

        // 把120修订为131：赛事版本与传感器修订号同时推进
        SensorReadingResponse revised =
                reviseReading("a", "c1", "s3", 131L, 7, 1, "req-rev1"); // v8
        assertThat(revised.revision()).isEqualTo(2);
        assertThat(revised.elapsedMillis()).isEqualTo(131L);

        // 旧裁定立即失效，历史行不覆写
        AdjudicationRow old = repository.findAdjudication("adj-1").orElseThrow();
        assertThat(old.valid()).isFalse();
        assertThat(old.medianMillis()).isEqualTo(110L);
        assertThat(old.invalidatedAt()).isNotNull();
        // 旧中位值从即时排名视图排除：分段计时行已删除，缺共识按漏点不排名
        assertThat(repository.findTiming("adj-1")).isEmpty();
        RunnerTimingResponse timing = raceService.getRunnerTimings(RACE, "a");
        assertThat(timing.checkpoints().get(0).elapsedMillis()).isNull();
        assertThat(timing.checkpoints().get(0).adjudicationId()).isNull();
        assertThat(raceService.getResults(RACE).entries())
                .extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.MISSING_CHECKPOINT);

        // 重新裁定：100/110/131 最大差31>20 -> 422整次不写
        assertThatThrownBy(() -> adjudicate("a", "adj-2", "c1", 8, "req-adj2"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("未达成共识");
        assertThat(repository.findAdjudication("adj-2")).isEmpty();
        assertThat(repository.findAllTimings(RACE)).isEmpty();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(8);

        // 不能凭旧110封榜：封榜后该选手仍漏点不排名，快照中该点耗时为null
        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(8, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
        StandingResponse standing = (StandingResponse) sealed.body();
        assertThat(standing.entries()).extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.MISSING_CHECKPOINT);
        SnapshotRow snapshot = repository.findSnapshot(RACE).orElseThrow();
        assertThat(snapshot.checkpoints()).hasSize(1);
        assertThat(snapshot.checkpoints().get(0).elapsedMillis()).isNull();
        assertThat(snapshot.checkpoints().get(0).adjudicationId()).isNull();
        assertThat(snapshot.sensorReadings()).isEmpty();
    }

    @Test
    void 修订后重新达成共识裁定引用当前修订号并封榜冻结三读数依据() {
        setupRaceWithThreeReadings();
        adjudicate("a", "adj-1", "c1", 6, "req-adj1"); // v7

        // 修订120->115，仍与100/110达成共识（差15<=20）
        reviseReading("a", "c1", "s3", 115L, 7, 1, "req-rev1"); // v8，revision=2
        AdjudicationResponse second = adjudicate("a", "adj-2", "c1", 8, "req-adj2"); // v9
        assertThat(second.medianMillis()).isEqualTo(110L);
        // 重新裁定引用三条当前修订号
        assertThat(second.readings()).extracting(SensorReadingBasis::revision)
                .containsExactly(1, 1, 2);
        assertThat(second.readings()).extracting(SensorReadingBasis::elapsedMillis)
                .containsExactly(100L, 110L, 115L);

        // 旧成功裁定按 adjudicationId 重放：仍返回首次修订号和耗时
        ServiceResult replay = raceService.adjudicate(RACE, "a",
                new AdjudicateRequest("adj-1", "c1", 9, "req-replay-adj1"));
        assertThat(replay.status()).isEqualTo(201);
        AdjudicationResponse replayed = (AdjudicationResponse) replay.body();
        assertThat(replayed.medianMillis()).isEqualTo(110L);
        assertThat(replayed.readings()).extracting(SensorReadingBasis::revision)
                .containsExactly(1, 1, 1);
        assertThat(replayed.readings()).extracting(SensorReadingBasis::elapsedMillis)
                .containsExactly(100L, 110L, 120L);
        // 重放不推进版本
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(9);

        // 封榜：参与排名选手的启用点存在有效裁定，冻结三读数及中位依据
        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(9, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
        StandingResponse standing = (StandingResponse) sealed.body();
        assertThat(standing.entries()).extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.RANKED);
        SnapshotRow snapshot = repository.findSnapshot(RACE).orElseThrow();
        assertThat(snapshot.checkpoints().get(0).elapsedMillis()).isEqualTo(110L);
        assertThat(snapshot.checkpoints().get(0).adjudicationId()).isEqualTo("adj-2");
        assertThat(snapshot.sensorReadings())
                .extracting(SnapshotSensorReadingRow::adjudicationId)
                .containsOnly("adj-2");
        assertThat(snapshot.sensorReadings())
                .extracting(SnapshotSensorReadingRow::sensorId)
                .containsExactly("s1", "s2", "s3");
        assertThat(snapshot.sensorReadings())
                .extracting(SnapshotSensorReadingRow::revision)
                .containsExactly(1, 1, 2);
        assertThat(snapshot.sensorReadings())
                .extracting(SnapshotSensorReadingRow::elapsedMillis)
                .containsExactly(100L, 110L, 115L);

        // 封榜后单选手分段查询走快照，裁定依据可见
        RunnerTimingResponse sealedTiming = raceService.getRunnerTimings(RACE, "a");
        assertThat(sealedTiming.checkpoints().get(0).adjudicationId()).isEqualTo("adj-2");
        assertThat(sealedTiming.checkpoints().get(0).elapsedMillis()).isEqualTo(110L);
    }

    @Test
    void 三源点不能由单条计时入口绕过且非三源点拒绝传感器采样() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a"); // v2
        configure(2, "req-cfg", List.of(
                new ConfigureCheckpointsRequest.CheckpointDefinition("c1", 1, true),
                new ConfigureCheckpointsRequest.CheckpointDefinition("c2", 2, false))); // v3

        // 三源点走原单条计时入口 -> 422
        assertThatThrownBy(() -> raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-bypass", "c1", 500L, 3, "req-bypass")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("三源");
        // 非三源点走传感器采样 -> 422
        assertThatThrownBy(() -> submitReading("a", "c2", "s1", 600L, 3, "req-r-c2"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("未启用三源");
        // 不存在的检查点 -> 404
        assertThatThrownBy(() -> submitReading("a", "c9", "s1", 600L, 3, "req-r-c9"))
                .isInstanceOf(NotFoundException.class);

        // 普通点仍可由原入口写入，版本只推进一次
        ServiceResult normal = raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-c2", "c2", 600L, 3, "req-t-c2"));
        assertThat(normal.status()).isEqualTo(201);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
    }

    @Test
    void 读数登记边界不齐全422第四传感器422重复传感器409非法耗时400() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a"); // v2
        configure(2, "req-cfg", List.of(
                new ConfigureCheckpointsRequest.CheckpointDefinition("c1", 1, true))); // v3

        // 读数不齐全（0条/2条）裁定 -> 422
        assertThatThrownBy(() -> adjudicate("a", "adj-0", "c1", 3, "req-adj0"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("不齐全");
        submitReading("a", "c1", "s1", 100L, 3, "req-r1"); // v4
        submitReading("a", "c1", "s2", 110L, 4, "req-r2"); // v5
        assertThatThrownBy(() -> adjudicate("a", "adj-0", "c1", 5, "req-adj0b"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("不齐全");

        // 非法耗时 -> 400
        assertThatThrownBy(() -> submitReading("a", "c1", "s3", 0L, 5, "req-r0"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> submitReading("a", "c1", "s3", 86_400_001L, 5, "req-r-max"))
                .isInstanceOf(BadRequestException.class);

        // 同一传感器重复登记 -> 409（须走修订入口）
        assertThatThrownBy(() -> submitReading("a", "c1", "s1", 105L, 5, "req-r1-dup"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("修订");

        submitReading("a", "c1", "s3", 120L, 5, "req-r3"); // v6
        // 第四个不同传感器 -> 422
        assertThatThrownBy(() -> submitReading("a", "c1", "s4", 130L, 6, "req-r4"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("三个");

        // 失败均不推进版本
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);
        assertThat(repository.findSensorReadings(RACE, "a", "c1")).hasSize(3);
    }

    @Test
    void 修订号冲突409且失败不占键封榜后禁止采样修订裁定() {
        setupRaceWithThreeReadings(); // v6

        // 修订号不匹配 -> 409，失败不占 requestId 键
        assertThatThrownBy(() -> reviseReading("a", "c1", "s3", 115L, 6, 5, "req-rev-bad"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("修订号");
        assertThat(repository.findIdempotency("req-rev-bad")).isEmpty();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);
        // 不存在的读数 -> 404
        assertThatThrownBy(() -> reviseReading("a", "c1", "s9", 115L, 6, 1, "req-rev-404"))
                .isInstanceOf(NotFoundException.class);
        // 同 requestId 用正确修订号重试成功
        SensorReadingResponse revised = reviseReading("a", "c1", "s3", 115L, 6, 1, "req-rev-bad");
        assertThat(revised.revision()).isEqualTo(2); // v7

        // 重新裁定并封榜
        adjudicate("a", "adj-1", "c1", 7, "req-adj1"); // v8
        raceService.sealRace(RACE, new SealRaceRequest(8, "req-seal")); // v9 SEALED

        // 封榜后采样/修订/裁定全部 409
        assertThatThrownBy(() -> submitReading("a", "c1", "s9", 100L, 9, "req-late-r"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
        assertThatThrownBy(() -> reviseReading("a", "c1", "s3", 116L, 9, 2, "req-late-rev"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
        assertThatThrownBy(() -> adjudicate("a", "adj-late", "c1", 9, "req-late-adj"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
        assertThat(repository.findRace(RACE).orElseThrow().status()).isEqualTo(RaceStatus.SEALED);
    }

    @Test
    void 中位值违反严格递增与完赛边界422整次不写() {
        createRace();
        registerRunner("a", 1_000L, 1, "req-a"); // v2
        configure(2, "req-cfg", List.of(
                new ConfigureCheckpointsRequest.CheckpointDefinition("c1", 1, false),
                new ConfigureCheckpointsRequest.CheckpointDefinition("c2", 2, true))); // v3
        // 普通点 c1 已有分段 500
        raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-c1", "c1", 500L, 3, "req-t-c1")); // v4
        submitReading("a", "c2", "s1", 100L, 4, "req-r1"); // v5
        submitReading("a", "c2", "s2", 110L, 5, "req-r2"); // v6
        submitReading("a", "c2", "s3", 120L, 6, "req-r3"); // v7

        // 中位110 不严格大于前序 c1=500 -> 422 整次不写
        assertThatThrownBy(() -> adjudicate("a", "adj-1", "c2", 7, "req-adj1"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("前一检查点");
        assertThat(repository.findAdjudication("adj-1")).isEmpty();
        assertThat(repository.findTimingsForRunner(RACE, "a")).hasSize(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(7);

        // 修订读数使中位值超过完赛耗时1000 -> 422
        reviseReading("a", "c2", "s1", 1_100L, 7, 1, "req-rev1"); // v8
        reviseReading("a", "c2", "s2", 1_105L, 8, 1, "req-rev2"); // v9
        reviseReading("a", "c2", "s3", 1_110L, 9, 1, "req-rev3"); // v10
        assertThatThrownBy(() -> adjudicate("a", "adj-2", "c2", 10, "req-adj2"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("完赛");
        assertThat(repository.findAdjudication("adj-2")).isEmpty();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(10);
    }

    @Test
    void 裁定幂等同键同参重放首次响应异参409且重复有效裁定409() {
        setupRaceWithThreeReadings(); // v6

        // 同 requestId 同参重放：返回首次完整响应，不推进版本
        AdjudicateRequest adjReq = new AdjudicateRequest("adj-1", "c1", 6, "req-adj1");
        ServiceResult first = raceService.adjudicate(RACE, "a", adjReq);
        ServiceResult replay = raceService.adjudicate(RACE, "a", adjReq);
        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(7);

        // 同 requestId 异参 -> 409
        assertThatThrownBy(() -> raceService.adjudicate(RACE, "a",
                new AdjudicateRequest("adj-9", "c1", 6, "req-adj1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");

        // 同 adjudicationId 异参（不同检查点）-> 409
        assertThatThrownBy(() -> raceService.adjudicate(RACE, "a",
                new AdjudicateRequest("adj-1", "c2", 7, "req-adj-other")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("adjudicationId");

        // 已有有效裁定再裁定（新 adjudicationId）-> 409
        assertThatThrownBy(() -> adjudicate("a", "adj-dup", "c1", 7, "req-adj-dup"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("有效裁定");
        assertThat(repository.findValidAdjudications(RACE)).hasSize(1);
    }

    @Test
    void 并发裁定与并发修订竞争均只有一个成功() throws Exception {
        setupRaceWithThreeReadings(); // v6

        int threads = 8;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger adjudicated =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger conflicts =
                new java.util.concurrent.atomic.AtomicInteger();
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        raceService.adjudicate(RACE, "a", new AdjudicateRequest(
                                "adj-conc-" + index, "c1", 6, "req-adj-conc-" + index));
                        adjudicated.incrementAndGet();
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
        // 同一版本只允许一个裁定成功，其余版本冲突或已存在有效裁定
        assertThat(adjudicated.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(repository.findAllTimings(RACE)).hasSize(1);
        assertThat(repository.findValidAdjudications(RACE)).hasSize(1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(7);

        // 并发修订同一读数：只有一个推进修订号
        java.util.concurrent.ExecutorService pool2 =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start2 = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger revised =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger reviseConflicts =
                new java.util.concurrent.atomic.AtomicInteger();
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(pool2.submit(() -> {
                    start2.await();
                    try {
                        raceService.reviseSensorReading(RACE, "a",
                                new ReviseSensorReadingRequest(
                                        "c1", "s3", 115L, 7, 1, "req-rev-conc-" + index));
                        revised.incrementAndGet();
                    } catch (ConflictException ex) {
                        reviseConflicts.incrementAndGet();
                    }
                    return null;
                }));
            }
            start2.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool2.shutdownNow();
        }
        assertThat(revised.get()).isEqualTo(1);
        assertThat(reviseConflicts.get()).isEqualTo(threads - 1);
        assertThat(repository.findSensorReading(RACE, "a", "c1", "s3").orElseThrow().revision())
                .isEqualTo(2);
        // 修订使旧裁定失效：无有效裁定、分段行删除
        assertThat(repository.findValidAdjudications(RACE)).isEmpty();
        assertThat(repository.findAllTimings(RACE)).isEmpty();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(8);
    }
}
