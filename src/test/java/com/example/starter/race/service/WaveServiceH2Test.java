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
import com.example.starter.race.api.WaveResponse;
import com.example.starter.race.api.WavesResponse;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.RaceRow;
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
 * 分批起跑波次能力的 H2 数据库测试：波次区间校验、净计时计算、修改后全量重校、
 * INVALID_WAVE、封榜快照固化、封榜后禁改与幂等。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class WaveServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-wave";
    private static final long BASE = 1_760_000_000_000L;

    @Autowired
    private RaceService raceService;

    private void createRace() {
        raceService.createRace(new CreateRaceRequest(RACE, BASE, "req-create"));
    }

    private void register(String bib, Long finishMs, int version, String reqId) {
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, finishMs, version, reqId));
    }

    private void configureCheckpoints(int version, String reqId) {
        List<ConfigureCheckpointsRequest.CheckpointDefinition> defs = List.of(
                new ConfigureCheckpointsRequest.CheckpointDefinition("c1", 1));
        raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(defs, version, reqId));
    }

    private RegisterWavesRequest.WaveDefinition wave(String key, long startAt, String... runners) {
        return new RegisterWavesRequest.WaveDefinition(key, startAt, List.of(runners));
    }

    private ServiceResult putWaves(int version, String reqId,
                                   RegisterWavesRequest.WaveDefinition... waves) {
        return raceService.registerWaves(RACE,
                new RegisterWavesRequest(List.of(waves), version, reqId));
    }

    @Test
    void 登记波次后按净计时排名并可查询波次与选手净计时() {
        createRace();
        register("a", 100_000L, 1, "req-a"); // v2
        register("b", 60_000L, 2, "req-b");  // v3

        ServiceResult result = putWaves(3, "req-waves",
                wave("wave-a", BASE + 60_000L, "a"));
        assertThat(result.status()).isEqualTo(200);

        WavesResponse waves = raceService.getWaves(RACE);
        assertThat(waves.baseStartAt()).isEqualTo(BASE);
        assertThat(waves.version()).isEqualTo(4);
        assertThat(waves.waves()).hasSize(1);
        WaveResponse waveA = waves.waves().getFirst();
        assertThat(waveA.waveKey()).isEqualTo("wave-a");
        assertThat(waveA.startAt()).isEqualTo(BASE + 60_000L);
        assertThat(waveA.runners()).containsExactly("a");

        StandingResponse standing = raceService.getResults(RACE);
        // a 枪声100000-波次偏移60000=净40000 排第1；b 无波次净60000 排第2
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b");
        assertThat(standing.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2);
        ResultEntryResponse a = standing.entries().getFirst();
        assertThat(a.netTimeMs()).isEqualTo(40_000L);
        assertThat(a.gunTimeMs()).isEqualTo(100_000L);
        assertThat(a.totalTimeMs()).isEqualTo(100_000L);
        assertThat(a.waveStartAt()).isEqualTo(BASE + 60_000L);
        assertThat(a.baseStartAt()).isEqualTo(BASE);

        RunnerNetTimeResponse netA = raceService.getRunnerNetTime(RACE, "a");
        assertThat(netA.status()).isEqualTo(EntryStatus.RANKED);
        assertThat(netA.netTimeMs()).isEqualTo(40_000L);
        assertThat(netA.waveKey()).isEqualTo("wave-a");
    }

    @Test
    void 波次起跑时刻相同允许且等于首个分段时刻也允许() {
        createRace();
        register("a", 10_000L, 1, "req-a"); // v2
        register("b", 10_000L, 2, "req-b"); // v3
        configureCheckpoints(3, "req-cfg");  // v4
        // a 在 c1 的首个分段累计耗时为 1000（相对基准）
        raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-a", "c1", 1_000L, 4, "req-ta")); // v5

        // 两个波次起跑时刻相同（均为基准+1000），且等于 a 的首个分段时刻：合法
        ServiceResult result = putWaves(5, "req-waves",
                wave("w1", BASE + 1_000L, "a"),
                wave("w2", BASE + 1_000L, "b"));
        assertThat(result.status()).isEqualTo(200);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);
    }

    @Test
    void 波次起跑晚于参赛者首个有效分段返回422且不推进版本() {
        createRace();
        register("a", 10_000L, 1, "req-a"); // v2
        configureCheckpoints(2, "req-cfg");  // v3
        raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-a", "c1", 1_000L, 3, "req-ta")); // v4

        // 波次偏移 2000 > 首个分段 1000 -> 422
        assertThatThrownBy(() -> putWaves(4, "req-waves-bad",
                wave("late", BASE + 2_000L, "a")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("首个有效分段");

        // 整次失败：无波次写入，版本仍为 4
        assertThat(raceService.getWaves(RACE).waves()).isEmpty();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
    }

    @Test
    void 区间与参赛者校验失败分支均不推进版本() {
        createRace();
        register("a", 10_000L, 1, "req-a"); // v2

        // 0 个波次
        assertThatThrownBy(() -> raceService.registerWaves(RACE,
                new RegisterWavesRequest(List.of(), 2, "req-empty")))
                .isInstanceOf(BadRequestException.class);

        // 21 个波次
        List<RegisterWavesRequest.WaveDefinition> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add(wave("w" + i, BASE + i));
        }
        assertThatThrownBy(() -> raceService.registerWaves(RACE,
                new RegisterWavesRequest(tooMany, 2, "req-many")))
                .isInstanceOf(BadRequestException.class);

        // waveKey 重复
        assertThatThrownBy(() -> putWaves(2, "req-dupkey",
                wave("dup", BASE + 1L, "a"),
                wave("dup", BASE + 2L)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("重复");

        // 同一参赛者属于两个波次
        assertThatThrownBy(() -> putWaves(2, "req-dupbib",
                wave("w1", BASE + 1L, "a"),
                wave("w2", BASE + 2L, "a")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("一个波次");

        // 参赛者不存在 -> 422
        assertThatThrownBy(() -> putWaves(2, "req-norunner",
                wave("w1", BASE + 1L, "ghost")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("不存在");

        // 全部失败：无波次写入，版本仍为 2
        assertThat(raceService.getWaves(RACE).waves()).isEmpty();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(2);
    }

    @Test
    void 修改波次后对全部已计时参赛者重新校验任一违反整次422并回滚() {
        createRace();
        register("a", 10_000L, 1, "req-a"); // v2
        configureCheckpoints(2, "req-cfg");  // v3
        // 先登记一个合法波次（偏移0），版本4
        assertThat(putWaves(3, "req-waves-1", wave("w1", BASE, "a")).status())
                .isEqualTo(200);
        // 再为 a 提交首个分段 c1=1000，版本5
        raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-a", "c1", 1_000L, 4, "req-ta"));

        // 携带过期版本修改 -> 409
        assertThatThrownBy(() -> putWaves(4, "req-stale", wave("w1", BASE + 2_000L, "a")))
                .isInstanceOf(ConflictException.class);

        // 携带最新版本但把波次改到晚于首个分段 -> 422，整次回滚，旧波次保留
        assertThatThrownBy(() -> putWaves(5, "req-waves-bad", wave("w1", BASE + 2_000L, "a")))
                .isInstanceOf(UnprocessableEntityException.class);
        WavesResponse after = raceService.getWaves(RACE);
        assertThat(after.waves()).hasSize(1);
        assertThat(after.waves().getFirst().startAt()).isEqualTo(BASE);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);

        // 合法修改（偏移不超过首个分段1000）成功，版本6
        assertThat(putWaves(5, "req-waves-2", wave("w1", BASE + 500L, "a")).status())
                .isEqualTo(200);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);
    }

    @Test
    void 波次选手提交早于波次起跑的分段返回422() {
        createRace();
        register("a", 10_000L, 1, "req-a"); // v2
        configureCheckpoints(2, "req-cfg");  // v3
        putWaves(3, "req-waves", wave("w1", BASE + 2_000L, "a")); // v4

        // 分段耗时 1000 早于波次起跑偏移 2000 -> 422
        assertThatThrownBy(() -> raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-early", "c1", 1_000L, 4, "req-early")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("波次起跑");

        // 合法分段（3000 >= 偏移2000 且 < 完赛10000）
        raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-ok", "c1", 3_000L, 4, "req-ok"));
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
    }

    @Test
    void 净计时为负标记INVALID_WAVE不排名但保留原始计时() {
        createRace();
        register("a", 50_000L, 1, "req-a"); // v2
        register("b", 60_000L, 2, "req-b"); // v3
        putWaves(3, "req-waves", wave("late", BASE + 100_000L, "a")); // v4

        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("b", "a");
        ResultEntryResponse invalid = standing.entries().get(1);
        assertThat(invalid.status()).isEqualTo(EntryStatus.INVALID_WAVE);
        assertThat(invalid.rank()).isNull();
        assertThat(invalid.netTimeMs()).isNull();
        assertThat(invalid.invalidReason()).isEqualTo("INVALID_WAVE");
        assertThat(invalid.finishTimeMs()).isEqualTo(50_000L);
        assertThat(invalid.gunTimeMs()).isEqualTo(50_000L);

        RunnerNetTimeResponse netA = raceService.getRunnerNetTime(RACE, "a");
        assertThat(netA.status()).isEqualTo(EntryStatus.INVALID_WAVE);
        assertThat(netA.netTimeMs()).isNull();
        assertThat(netA.gunTimeMs()).isEqualTo(50_000L);
    }

    @Test
    void 封榜快照固化波次原始与净计时且封榜后禁改且不追溯() {
        createRace();
        register("a", 100_000L, 1, "req-a"); // v2
        register("b", 60_000L, 2, "req-b");  // v3
        register("c", 50_000L, 3, "req-c");  // v4
        // c 波次晚 100000，净计时 -50000 -> INVALID_WAVE
        putWaves(4, "req-waves",
                wave("wa", BASE + 60_000L, "a"),
                wave("wc", BASE + 100_000L, "c")); // v5

        ServiceResult sealed = raceService.sealRace(RACE,
                new SealRaceRequest(5, "req-seal"));
        assertThat(sealed.status()).isEqualTo(200);
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(standing.version()).isEqualTo(6);
        // 排名：a 净40000 第1，b 净60000 第2，c INVALID_WAVE 列最后
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b", "c");
        assertThat(standing.entries()).extracting(ResultEntryResponse::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.RANKED,
                        EntryStatus.INVALID_WAVE);
        ResultEntryResponse sealedA = standing.entries().getFirst();
        assertThat(sealedA.waveKey()).isEqualTo("wa");
        assertThat(sealedA.netTimeMs()).isEqualTo(40_000L);
        ResultEntryResponse sealedC = standing.entries().get(2);
        assertThat(sealedC.invalidReason()).isEqualTo("INVALID_WAVE");
        assertThat(sealedC.netTimeMs()).isNull();
        assertThat(sealedC.gunTimeMs()).isEqualTo(50_000L);

        // 封榜快照查询同样固化
        StandingResponse snapshot = raceService.getSnapshot(RACE);
        assertThat(snapshot.entries()).extracting(ResultEntryResponse::netTimeMs)
                .containsExactly(40_000L, 60_000L, null);
        RunnerNetTimeResponse netC = raceService.getRunnerNetTime(RACE, "c");
        assertThat(netC.status()).isEqualTo(EntryStatus.INVALID_WAVE);

        // 封榜后不得再登记/修改波次 -> 409
        assertThatThrownBy(() -> putWaves(6, "req-after-seal",
                wave("wa", BASE + 60_000L, "a")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
    }

    @Test
    void 波次登记幂等同键同参重放异参409失败不占键() {
        createRace();
        register("a", 100_000L, 1, "req-a"); // v2

        RegisterWavesRequest first = new RegisterWavesRequest(
                List.of(wave("w1", BASE + 60_000L, "a")), 2, "req-idem");
        ServiceResult r1 = raceService.registerWaves(RACE, first);
        // 同键同参重放：返回首次结果，版本不再增加
        ServiceResult r2 = raceService.registerWaves(RACE, first);
        assertThat(r2.status()).isEqualTo(r1.status());
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(3);
        assertThat(repository.findWaves(RACE)).hasSize(1);

        // 同键异参 -> 409
        RegisterWavesRequest different = new RegisterWavesRequest(
                List.of(wave("w1", BASE + 70_000L, "a")), 3, "req-idem");
        assertThatThrownBy(() -> raceService.registerWaves(RACE, different))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不同参数");

        // 业务失败不占用新键：同一 requestId 改正参数后可成功
        register("b", 100_000L, 3, "req-b"); // v4
        assertThatThrownBy(() -> raceService.registerWaves(RACE,
                new RegisterWavesRequest(List.of(wave("w2", BASE + 1L, "ghost")),
                        4, "req-retry")))
                .isInstanceOf(UnprocessableEntityException.class);
        // 前次失败未占键：换成合法参赛者后同 requestId 成功（整批替换为仅 w2）
        raceService.registerWaves(RACE,
                new RegisterWavesRequest(List.of(wave("w2", BASE + 1L, "b")),
                        4, "req-retry"));
        assertThat(repository.findWaves(RACE)).hasSize(1);
        assertThat(repository.findWaveRunner(RACE, "b")).isPresent();
        assertThat(repository.findWaveRunner(RACE, "a")).isEmpty();
    }
}
