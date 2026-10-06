package com.example.starter.race.service;

import com.example.starter.race.api.AdjudicateSensorRequest;
import com.example.starter.race.api.CheckpointPassResponse;
import com.example.starter.race.api.CheckpointsConfigResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.ReviseSensorSampleRequest;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SensorAdjudicationResponse;
import com.example.starter.race.api.SensorRevisionResponse;
import com.example.starter.race.api.SensorSampleResponse;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitSensorSampleRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
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
 * 三源计时共识的 H2 数据库测试：合成读数裁定中位值、修订使旧裁定失效、
 * 缺共识按漏点不排名、单条入口拦截、采样与修订校验、封榜前置有效裁定。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class SensorQuorumServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-sensor";

    @Autowired
    private RaceService raceService;

    private void createRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
    }

    private void registerRunner(String bib, Long finishTimeMs, int expectedVersion, String reqId) {
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, finishTimeMs, expectedVersion, reqId));
    }

    private CheckpointsConfigResponse configure(int expectedVersion, String reqId,
                                                boolean sensorMode, String... codes) {
        List<ConfigureCheckpointsRequest.CheckpointDefinition> defs = new java.util.ArrayList<>();
        for (int i = 0; i < codes.length; i++) {
            defs.add(new ConfigureCheckpointsRequest.CheckpointDefinition(
                    codes[i], i + 1, sensorMode));
        }
        ServiceResult result = raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(defs, expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(201);
        return (CheckpointsConfigResponse) result.body();
    }

    private SensorSampleResponse sample(String bib, String sampleId, String code,
                                        String sensorId, long elapsed,
                                        int expectedVersion, String reqId) {
        ServiceResult result = raceService.submitSensorSample(RACE, bib,
                new SubmitSensorSampleRequest(sampleId, code, sensorId, elapsed,
                        "recorder", expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(201);
        return (SensorSampleResponse) result.body();
    }

    private SensorAdjudicationResponse adjudicate(String bib, String adjudicationId, String code,
                                                  int expectedVersion, String reqId) {
        ServiceResult result = raceService.adjudicateSensor(RACE, bib,
                new AdjudicateSensorRequest(adjudicationId, code, "referee",
                        expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(201);
        return (SensorAdjudicationResponse) result.body();
    }

    private SensorRevisionResponse revise(String bib, String code, String sensorId, long elapsed,
                                          int expectedRaceVersion, int expectedSensorRevision,
                                          String reqId) {
        ServiceResult result = raceService.reviseSensorSample(RACE, bib,
                new ReviseSensorSampleRequest(code, sensorId, elapsed, "recorder",
                        expectedRaceVersion, expectedSensorRevision, reqId));
        assertThat(result.status()).isEqualTo(200);
        return (SensorRevisionResponse) result.body();
    }

    @Test
    void 三源齐全且离散不超20毫秒时裁定中位值写入分段计时() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");              // v2
        CheckpointsConfigResponse config = configure(2, "req-cfg", true, "cp1"); // v3
        assertThat(config.checkpoints().get(0).sensorMode()).isTrue();

        // 题干合成示例：100、110、120
        SensorSampleResponse s1 = sample("a", "sp-1", "cp1", "sensor-1", 100L, 3, "req-s1"); // v4
        assertThat(s1.sensorRevision()).isEqualTo(1);
        sample("a", "sp-2", "cp1", "sensor-2", 110L, 4, "req-s2");  // v5
        sample("a", "sp-3", "cp1", "sensor-3", 120L, 5, "req-s3");  // v6

        // 裁定前：有完赛耗时但无有效裁定，按漏点不排名
        StandingResponse before = raceService.getResults(RACE);
        assertThat(before.entries()).extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.MISSING_CHECKPOINT);

        SensorAdjudicationResponse adjudication =
                adjudicate("a", "adj-1", "cp1", 6, "req-adj1");     // v7
        assertThat(adjudication.medianElapsedMillis()).isEqualTo(110L);
        assertThat(adjudication.raceVersion()).isEqualTo(7);
        assertThat(adjudication.readings())
                .extracting(r -> r.sensorId() + ":" + r.sensorRevision() + ":" + r.elapsedMillis())
                .containsExactly("sensor-1:1:100", "sensor-2:1:110", "sensor-3:1:120");

        // 中位值写入原分段计时，即时排名恢复
        RunnerTimingResponse timing = raceService.getRunnerTimings(RACE, "a");
        assertThat(timing.checkpoints()).extracting(CheckpointPassResponse::elapsedMillis)
                .containsExactly(110L);
        assertThat(timing.checkpoints().get(0).timingId()).isEqualTo("adj-1");
        StandingResponse after = raceService.getResults(RACE);
        assertThat(after.entries()).extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.RANKED);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(7);
    }

    @Test
    void 修订使旧裁定失效重新裁定422且不能凭旧中位值封榜() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");
        configure(2, "req-cfg", true, "cp1");                        // v3
        sample("a", "sp-1", "cp1", "sensor-1", 100L, 3, "req-s1");   // v4
        sample("a", "sp-2", "cp1", "sensor-2", 110L, 4, "req-s2");   // v5
        sample("a", "sp-3", "cp1", "sensor-3", 120L, 5, "req-s3");   // v6
        adjudicate("a", "adj-1", "cp1", 6, "req-adj1");              // v7，裁定110

        // 把120修订为131：赛事版本与传感器修订号共同推进
        SensorRevisionResponse revision = revise("a", "cp1", "sensor-3", 131L, 7, 1, "req-rev1");
        assertThat(revision.sensorRevision()).isEqualTo(2);
        assertThat(revision.raceVersion()).isEqualTo(8);             // v8

        // 旧裁定立即失效：历史裁定保留但已淘汰，分段计时移出即时排名视图
        assertThat(repository.findSensorAdjudication("adj-1").orElseThrow().superseded()).isTrue();
        assertThat(repository.findAllTimings(RACE)).isEmpty();
        StandingResponse live = raceService.getResults(RACE);
        assertThat(live.entries()).extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.MISSING_CHECKPOINT);

        // 重新裁定：100/110/131 离散31 > 20 -> 422 整次不写计时
        assertThatThrownBy(() -> adjudicate("a", "adj-2", "cp1", 8, "req-adj2"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("离散");
        assertThat(repository.findAllTimings(RACE)).isEmpty();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(8);

        // 不能凭旧110封榜：缺少有效裁定 -> 422
        assertThatThrownBy(() -> raceService.sealRace(RACE, new SealRaceRequest(8, "req-seal")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("缺少有效裁定");
        assertThat(repository.findRace(RACE).orElseThrow().status()).isEqualTo(RaceStatus.OPEN);

        // 再把131修订为115：100/110/115 离散15，重新裁定引用三条当前修订号
        revise("a", "cp1", "sensor-3", 115L, 8, 2, "req-rev2");      // v9
        SensorAdjudicationResponse reAdjudicated =
                adjudicate("a", "adj-3", "cp1", 9, "req-adj3");      // v10
        assertThat(reAdjudicated.medianElapsedMillis()).isEqualTo(110L);
        assertThat(reAdjudicated.readings())
                .extracting(r -> r.sensorId() + ":" + r.sensorRevision())
                .containsExactly("sensor-1:1", "sensor-2:1", "sensor-3:3");

        // 有效裁定齐备后可以封榜，历史裁定不覆写
        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(10, "req-seal2"));
        assertThat(sealed.status()).isEqualTo(200);
        assertThat(repository.findSensorAdjudication("adj-1").orElseThrow().superseded()).isTrue();
        assertThat(repository.findSensorAdjudication("adj-3").orElseThrow().superseded()).isFalse();
    }

    @Test
    void 三源点不能由单条计时入口绕过且非三源点拒绝传感器入口() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");
        // cp1 三源，cp2 普通
        List<ConfigureCheckpointsRequest.CheckpointDefinition> defs = List.of(
                new ConfigureCheckpointsRequest.CheckpointDefinition("cp1", 1, true),
                new ConfigureCheckpointsRequest.CheckpointDefinition("cp2", 2, false));
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(defs, 2, "req-cfg"));

        // 三源点走原单条计时入口 -> 422，不写入不推进版本
        assertThatThrownBy(() -> raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-1", "cp1", 100L, 3, "req-t1")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("三源");
        assertThat(repository.findAllTimings(RACE)).isEmpty();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);

        // 非三源点走传感器采样/裁定入口 -> 422
        assertThatThrownBy(() -> sample("a", "sp-x", "cp2", "sensor-1", 100L, 3, "req-sx"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("未启用三源");
        assertThatThrownBy(() -> adjudicate("a", "adj-x", "cp2", 3, "req-adjx"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("未启用三源");

        // 普通检查点仍可由单条入口写入
        ServiceResult normal = raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-2", "cp2", 500L, 3, "req-t2"));
        assertThat(normal.status()).isEqualTo(201);
    }

    @Test
    void 采样约束与读数边界校验() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");
        configure(2, "req-cfg", true, "cp1");                        // v3
        sample("a", "sp-1", "cp1", "sensor-1", 100L, 3, "req-s1");   // v4
        sample("a", "sp-2", "cp1", "sensor-2", 110L, 4, "req-s2");   // v5
        sample("a", "sp-3", "cp1", "sensor-3", 120L, 5, "req-s3");   // v6

        // 同一传感器重复登记 -> 422
        assertThatThrownBy(() -> sample("a", "sp-4", "cp1", "sensor-1", 105L, 6, "req-s4"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("修订");
        // 第四个不同传感器 -> 422
        assertThatThrownBy(() -> sample("a", "sp-5", "cp1", "sensor-4", 105L, 6, "req-s5"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("最多");
        // 读数越界 -> 400
        assertThatThrownBy(() -> raceService.submitSensorSample(RACE, "a",
                new SubmitSensorSampleRequest("sp-6", "cp1", "sensor-9", 0L,
                        "recorder", 6, "req-s6")))
                .isInstanceOf(BadRequestException.class);
        // 全部失败不推进版本、不新增采样
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);
        assertThat(repository.findSensorSamples(RACE, "a", "cp1")).hasSize(3);
    }

    @Test
    void 修订校验与封榜后禁止修订() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");
        configure(2, "req-cfg", true, "cp1");                        // v3
        sample("a", "sp-1", "cp1", "sensor-1", 100L, 3, "req-s1");   // v4

        // 传感器修订号不匹配 -> 409
        assertThatThrownBy(() -> revise("a", "cp1", "sensor-1", 105L, 4, 9, "req-r1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("修订号");
        // 赛事版本不匹配 -> 409
        assertThatThrownBy(() -> revise("a", "cp1", "sensor-1", 105L, 99, 1, "req-r2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("版本冲突");
        // 未登记的传感器 -> 404
        assertThatThrownBy(() -> revise("a", "cp1", "sensor-9", 105L, 4, 1, "req-r3"))
                .isInstanceOf(NotFoundException.class);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);

        // 封榜后禁止修订（先齐三源并裁定才能封榜）
        sample("a", "sp-2", "cp1", "sensor-2", 110L, 4, "req-s2");   // v5
        sample("a", "sp-3", "cp1", "sensor-3", 120L, 5, "req-s3");   // v6
        adjudicate("a", "adj-1", "cp1", 6, "req-adj1");              // v7
        raceService.sealRace(RACE, new SealRaceRequest(7, "req-seal"));
        assertThatThrownBy(() -> revise("a", "cp1", "sensor-1", 105L, 8, 1, "req-r4"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
    }

    @Test
    void 未集齐三条读数时裁定422() {
        createRace();
        registerRunner("a", 10_000L, 1, "req-a");
        configure(2, "req-cfg", true, "cp1");                        // v3
        sample("a", "sp-1", "cp1", "sensor-1", 100L, 3, "req-s1");   // v4
        sample("a", "sp-2", "cp1", "sensor-2", 110L, 4, "req-s2");   // v5

        // 只有两条读数，未集齐 -> 422
        assertThatThrownBy(() -> adjudicate("a", "adj-1", "cp1", 5, "req-adj1"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("3");
        assertThat(repository.findAllTimings(RACE)).isEmpty();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
    }
}
