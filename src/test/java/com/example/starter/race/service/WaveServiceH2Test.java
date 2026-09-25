package com.example.starter.race.service;

import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RegisterWavesRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.RunnerNetTimeResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.UpdateWaveRequest;
import com.example.starter.race.api.WaveResponse;
import com.example.starter.race.api.WavesResponse;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
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
 * 分批起跑波次能力的真实 H2 数据库测试：
 * 区间校验、净计时计算、修改后全量重校验、负净计时 INVALID_WAVE、封榜固化与幂等边界。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class WaveServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-wave";
    /** 赛事基准起跑时刻：2026-01-01T00:00:00Z。 */
    private static final long BASE = 1_767_225_600_000L;

    @Autowired
    private RaceService raceService;

    private void createRaceWithBase(long baseStartMs) {
        raceService.createRace(new CreateRaceRequest(RACE, baseStartMs, "req-create"));
    }

    private void registerRunner(String bib, Long finishTimeMs, int version, String requestId) {
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, finishTimeMs, version, requestId));
    }

    private void configureOneCheckpoint(int version, String requestId) {
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(
                List.of(new ConfigureCheckpointsRequest.CheckpointDefinition("p1", 1)),
                version, requestId));
    }

    private void submitTiming(String bib, long elapsedMillis, int version, String requestId) {
        raceService.submitTiming(RACE, bib, new SubmitTimingRequest(
                "timing-" + requestId, "p1", elapsedMillis, version, requestId));
    }

    private RegisterWavesRequest.WaveDefinition wave(String key, long startMs, String... bibs) {
        return new RegisterWavesRequest.WaveDefinition(key, startMs, List.of(bibs));
    }

    @Test
    void 波次登记后按净计时排名并可查询波次清单与参赛者净计时() {
        createRaceWithBase(BASE);                 // v1
        registerRunner("A", 5000L, 1, "reg-a");   // v2
        registerRunner("B", 5000L, 2, "reg-b");   // v3
        registerRunner("C", 5000L, 3, "reg-c");   // v4
        // w1 与基准同时刻；w2 晚 2000ms；C 无波次
        raceService.registerWaves(RACE, new RegisterWavesRequest(
                4,
                List.of(wave("w1", BASE, "A"), wave("w2", BASE + 2000L, "B")),
                "req-waves"));                    // v5

        StandingResponse standing = raceService.getResults(RACE);
        // 净计时：B=3000 第一；A=5000 与 C=5000 并列，参赛号字典序 A 在 C 前
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("B", "A", "C");
        assertThat(standing.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2, 2);
        ResultEntryResponse b = standing.entries().getFirst();
        assertThat(b.netTimeMs()).isEqualTo(3000L);
        assertThat(b.totalTimeMs()).isEqualTo(5000L);
        assertThat(b.waveKey()).isEqualTo("w2");
        assertThat(b.waveStartMs()).isEqualTo(BASE + 2000L);
        assertThat(b.baseStartMs()).isEqualTo(BASE);
        assertThat(standing.version()).isEqualTo(5);

        WavesResponse waves = raceService.getWaves(RACE);
        assertThat(waves.version()).isEqualTo(5);
        assertThat(waves.waves()).extracting(WaveResponse::waveKey)
                .containsExactly("w1", "w2");
        assertThat(waves.waves().getFirst().bibs()).containsExactly("A");
        assertThat(waves.waves().get(1).bibs()).containsExactly("B");

        RunnerNetTimeResponse netB = raceService.getRunnerNetTime(RACE, "B");
        assertThat(netB.status()).isEqualTo(EntryStatus.RANKED);
        assertThat(netB.netTimeMs()).isEqualTo(3000L);
        assertThat(netB.waveKey()).isEqualTo("w2");
        RunnerNetTimeResponse netC = raceService.getRunnerNetTime(RACE, "C");
        assertThat(netC.waveKey()).isNull();
        assertThat(netC.netTimeMs()).isEqualTo(5000L);
        assertThatThrownBy(() -> raceService.getRunnerNetTime(RACE, "Z"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 波次数量区间与无基准起跑时刻被拒绝() {
        createRaceWithBase(BASE);
        // 0 个波次
        assertThatThrownBy(() -> raceService.registerWaves(RACE,
                new RegisterWavesRequest(1, List.of(), "req-empty")))
                .isInstanceOf(BadRequestException.class);
        // 21 个波次
        List<RegisterWavesRequest.WaveDefinition> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add(wave("w" + i, BASE + i));
        }
        assertThatThrownBy(() -> raceService.registerWaves(RACE,
                new RegisterWavesRequest(1, tooMany, "req-too-many")))
                .isInstanceOf(BadRequestException.class);

        // 未设置基准起跑时刻的赛事不能登记波次
        raceService.createRace(new CreateRaceRequest("race-no-base", "req-create-nb"));
        assertThatThrownBy(() -> raceService.registerWaves("race-no-base",
                new RegisterWavesRequest(1, List.of(wave("w1", BASE)), "req-nb-waves")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void 重复waveKey重复参赛号未知选手与重复登记被拒绝() {
        createRaceWithBase(BASE);
        registerRunner("A", 5000L, 1, "reg-a");
        registerRunner("B", 5000L, 2, "reg-b");

        // waveKey 在同一次登记内重复
        assertThatThrownBy(() -> raceService.registerWaves(RACE, new RegisterWavesRequest(
                3, List.of(wave("w1", BASE, "A"), wave("w1", BASE, "B")), "req-dup-key")))
                .isInstanceOf(BadRequestException.class);
        // 同一参赛者属于两个波次
        assertThatThrownBy(() -> raceService.registerWaves(RACE, new RegisterWavesRequest(
                3, List.of(wave("w1", BASE, "A"), wave("w2", BASE, "A")), "req-dup-bib")))
                .isInstanceOf(UnprocessableEntityException.class);
        // 参赛者未登记
        assertThatThrownBy(() -> raceService.registerWaves(RACE, new RegisterWavesRequest(
                3, List.of(wave("w1", BASE, "Z")), "req-unknown")))
                .isInstanceOf(NotFoundException.class);

        // 两个波次允许相同起跑时刻
        raceService.registerWaves(RACE, new RegisterWavesRequest(
                3, List.of(wave("w1", BASE, "A"), wave("w2", BASE, "B")), "req-ok"));
        // 已登记波次的赛事不能再次登记
        assertThatThrownBy(() -> raceService.registerWaves(RACE, new RegisterWavesRequest(
                4, List.of(wave("w3", BASE)), "req-again")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 波次起跑晚于首个有效分段计时422相等放行() {
        createRaceWithBase(BASE);                 // v1
        registerRunner("A", 5000L, 1, "reg-a");   // v2
        configureOneCheckpoint(2, "req-cp");      // v3
        submitTiming("A", 500L, 3, "req-t1");     // v4 首个分段累计 500ms

        // 波次相对基准 501ms > 首个分段 500ms：422，不推进版本
        assertThatThrownBy(() -> raceService.registerWaves(RACE, new RegisterWavesRequest(
                4, List.of(wave("w1", BASE + 501L, "A")), "req-late")))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
        assertThat(repository.findWaves(RACE)).isEmpty();

        // 边界相等（500 == 500）放行
        raceService.registerWaves(RACE, new RegisterWavesRequest(
                4, List.of(wave("w1", BASE + 500L, "A")), "req-equal"));
        assertThat(repository.findWaves(RACE)).hasSize(1);
    }

    @Test
    void 修改波次后重新校验全部已计时参赛者且失败整次不生效() {
        createRaceWithBase(BASE);
        registerRunner("A", 5000L, 1, "reg-a");
        registerRunner("B", 5000L, 2, "reg-b");
        configureOneCheckpoint(3, "req-cp");      // v4
        submitTiming("A", 500L, 4, "req-ta");     // v5
        submitTiming("B", 500L, 5, "req-tb");     // v6
        raceService.registerWaves(RACE, new RegisterWavesRequest(
                6,
                List.of(wave("w1", BASE + 500L, "A"), wave("w2", BASE + 500L, "B")),
                "req-waves"));                    // v7

        // 把 w1 起跑改成 501ms：A 的首个分段只有 500ms，整次 422
        assertThatThrownBy(() -> raceService.updateWave(RACE, "w1",
                new UpdateWaveRequest(BASE + 501L, List.of("A"), 7, "req-upd-bad")))
                .isInstanceOf(UnprocessableEntityException.class);
        // 整次回滚：起跑时刻、成员、版本均不变
        assertThat(repository.findWave(RACE, "w1").orElseThrow().startMs())
                .isEqualTo(BASE + 500L);
        assertThat(repository.findWaveEntrants(RACE, "w1")).hasSize(1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(7);

        // 同波次内成员重复：422
        assertThatThrownBy(() -> raceService.updateWave(RACE, "w1",
                new UpdateWaveRequest(BASE + 500L, List.of("A", "A"), 7, "req-upd-dup")))
                .isInstanceOf(UnprocessableEntityException.class);
        // 波次不存在：404
        assertThatThrownBy(() -> raceService.updateWave(RACE, "nope",
                new UpdateWaveRequest(BASE, List.of("A"), 7, "req-upd-404")))
                .isInstanceOf(NotFoundException.class);
        // 版本不匹配：409
        assertThatThrownBy(() -> raceService.updateWave(RACE, "w1",
                new UpdateWaveRequest(BASE + 500L, List.of("A"), 6, "req-upd-stale")))
                .isInstanceOf(ConflictException.class);

        // 合法修改：w1 起跑提前到 300ms，并把 B 从 w2 移入 w1；B 的偏移 300 <= 500 合法
        raceService.updateWave(RACE, "w1",
                new UpdateWaveRequest(BASE + 300L, List.of("A", "B"), 7, "req-upd-ok"));
        WavesResponse waves = raceService.getWaves(RACE);
        WaveResponse w1 = waves.waves().stream()
                .filter(w -> w.waveKey().equals("w1")).findFirst().orElseThrow();
        WaveResponse w2 = waves.waves().stream()
                .filter(w -> w.waveKey().equals("w2")).findFirst().orElseThrow();
        assertThat(w1.startMs()).isEqualTo(BASE + 300L);
        assertThat(w1.bibs()).containsExactly("A", "B");
        assertThat(w2.bibs()).isEmpty();
        // A、B 净计时均为 5000-300=4700，并列第一
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("A", "B");
        assertThat(standing.entries()).extracting(ResultEntryResponse::netTimeMs)
                .containsExactly(4700L, 4700L);
        assertThat(standing.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 1);
    }

    @Test
    void 负净计时为INVALID_WAVE且封榜固化波次与原始计时随后修改全部拒绝() {
        createRaceWithBase(BASE);
        registerRunner("good", 5000L, 1, "reg-g");
        registerRunner("bad", 1000L, 2, "reg-b");
        raceService.registerWaves(RACE, new RegisterWavesRequest(
                3,
                List.of(wave("w1", BASE + 1000L, "good"),
                        wave("w2", BASE + 2000L, "bad")),
                "req-waves"));                    // v4

        StandingResponse live = raceService.getResults(RACE);
        assertThat(live.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("good", "bad");
        ResultEntryResponse bad = live.entries().get(1);
        assertThat(bad.status()).isEqualTo(EntryStatus.INVALID_WAVE);
        assertThat(bad.rank()).isNull();
        assertThat(bad.netTimeMs()).isNull();
        assertThat(bad.finishTimeMs()).isEqualTo(1000L);
        assertThat(bad.waveKey()).isEqualTo("w2");
        assertThat(bad.invalidReason()).isEqualTo("INVALID_WAVE");

        raceService.sealRace(RACE, new SealRaceRequest(4, "req-seal"));  // v5
        StandingResponse snapshot = raceService.getSnapshot(RACE);
        ResultEntryResponse sealedBad = snapshot.entries().stream()
                .filter(e -> e.bib().equals("bad")).findFirst().orElseThrow();
        assertThat(sealedBad.status()).isEqualTo(EntryStatus.INVALID_WAVE);
        assertThat(sealedBad.finishTimeMs()).isEqualTo(1000L);
        assertThat(sealedBad.waveKey()).isEqualTo("w2");
        assertThat(sealedBad.waveStartMs()).isEqualTo(BASE + 2000L);
        assertThat(sealedBad.baseStartMs()).isEqualTo(BASE);
        assertThat(sealedBad.netTimeMs()).isNull();
        ResultEntryResponse sealedGood = snapshot.entries().getFirst();
        assertThat(sealedGood.status()).isEqualTo(EntryStatus.RANKED);
        assertThat(sealedGood.netTimeMs()).isEqualTo(4000L);

        // 封榜后净计时查询走只读快照
        RunnerNetTimeResponse netBad = raceService.getRunnerNetTime(RACE, "bad");
        assertThat(netBad.status()).isEqualTo(EntryStatus.INVALID_WAVE);
        assertThat(netBad.waveKey()).isEqualTo("w2");

        // 封榜后登记/修改波次一律 409，既有快照不被追溯改写
        assertThatThrownBy(() -> raceService.registerWaves(RACE, new RegisterWavesRequest(
                5, List.of(wave("w3", BASE)), "req-after-seal")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.updateWave(RACE, "w2",
                new UpdateWaveRequest(BASE, List.of("bad"), 5, "req-upd-after-seal")))
                .isInstanceOf(ConflictException.class);
        assertThat(raceService.getSnapshot(RACE).entries())
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(snapshot.entries());
        assertThat(repository.findRace(RACE).orElseThrow().status())
                .isEqualTo(RaceStatus.SEALED);
    }

    @Test
    void 波次登记幂等同键重放异参409失败不占键() {
        createRaceWithBase(BASE);
        registerRunner("A", 5000L, 1, "reg-a");
        registerRunner("B", 5000L, 2, "reg-b");

        RegisterWavesRequest request = new RegisterWavesRequest(
                3, List.of(wave("w1", BASE + 100L, "A"), wave("w2", BASE + 200L, "B")),
                "req-wave-idem");
        ServiceResult first = raceService.registerWaves(RACE, request);
        ServiceResult replay = raceService.registerWaves(RACE, request);
        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(201);
        // 只产生一次变更：版本到4、波次2个
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
        assertThat(repository.findWaves(RACE)).hasSize(2);

        // 同键异参：409
        assertThatThrownBy(() -> raceService.registerWaves(RACE, new RegisterWavesRequest(
                3, List.of(wave("w1", BASE + 101L, "A"), wave("w2", BASE + 200L, "B")),
                "req-wave-idem")))
                .isInstanceOf(ConflictException.class);

        // 失败不占键：先用同键制造一次 422（成员重复），再用同键完成合法修改
        assertThatThrownBy(() -> raceService.updateWave(RACE, "w1",
                new UpdateWaveRequest(BASE + 100L, List.of("A", "A"), 4, "req-upd-idem")))
                .isInstanceOf(UnprocessableEntityException.class);
        ServiceResult recovered = raceService.updateWave(RACE, "w1",
                new UpdateWaveRequest(BASE + 300L, List.of("A"), 4, "req-upd-idem"));
        assertThat(recovered.status()).isEqualTo(200);
        assertThat(repository.findWave(RACE, "w1").orElseThrow().startMs())
                .isEqualTo(BASE + 300L);
    }
}
