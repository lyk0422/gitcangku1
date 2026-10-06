package com.example.starter.race.service;

import com.example.starter.race.api.AdjudicateSensorRequest;
import com.example.starter.race.api.CheckpointsConfigResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseSensorSampleRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SensorAdjudicationResponse;
import com.example.starter.race.api.SensorRevisionResponse;
import com.example.starter.race.api.SensorSampleResponse;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitSensorSampleRequest;
import com.example.starter.race.persistence.SnapshotRow;
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
 * 三源计时共识的幂等与封榜冻结 H2 测试：requestId 同参重放/异参409/失败不占键、
 * sampleId 与 adjudicationId 第二层幂等、历史裁定不覆写、封榜冻结三读数及中位依据。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class SensorQuorumIdempotencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-sensor-idem";

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

    /** 准备：赛事 + 选手a + 三源检查点 cp1，返回当前版本3。 */
    private void setupRaceWithSensorCheckpoint() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 10_000L, 1, "req-a"));
        ServiceResult result = raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(List.of(
                        new ConfigureCheckpointsRequest.CheckpointDefinition("cp1", 1, true)),
                        2, "req-cfg"));
        assertThat(result.status()).isEqualTo(201);
        assertThat(((CheckpointsConfigResponse) result.body()).version()).isEqualTo(3);
    }

    private void sampleThree(long e1, long e2, long e3, int baseVersion, String tag) {
        raceService.submitSensorSample(RACE, "a", new SubmitSensorSampleRequest(
                "sp-1-" + tag, "cp1", "sensor-1", e1, "recorder", baseVersion, "req-s1-" + tag));
        raceService.submitSensorSample(RACE, "a", new SubmitSensorSampleRequest(
                "sp-2-" + tag, "cp1", "sensor-2", e2, "recorder", baseVersion + 1, "req-s2-" + tag));
        raceService.submitSensorSample(RACE, "a", new SubmitSensorSampleRequest(
                "sp-3-" + tag, "cp1", "sensor-3", e3, "recorder", baseVersion + 2, "req-s3-" + tag));
    }

    @Test
    void requestId同参重放首次完整响应异参409且失败不占键() {
        setupRaceWithSensorCheckpoint();

        // 采样：同 requestId 同参重放返回首次响应（含首次修订号与读数）
        SubmitSensorSampleRequest sampleReq = new SubmitSensorSampleRequest(
                "sp-1", "cp1", "sensor-1", 100L, "recorder", 3, "req-sample");
        ServiceResult first = raceService.submitSensorSample(RACE, "a", sampleReq);
        ServiceResult replay = raceService.submitSensorSample(RACE, "a", sampleReq);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);

        // 同 requestId 异参 -> 409
        assertThatThrownBy(() -> raceService.submitSensorSample(RACE, "a",
                new SubmitSensorSampleRequest("sp-1", "cp1", "sensor-1", 101L,
                        "recorder", 3, "req-sample")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");

        // 业务失败不占键：错误版本提交失败后，同 requestId 用正确版本重试成功
        assertThatThrownBy(() -> raceService.submitSensorSample(RACE, "a",
                new SubmitSensorSampleRequest("sp-2", "cp1", "sensor-2", 110L,
                        "recorder", 999, "req-s2")))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findIdempotency("req-s2")).isEmpty();
        ServiceResult retried = raceService.submitSensorSample(RACE, "a",
                new SubmitSensorSampleRequest("sp-2", "cp1", "sensor-2", 110L,
                        "recorder", 4, "req-s2"));
        assertThat(retried.status()).isEqualTo(201);
    }

    @Test
    void sampleId第二层幂等同参重放异参409() {
        setupRaceWithSensorCheckpoint();
        raceService.submitSensorSample(RACE, "a", new SubmitSensorSampleRequest(
                "sp-1", "cp1", "sensor-1", 100L, "recorder", 3, "req-s1")); // v4

        // 同 sampleId 同参但新 requestId：作为采样重放返回原结果，不推进版本
        ServiceResult sampleReplay = raceService.submitSensorSample(RACE, "a",
                new SubmitSensorSampleRequest("sp-1", "cp1", "sensor-1", 100L,
                        "recorder", 999, "req-replay-sp"));
        assertThat(sampleReplay.status()).isEqualTo(201);
        assertThat(((SensorSampleResponse) sampleReplay.body()).elapsedMillis()).isEqualTo(100L);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);

        // 同 sampleId 异参 -> 409
        assertThatThrownBy(() -> raceService.submitSensorSample(RACE, "a",
                new SubmitSensorSampleRequest("sp-1", "cp1", "sensor-2", 100L,
                        "recorder", 4, "req-other-sp")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("sampleId");
        assertThat(repository.findSensorSamples(RACE, "a", "cp1")).hasSize(1);
    }

    @Test
    void 裁定幂等与历史裁定不覆写且旧成功重放返回首次修订号和耗时() {
        setupRaceWithSensorCheckpoint();
        sampleThree(100L, 110L, 120L, 3, "main");                    // v4,v5,v6
        AdjudicateSensorRequest adjReq =
                new AdjudicateSensorRequest("adj-1", "cp1", "referee", 6, "req-adj1");
        ServiceResult first = raceService.adjudicateSensor(RACE, "a", adjReq); // v7
        assertThat(first.status()).isEqualTo(201);

        // requestId 同参重放：返回首次完整响应
        ServiceResult replay = raceService.adjudicateSensor(RACE, "a", adjReq);
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        // adjudicationId 同参新 requestId：第二层重放，不推进版本
        ServiceResult secondLayer = raceService.adjudicateSensor(RACE, "a",
                new AdjudicateSensorRequest("adj-1", "cp1", "referee", 999, "req-adj1-b"));
        assertThat(secondLayer.status()).isEqualTo(201);
        assertThat(((SensorAdjudicationResponse) secondLayer.body()).medianElapsedMillis())
                .isEqualTo(110L);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(7);
        // adjudicationId 异参 -> 409
        assertThatThrownBy(() -> raceService.adjudicateSensor(RACE, "a",
                new AdjudicateSensorRequest("adj-1", "cp1", "other", 7, "req-adj1-c")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("adjudicationId");

        // 修订使旧裁定失效；旧成功请求重放仍返回首次修订号和耗时（110）
        raceService.reviseSensorSample(RACE, "a", new ReviseSensorSampleRequest(
                "cp1", "sensor-3", 131L, "recorder", 7, 1, "req-rev1"));       // v8
        ServiceResult oldReplay = raceService.adjudicateSensor(RACE, "a", adjReq);
        SensorAdjudicationResponse oldBody =
                (SensorAdjudicationResponse) objectMapper.convertValue(
                        oldReplay.body(), SensorAdjudicationResponse.class);
        assertThat(oldBody.medianElapsedMillis()).isEqualTo(110L);
        assertThat(oldBody.readings())
                .extracting(r -> r.sensorId() + ":" + r.sensorRevision())
                .containsExactly("sensor-1:1", "sensor-2:1", "sensor-3:1");
        // 历史裁定保留不覆写
        assertThat(repository.findSensorAdjudication("adj-1").orElseThrow().superseded()).isTrue();
        assertThat(repository.findSensorAdjudication("adj-1").orElseThrow().medianElapsedMillis())
                .isEqualTo(110L);
    }

    @Test
    void 修订幂等重放返回首次修订号且封榜快照冻结三读数及中位依据() {
        setupRaceWithSensorCheckpoint();
        sampleThree(100L, 110L, 120L, 3, "main");                    // v4,v5,v6
        raceService.adjudicateSensor(RACE, "a",
                new AdjudicateSensorRequest("adj-1", "cp1", "referee", 6, "req-adj1")); // v7

        ReviseSensorSampleRequest revReq = new ReviseSensorSampleRequest(
                "cp1", "sensor-3", 118L, "recorder", 7, 1, "req-rev1");
        ServiceResult firstRev = raceService.reviseSensorSample(RACE, "a", revReq); // v8
        assertThat(((SensorRevisionResponse) firstRev.body()).sensorRevision()).isEqualTo(2);
        // 同 requestId 重放：仍返回首次修订号2与读数118，不重复推进
        ServiceResult revReplay = raceService.reviseSensorSample(RACE, "a", revReq);
        assertThat(json(revReplay.body())).isEqualTo(json(firstRev.body()));
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(8);
        assertThat(repository.findSensorSamples(RACE, "a", "cp1"))
                .filteredOn(s -> s.sensorId().equals("sensor-3"))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.sensorRevision()).isEqualTo(2);
                    assertThat(s.elapsedMillis()).isEqualTo(118L);
                });
        // 同 requestId 异参 -> 409
        assertThatThrownBy(() -> raceService.reviseSensorSample(RACE, "a",
                new ReviseSensorSampleRequest("cp1", "sensor-3", 119L,
                        "recorder", 7, 1, "req-rev1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");

        // 重新裁定（100/110/118 离散18）后封榜：快照冻结三读数及中位依据
        raceService.adjudicateSensor(RACE, "a",
                new AdjudicateSensorRequest("adj-2", "cp1", "referee", 8, "req-adj2")); // v9
        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(9, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
        SnapshotRow snapshot = repository.findSnapshot(RACE).orElseThrow();
        assertThat(snapshot.checkpoints()).singleElement().satisfies(detail -> {
            assertThat(detail.adjudicationId()).isEqualTo("adj-2");
            assertThat(detail.elapsedMillis()).isEqualTo(110L);
            assertThat(detail.sensorReadings()).contains("sensor-3").contains("118");
        });
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.status().name()).isEqualTo("SEALED");
    }
}
