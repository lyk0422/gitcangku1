package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.EnablePursuitRequest;
import com.example.starter.race.api.PursuitConfigResponse;
import com.example.starter.race.api.PursuitRulingsResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RulingResponse;
import com.example.starter.race.api.SubmitRulingRequest;
import com.example.starter.race.api.SubmitSamplesRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.TrajectoryResponse;
import com.example.starter.race.domain.RaceMode;
import com.example.starter.race.domain.RulingStatus;
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
 * 追逐赛（PURSUIT）模式的 H2 服务层测试：主流程、未交会422、
 * 轨迹更正使旧裁定失效、版本冲突与模式守卫。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class PursuitServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-pursuit";

    @Autowired
    private RaceService raceService;

    /** 建赛并登记追赶者 a（起始0mm）与被追者 b（起始1000mm），启用 PURSUIT 后返回当前版本4。 */
    private int createPursuitRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", null, 1, "req-reg-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", null, 2, "req-reg-b"));
        ServiceResult enabled = raceService.enablePursuit(RACE, new EnablePursuitRequest(
                3,
                List.of(new EnablePursuitRequest.PursuitPairDefinition(
                        "pair-1", "a", "b", 0L, 1000L)),
                "op-test", "req-enable"));
        assertThat(enabled.status()).isEqualTo(201);
        PursuitConfigResponse config = (PursuitConfigResponse) enabled.body();
        assertThat(config.mode()).isEqualTo(RaceMode.PURSUIT);
        assertThat(config.pairs()).hasSize(1);
        return config.version();
    }

    private SubmitSamplesRequest samplesRequest(
            int expectedVersion, int expectedTrajectoryVersion, String requestId, long... points) {
        java.util.List<SubmitSamplesRequest.SamplePointRequest> samples = new java.util.ArrayList<>();
        for (int i = 0; i < points.length; i += 2) {
            samples.add(new SubmitSamplesRequest.SamplePointRequest(points[i], points[i + 1]));
        }
        return new SubmitSamplesRequest(
                expectedVersion, expectedTrajectoryVersion, samples, "op-test", requestId);
    }

    @Test
    void 合成样例主流程_最早交会10秒且裁定精确保存() {
        int version = createPursuitRace();
        // v5: a 的轨迹 (0,0)->(10000ms,3000mm)
        ServiceResult samplesA = raceService.submitSamples(RACE, "a",
                samplesRequest(version, 0, "req-sa", 0, 0, 10_000, 3_000));
        assertThat(((TrajectoryResponse) samplesA.body()).trajectoryVersion()).isEqualTo(1);
        // v6: b 的轨迹 (0,0)->(10000ms,2000mm)
        raceService.submitSamples(RACE, "b",
                samplesRequest(version + 1, 0, "req-sb", 0, 0, 10_000, 2_000));

        ServiceResult ruled = raceService.submitRuling(RACE, new SubmitRulingRequest(
                "ruling-1", "pair-1", 1, 1, "op-test", "req-ruling"));
        assertThat(ruled.status()).isEqualTo(201);
        RulingResponse ruling = (RulingResponse) ruled.body();
        assertThat(ruling.catchTimeNum()).isEqualTo("10000");
        assertThat(ruling.catchTimeDen()).isEqualTo("1");
        assertThat(ruling.status()).isEqualTo(RulingStatus.CURRENT);
        assertThat(ruling.pursuerTrajectoryVersion()).isEqualTo(1);
        assertThat(ruling.pursuedTrajectoryVersion()).isEqualTo(1);

        PursuitRulingsResponse rulings = raceService.getPursuitRulings(RACE);
        assertThat(rulings.rulings()).hasSize(1);
        assertThat(rulings.rulings().getFirst().rulingId()).isEqualTo("ruling-1");
        assertThat(rulings.version()).isEqualTo(7);
    }

    @Test
    void 合成样例_A仅前进2999_未交会返回422且不产生裁定() {
        int version = createPursuitRace();
        raceService.submitSamples(RACE, "a",
                samplesRequest(version, 0, "req-sa", 0, 0, 10_000, 2_999));
        raceService.submitSamples(RACE, "b",
                samplesRequest(version + 1, 0, "req-sb", 0, 0, 10_000, 2_000));

        assertThatThrownBy(() -> raceService.submitRuling(RACE, new SubmitRulingRequest(
                "ruling-1", "pair-1", 1, 1, "op-test", "req-ruling")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("未交会");
        assertThat(raceService.getPursuitRulings(RACE).rulings()).isEmpty();
        // 422 失败回滚不占 requestId：修正轨迹后同键可成功复用。
        raceService.submitSamples(RACE, "a",
                samplesRequest(version + 2, 1, "req-sa2", 0, 0, 10_000, 3_000));
        ServiceResult ruled = raceService.submitRuling(RACE, new SubmitRulingRequest(
                "ruling-2", "pair-1", 2, 1, "op-test", "req-ruling"));
        assertThat(ruled.status()).isEqualTo(201);
        assertThat(((RulingResponse) ruled.body()).catchTimeNum()).isEqualTo("10000");
    }

    @Test
    void 轨迹更正后旧裁定失效需重新裁定() {
        int version = createPursuitRace();
        raceService.submitSamples(RACE, "a",
                samplesRequest(version, 0, "req-sa", 0, 0, 10_000, 3_000));
        raceService.submitSamples(RACE, "b",
                samplesRequest(version + 1, 0, "req-sb", 0, 0, 10_000, 2_000));
        raceService.submitRuling(RACE, new SubmitRulingRequest(
                "ruling-1", "pair-1", 1, 1, "op-test", "req-ruling"));
        assertThat(raceService.getPursuitRulings(RACE).rulings()).hasSize(1);

        // 更正 a 的轨迹（轨迹版本1->2）：旧裁定失效。
        raceService.submitSamples(RACE, "a",
                samplesRequest(version + 3, 1, "req-sa2", 0, 0, 10_000, 3_500));
        assertThat(raceService.getPursuitRulings(RACE).rulings()).isEmpty();
        assertThat(repository.findRuling("ruling-1").orElseThrow().status())
                .isEqualTo(RulingStatus.SUPERSEDED);

        // 用旧轨迹版本裁定 -> 409；用当前版本裁定 -> 成功。
        assertThatThrownBy(() -> raceService.submitRuling(RACE, new SubmitRulingRequest(
                "ruling-2", "pair-1", 1, 1, "op-test", "req-ruling-2")))
                .isInstanceOf(ConflictException.class);
        ServiceResult ruled = raceService.submitRuling(RACE, new SubmitRulingRequest(
                "ruling-2", "pair-1", 2, 1, "op-test", "req-ruling-2"));
        assertThat(ruled.status()).isEqualTo(201);
        // a 速度0.35mm/ms、b 速度0.2mm/ms、起始差1000mm：0.15t = 1000 -> t = 20000/3，精确保存。
        assertThat(((RulingResponse) ruled.body()).catchTimeNum()).isEqualTo("20000");
        assertThat(((RulingResponse) ruled.body()).catchTimeDen()).isEqualTo("3");
    }

    @Test
    void 版本不匹配与模式守卫() {
        int version = createPursuitRace();
        // 赛事版本不匹配 -> 409
        assertThatThrownBy(() -> raceService.submitSamples(RACE, "a",
                samplesRequest(version + 1, 0, "req-sa", 0, 0, 10_000, 3_000)))
                .isInstanceOf(ConflictException.class);
        raceService.submitSamples(RACE, "a",
                samplesRequest(version, 0, "req-sa", 0, 0, 10_000, 3_000));
        // 轨迹版本不匹配 -> 409
        assertThatThrownBy(() -> raceService.submitSamples(RACE, "a",
                samplesRequest(version + 1, 0, "req-sa2", 0, 0, 10_000, 3_000)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("轨迹版本冲突");
        // PURSUIT 赛事禁止旧单条成绩入口
        assertThatThrownBy(() -> raceService.registerRunner(RACE,
                new RegisterRunnerRequest("c", 1000L, version + 1, "req-reg-c")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.reviseTime(RACE,
                new ReviseTimeRequest("a", 1000L, version + 1, "req-rev-a")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-1", "cp-1", 100L, version + 1, "req-t-1")))
                .isInstanceOf(ConflictException.class);
        // 重复启用 -> 409
        assertThatThrownBy(() -> raceService.enablePursuit(RACE, new EnablePursuitRequest(
                version + 1,
                List.of(new EnablePursuitRequest.PursuitPairDefinition(
                        "pair-2", "a", "b", 0L, 1000L)),
                "op-test", "req-enable-2")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 普通赛事禁止追逐赛入口且计时后不能再启用() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", null, 1, "req-reg-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", null, 2, "req-reg-b"));
        // 普通赛事提交轨迹 -> 409
        assertThatThrownBy(() -> raceService.submitSamples(RACE, "a",
                samplesRequest(3, 0, "req-sa", 0, 0, 10_000, 3_000)))
                .isInstanceOf(ConflictException.class);
        // 登记带成绩后再启用 -> 409（计时后禁止启用）
        raceService.registerRunner(RACE, new RegisterRunnerRequest("c", 1000L, 3, "req-reg-c"));
        assertThatThrownBy(() -> raceService.enablePursuit(RACE, new EnablePursuitRequest(
                4,
                List.of(new EnablePursuitRequest.PursuitPairDefinition(
                        "pair-1", "a", "b", 0L, 1000L)),
                "op-test", "req-enable")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("计时后");
    }

    @Test
    void 追逐对声明校验() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", null, 1, "req-reg-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", null, 2, "req-reg-b"));
        // 未登记选手 -> 404
        assertThatThrownBy(() -> raceService.enablePursuit(RACE, new EnablePursuitRequest(
                3,
                List.of(new EnablePursuitRequest.PursuitPairDefinition(
                        "pair-1", "a", "ghost", 0L, 1000L)),
                "op-test", "req-enable")))
                .isInstanceOf(NotFoundException.class);
        // 同一人在两对中 -> 400
        assertThatThrownBy(() -> raceService.enablePursuit(RACE, new EnablePursuitRequest(
                3,
                List.of(
                        new EnablePursuitRequest.PursuitPairDefinition(
                                "pair-1", "a", "b", 0L, 1000L),
                        new EnablePursuitRequest.PursuitPairDefinition(
                                "pair-2", "b", "a", 0L, 1000L)),
                "op-test", "req-enable")))
                .isInstanceOf(BadRequestException.class);
        // 追逐对数量超上限 -> 400
        List<EnablePursuitRequest.PursuitPairDefinition> tooMany = java.util.stream.IntStream
                .rangeClosed(1, 6)
                .mapToObj(i -> new EnablePursuitRequest.PursuitPairDefinition(
                        "pair-" + i, "a", "b", 0L, 1000L))
                .toList();
        assertThatThrownBy(() -> raceService.enablePursuit(RACE,
                new EnablePursuitRequest(3, tooMany, "op-test", "req-enable")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void 采样集合校验() {
        int version = createPursuitRace();
        // 首点必须为(0,0)
        assertThatThrownBy(() -> raceService.submitSamples(RACE, "a",
                samplesRequest(version, 0, "req-sa", 0, 5, 10_000, 3_000)))
                .isInstanceOf(BadRequestException.class);
        // 时刻必须严格递增
        assertThatThrownBy(() -> raceService.submitSamples(RACE, "a",
                samplesRequest(version, 0, "req-sa", 0, 0, 5_000, 1_000, 5_000, 2_000)))
                .isInstanceOf(BadRequestException.class);
        // 距离不得减少
        assertThatThrownBy(() -> raceService.submitSamples(RACE, "a",
                samplesRequest(version, 0, "req-sa", 0, 0, 5_000, 2_000, 10_000, 1_000)))
                .isInstanceOf(BadRequestException.class);
        // 未登记选手 -> 404
        assertThatThrownBy(() -> raceService.submitSamples(RACE, "ghost",
                samplesRequest(version, 0, "req-sg", 0, 0, 10_000, 3_000)))
                .isInstanceOf(NotFoundException.class);
        // 失败不占 requestId：合法采样同键成功
        ServiceResult ok = raceService.submitSamples(RACE, "a",
                samplesRequest(version, 0, "req-sa", 0, 0, 10_000, 3_000));
        assertThat(ok.status()).isEqualTo(201);
    }
}
