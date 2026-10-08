package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.EnableIntervalModeRequest;
import com.example.starter.race.api.IntervalPassResponse;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.ReviseIntervalRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.RunnerIntervalsResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitIntervalRequest;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.IntervalRules;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.TimingMode;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 区间计时模式的幂等、并发与封榜冻结 H2 测试：
 * requestId/timingId 双重幂等、失败不占键、写竞争串行化、封榜固化区间与名次范围。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class IntervalIdempotencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-interval-idem";

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

    private void registerRunner(String bib, int expectedVersion, String reqId) {
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, null, expectedVersion, reqId));
    }

    private void enableIntervalMode(int expectedVersion, String reqId) {
        raceService.enableIntervalMode(RACE,
                new EnableIntervalModeRequest(expectedVersion, "op-admin", reqId));
    }

    private ServiceResult submitInterval(
            String bib, String timingId, long lower, long upper,
            int expectedVersion, String reqId) {
        return raceService.submitInterval(RACE, bib,
                new SubmitIntervalRequest(timingId, IntervalRules.FINISH_CODE,
                        lower, upper, expectedVersion, "op-timing", reqId));
    }

    @Test
    void requestId同参重放异参409且timingId双重幂等() {
        createRace();
        registerRunner("a", 1, "req-a");
        enableIntervalMode(2, "req-mode"); // v3

        SubmitIntervalRequest req = new SubmitIntervalRequest(
                "t-f", IntervalRules.FINISH_CODE, 100L, 110L, 3, "op-timing", "req-sub");
        ServiceResult first = raceService.submitInterval(RACE, "a", req);
        ServiceResult replay = raceService.submitInterval(RACE, "a", req);
        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        // 重放不推进版本（仍为 v4）
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);

        // 同 requestId 异参 -> 409
        assertThatThrownBy(() -> raceService.submitInterval(RACE, "a",
                new SubmitIntervalRequest("t-other", IntervalRules.FINISH_CODE,
                        100L, 110L, 3, "op-timing", "req-sub")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");

        // 同 timingId 异参（新 requestId）-> 409
        assertThatThrownBy(() -> raceService.submitInterval(RACE, "a",
                new SubmitIntervalRequest("t-f", IntervalRules.FINISH_CODE,
                        100L, 120L, 4, "op-timing", "req-sub-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("timingId");

        // 同 timingId 同参（新 requestId）：重放原结果，不新增记录不推进版本
        ServiceResult timingReplay = raceService.submitInterval(RACE, "a",
                new SubmitIntervalRequest("t-f", IntervalRules.FINISH_CODE,
                        100L, 110L, 999, "op-timing", "req-sub-3"));
        assertThat(timingReplay.status()).isEqualTo(201);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
        assertThat(repository.findAllIntervals(RACE)).hasSize(1);
    }

    @Test
    void 业务失败不占requestId键且可同键重试() {
        createRace();
        registerRunner("a", 1, "req-a");
        enableIntervalMode(2, "req-mode"); // v3

        // 错误版本提交，业务失败回滚不占键
        assertThatThrownBy(() -> submitInterval("a", "t-f", 100L, 110L, 999, "req-t1"))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findIdempotency("req-t1")).isEmpty();
        // 同 requestId 用正确版本重试成功
        ServiceResult retry = submitInterval("a", "t-f", 100L, 110L, 3, "req-t1");
        assertThat(retry.status()).isEqualTo(201);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
    }

    @Test
    void 并发区间提交与封榜竞争仅一个成功() throws Exception {
        createRace();
        registerRunner("a", 1, "req-a");
        enableIntervalMode(2, "req-mode"); // 当前版本 3

        int threads = 8;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger submitOk =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger sealOk =
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
                        if (index % 2 == 0) {
                            submitInterval("a", "t-conc-" + index,
                                    100L + index * 10L, 105L + index * 10L,
                                    3, "req-conc-" + index);
                            submitOk.incrementAndGet();
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

        // 同一版本只允许一个写成功（要么一条区间，要么封榜）
        assertThat(submitOk.get() + sealOk.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(4);
        if (sealOk.get() == 1) {
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            assertThat(repository.findAllIntervals(RACE)).isEmpty();
        } else {
            assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
            assertThat(repository.findAllIntervals(RACE)).hasSize(1);
        }
    }

    @Test
    void 封榜冻结区间与名次范围且缺点沿用漏点查询() {
        createRace();
        registerRunner("A", 1, "req-a");
        registerRunner("B", 2, "req-b");   // v3
        registerRunner("miss", 3, "req-m"); // v4
        enableIntervalMode(4, "req-mode");  // v5
        raceService.configureCheckpoints(RACE,
                new com.example.starter.race.api.ConfigureCheckpointsRequest(
                        java.util.List.of(
                                new com.example.starter.race.api
                                        .ConfigureCheckpointsRequest.CheckpointDefinition("c1", 1)),
                        5, "req-cfg")); // v6

        // A、B 完整（c1 + 完赛）；miss 只有完赛区间，缺 c1
        raceService.submitInterval(RACE, "A", new SubmitIntervalRequest(
                "t-a-c1", "c1", 50L, 60L, 6, "op", "req-a-c1")); // v7
        raceService.submitInterval(RACE, "A", new SubmitIntervalRequest(
                "t-a-f", IntervalRules.FINISH_CODE, 100L, 110L, 7, "op", "req-a-f")); // v8
        raceService.submitInterval(RACE, "B", new SubmitIntervalRequest(
                "t-b-c1", "c1", 55L, 65L, 8, "op", "req-b-c1")); // v9
        raceService.submitInterval(RACE, "B", new SubmitIntervalRequest(
                "t-b-f", IntervalRules.FINISH_CODE, 120L, 130L, 9, "op", "req-b-f")); // v10
        raceService.submitInterval(RACE, "miss", new SubmitIntervalRequest(
                "t-m-f", IntervalRules.FINISH_CODE, 90L, 95L, 10, "op", "req-m-f")); // v11

        // A 加时 15ms：净区间 [115,125]，与 B [120,130] 重叠 -> 名次范围均 [1,2]
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "pen-a", "A", "ADD_TIME", 15L, 11, "req-pen")); // v12

        StandingResponse live = raceService.getResults(RACE);
        assertThat(live.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("A", "B", "miss");
        assertThat(live.entries().get(0).netLowerMs()).isEqualTo(115L);
        assertThat(live.entries().get(0).netUpperMs()).isEqualTo(125L);
        assertThat(live.entries().get(0).rankBest()).isEqualTo(1);
        assertThat(live.entries().get(0).rankWorst()).isEqualTo(2);
        assertThat(live.entries().get(1).rankBest()).isEqualTo(1);
        assertThat(live.entries().get(1).rankWorst()).isEqualTo(2);
        // 缺点不参与：无名次范围，状态 MISSING_CHECKPOINT
        assertThat(live.entries().get(2).status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(live.entries().get(2).rankBest()).isNull();
        assertThat(live.entries().get(2).missingCheckpoints()).containsExactly("c1");

        ServiceResult sealed = raceService.sealRace(RACE, new SealRaceRequest(12, "req-seal")); // v13
        assertThat(sealed.status()).isEqualTo(200);
        StandingResponse snapshot = (StandingResponse) sealed.body();
        assertThat(snapshot.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(snapshot.timingMode()).isEqualTo(TimingMode.INTERVAL);
        // 封榜冻结名次范围与净区间
        assertThat(snapshot.entries()).extracting(ResultEntryResponse::rankBest)
                .containsExactly(1, 1, null);
        assertThat(snapshot.entries()).extracting(ResultEntryResponse::rankWorst)
                .containsExactly(2, 2, null);
        assertThat(snapshot.entries().get(0).netLowerMs()).isEqualTo(115L);
        assertThat(snapshot.entries().get(0).finishLowerMs()).isEqualTo(100L);

        // 封榜后成绩与快照一致，区间明细来自快照
        StandingResponse postSeal = raceService.getResults(RACE);
        assertThat(json(postSeal)).isEqualTo(json(raceService.getSnapshot(RACE)));
        RunnerIntervalsResponse aIntervals = raceService.getRunnerIntervals(RACE, "A");
        assertThat(aIntervals.points()).extracting(IntervalPassResponse::checkpointCode)
                .containsExactly("c1", IntervalRules.FINISH_CODE);
        assertThat(aIntervals.points().get(1).lowerMs()).isEqualTo(100L);
        assertThat(aIntervals.points().get(1).upperMs()).isEqualTo(110L);

        // 漏点查询沿用原口径
        MissingCheckpointsResponse missing = raceService.getMissingCheckpoints(RACE);
        assertThat(missing.runners()).extracting(r -> r.bib())
                .containsExactly("A", "B", "miss");
        assertThat(missing.runners().get(2).missingCheckpoints()).containsExactly("c1");

        // 封榜后区间写入、区间修订、处罚撤销均 409
        assertThatThrownBy(() -> submitInterval("A", "t-late", 200L, 210L, 13, "req-late"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
        assertThatThrownBy(() -> raceService.reviseInterval(RACE, "A",
                new ReviseIntervalRequest(IntervalRules.FINISH_CODE, 100L, 111L, 13, "op", "req-rv")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.revokePenalty(RACE, "pen-a",
                new RevokePenaltyRequest(13, "req-revoke")))
                .isInstanceOf(ConflictException.class);
        // 冻结数据未变
        assertThat(raceService.getSnapshot(RACE).entries().get(0).netUpperMs()).isEqualTo(125L);
    }
}
