package com.example.starter.race.service;

import com.example.starter.race.api.CheckpointsConfigResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.EnableIntervalModeRequest;
import com.example.starter.race.api.IntervalPassResponse;
import com.example.starter.race.api.IntervalTimingResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.ReviseIntervalRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RunnerIntervalsResponse;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitIntervalRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.IntervalRules;
import com.example.starter.race.domain.TimingMode;
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
 * 区间计时模式的 H2 数据库测试：启用模式、主流程名次范围、
 * 非法区间400、相邻区间422、单值与区间模式互相拒绝、区间修订。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class IntervalServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-interval";

    @Autowired
    private RaceService raceService;

    private void createRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
    }

    private void registerRunner(String bib, Long finishTimeMs, int expectedVersion, String reqId) {
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, finishTimeMs, expectedVersion, reqId));
    }

    private void enableIntervalMode(int expectedVersion, String reqId) {
        ServiceResult result = raceService.enableIntervalMode(RACE,
                new EnableIntervalModeRequest(expectedVersion, "op-admin", reqId));
        assertThat(result.status()).isEqualTo(200);
    }

    private void configure(int expectedVersion, String reqId, String... codeThenPosition) {
        java.util.List<ConfigureCheckpointsRequest.CheckpointDefinition> defs =
                new java.util.ArrayList<>();
        for (int i = 0; i < codeThenPosition.length; i += 2) {
            defs.add(new ConfigureCheckpointsRequest.CheckpointDefinition(
                    codeThenPosition[i], Integer.parseInt(codeThenPosition[i + 1])));
        }
        ServiceResult result = raceService.configureCheckpoints(RACE,
                new ConfigureCheckpointsRequest(defs, expectedVersion, reqId));
        assertThat(result.status()).isEqualTo(201);
    }

    private IntervalTimingResponse submitInterval(
            String bib, String timingId, String code, long lower, long upper,
            int expectedVersion, String reqId) {
        ServiceResult result = raceService.submitInterval(RACE, bib,
                new SubmitIntervalRequest(
                        timingId, code, lower, upper, expectedVersion, "op-timing", reqId));
        assertThat(result.status()).isEqualTo(201);
        return (IntervalTimingResponse) result.body();
    }

    @Test
    void 合成样例主流程输出名次范围与原区间() {
        createRace();
        registerRunner("A", null, 1, "req-a");   // v2
        registerRunner("B", null, 2, "req-b");   // v3
        registerRunner("C", null, 3, "req-c");   // v4
        enableIntervalMode(4, "req-mode");       // v5

        submitInterval("A", "t-a-f", IntervalRules.FINISH_CODE, 100L, 110L, 5, "req-ta");
        submitInterval("B", "t-b-f", IntervalRules.FINISH_CODE, 105L, 115L, 6, "req-tb");
        submitInterval("C", "t-c-f", IntervalRules.FINISH_CODE, 120L, 130L, 7, "req-tc"); // v8

        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.timingMode()).isEqualTo(TimingMode.INTERVAL);
        assertThat(standing.version()).isEqualTo(8);
        // 展示顺序按参赛号；A、B 重叠均为 [1,2]，C 为 [3,3]，不输出固定名次
        assertThat(standing.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("A", "B", "C");
        assertThat(standing.entries()).extracting(ResultEntryResponse::status)
                .containsOnly(EntryStatus.RANKED);
        assertThat(standing.entries()).extracting(ResultEntryResponse::rank)
                .containsOnlyNulls();
        assertThat(standing.entries()).extracting(ResultEntryResponse::rankBest)
                .containsExactly(1, 1, 3);
        assertThat(standing.entries()).extracting(ResultEntryResponse::rankWorst)
                .containsExactly(2, 2, 3);
        assertThat(standing.entries().get(0).finishLowerMs()).isEqualTo(100L);
        assertThat(standing.entries().get(0).finishUpperMs()).isEqualTo(110L);
        assertThat(standing.entries().get(0).netLowerMs()).isEqualTo(100L);
        assertThat(standing.entries().get(0).netUpperMs()).isEqualTo(110L);

        RunnerIntervalsResponse aIntervals = raceService.getRunnerIntervals(RACE, "A");
        assertThat(aIntervals.points()).extracting(IntervalPassResponse::checkpointCode)
                .containsExactly(IntervalRules.FINISH_CODE);
        assertThat(aIntervals.points().get(0).lowerMs()).isEqualTo(100L);
        assertThat(aIntervals.points().get(0).timingId()).isEqualTo("t-a-f");
    }

    @Test
    void 启用模式要求尚无计时证据且不可重复启用() {
        createRace();
        registerRunner("a", 1_000L, 1, "req-a"); // 已有单值完赛耗时
        assertThatThrownBy(() -> enableIntervalMode(2, "req-mode"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("计时证据");
        // 失败不推进版本
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(2);

        // 修订掉单值耗时后仍不可启用（修订本身合法，但耗时仍在）
        raceService.reviseTime(RACE, new ReviseTimeRequest("a", 2_000L, 2, "req-rev")); // v3
        assertThatThrownBy(() -> enableIntervalMode(3, "req-mode-2"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 重复启用与单值写入区间模式均被409拒绝() {
        createRace();
        registerRunner("a", null, 1, "req-a");
        enableIntervalMode(2, "req-mode"); // v3
        // 重复启用 -> 409
        assertThatThrownBy(() -> enableIntervalMode(3, "req-mode-again"))
                .isInstanceOf(ConflictException.class);
        // 单值计时修订不能覆盖区间模式 -> 409
        assertThatThrownBy(() -> raceService.reviseTime(RACE,
                new ReviseTimeRequest("a", 5_000L, 3, "req-rev")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("区间");
        // 单值分段不能写入区间模式 -> 409
        assertThatThrownBy(() -> raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-x", "c1", 100L, 3, "req-tx")))
                .isInstanceOf(ConflictException.class);
        // 区间模式登记选手不得携带单值完赛耗时 -> 409
        assertThatThrownBy(() -> registerRunner("b", 9_000L, 3, "req-b"))
                .isInstanceOf(ConflictException.class);
        // 版本保持 3
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
    }

    @Test
    void 单值模式赛事拒绝区间写入() {
        createRace();
        registerRunner("a", null, 1, "req-a");
        assertThatThrownBy(() -> submitInterval("a", "t-a-f",
                IntervalRules.FINISH_CODE, 100L, 110L, 2, "req-ta"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("未启用区间计时模式");
        assertThatThrownBy(() -> raceService.reviseInterval(RACE, "a",
                new ReviseIntervalRequest(
                        IntervalRules.FINISH_CODE, 100L, 110L, 2, "op", "req-ra")))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.countIntervals(RACE)).isZero();
    }

    @Test
    void 非法区间400且不留半组证据() {
        createRace();
        registerRunner("a", null, 1, "req-a");
        enableIntervalMode(2, "req-mode"); // v3

        // 下界大于上界 -> 400
        assertThatThrownBy(() -> submitInterval("a", "t-bad",
                IntervalRules.FINISH_CODE, 200L, 100L, 3, "req-bad"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("下界");
        // 失败不留证据、不推进版本、不占 requestId
        assertThat(repository.countIntervals(RACE)).isZero();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
        assertThat(repository.findIdempotency("req-bad")).isEmpty();
    }

    @Test
    void 相邻区间不能保证严格递增返回422() {
        createRace();
        registerRunner("a", null, 1, "req-a");
        enableIntervalMode(2, "req-mode");      // v3
        configure(3, "req-cfg", "c1", "1", "c2", "2"); // v4

        submitInterval("a", "t-c1", "c1", 100L, 110L, 4, "req-t1"); // v5
        // c2 下界 105 不大于 c1 上界 110 -> 422
        assertThatThrownBy(() -> submitInterval("a", "t-c2-bad", "c2", 105L, 120L, 5, "req-t2b"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("严格大于");
        // c2 下界等于 c1 上界（端点相等）-> 422
        assertThatThrownBy(() -> submitInterval("a", "t-c2-eq", "c2", 110L, 120L, 5, "req-t2e"))
                .isInstanceOf(UnprocessableEntityException.class);
        submitInterval("a", "t-c2", "c2", 111L, 120L, 5, "req-t2"); // v6
        // 完赛区间与 c2 重叠 -> 422
        assertThatThrownBy(() -> submitInterval("a", "t-f-bad",
                IntervalRules.FINISH_CODE, 115L, 130L, 6, "req-tfb"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("严格大于");
        // 失败不留半组证据：仍只有 c1、c2 两条，版本 6
        assertThat(repository.findIntervalsForRunner(RACE, "a")).hasSize(2);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);

        submitInterval("a", "t-f", IntervalRules.FINISH_CODE, 121L, 130L, 6, "req-tf"); // v7
        RunnerIntervalsResponse intervals = raceService.getRunnerIntervals(RACE, "a");
        assertThat(intervals.points()).extracting(IntervalPassResponse::checkpointCode)
                .containsExactly("c1", "c2", IntervalRules.FINISH_CODE);
        assertThat(intervals.points()).extracting(IntervalPassResponse::position)
                .containsExactly(1, 2, 3);
    }

    @Test
    void 区间修订共享赛事版本并受相邻约束() {
        createRace();
        registerRunner("a", null, 1, "req-a");
        enableIntervalMode(2, "req-mode");           // v3
        configure(3, "req-cfg", "c1", "1", "c2", "2"); // v4
        submitInterval("a", "t-c1", "c1", 100L, 110L, 4, "req-t1"); // v5
        submitInterval("a", "t-c2", "c2", 111L, 120L, 5, "req-t2"); // v6

        // 修订 c1 与 c2 重叠 -> 422
        assertThatThrownBy(() -> raceService.reviseInterval(RACE, "a",
                new ReviseIntervalRequest("c1", 105L, 112L, 6, "op", "req-r-bad")))
                .isInstanceOf(UnprocessableEntityException.class);
        // 修订不存在的计时点 -> 404
        assertThatThrownBy(() -> raceService.reviseInterval(RACE, "a",
                new ReviseIntervalRequest(IntervalRules.FINISH_CODE, 121L, 130L, 6, "op", "req-r-404")))
                .isInstanceOf(NotFoundException.class);
        // 非法区间 -> 400
        assertThatThrownBy(() -> raceService.reviseInterval(RACE, "a",
                new ReviseIntervalRequest("c1", 200L, 100L, 6, "op", "req-r-400")))
                .isInstanceOf(BadRequestException.class);
        // 版本冲突 -> 409
        assertThatThrownBy(() -> raceService.reviseInterval(RACE, "a",
                new ReviseIntervalRequest("c1", 100L, 109L, 999, "op", "req-r-409")))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);

        // 合法修订成功并推进版本
        ServiceResult revised = raceService.reviseInterval(RACE, "a",
                new ReviseIntervalRequest("c1", 100L, 109L, 6, "op-revise", "req-r-ok"));
        assertThat(revised.status()).isEqualTo(200);
        IntervalTimingResponse body = (IntervalTimingResponse) revised.body();
        assertThat(body.upperMs()).isEqualTo(109L);
        assertThat(body.operator()).isEqualTo("op-revise");
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(7);
    }
}
