package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResultCalculator} 分批起跑净计时纯逻辑单元测试：
 * 波次毫秒差校正、无波次沿用枪声计时、负净计时 INVALID_WAVE 与同净计时稳定裁决。
 */
class WaveNetTimeCalculatorTest {

    private record Runner(String bib, Long finishTimeMs) implements ResultCalculator.RunnerView {
    }

    private record Assignment(String bib, String waveKey, long waveStartMs)
            implements WaveAssignmentView {
    }

    /** 基准起跑时刻：2026-01-01T00:00:00Z。 */
    private static final long BASE = 1_767_225_600_000L;

    @Test
    void 波次参赛者净计时等于枪声总耗时减波次相对基准毫秒差() {
        List<Runner> runners = List.of(
                new Runner("A", 3000L),
                new Runner("B", 3000L),
                new Runner("C", 3000L));
        // A 在基准后 1000ms 起跑；B 与基准同时刻；C 无波次沿用枪声计时。
        List<Assignment> waves = List.of(
                new Assignment("A", "w2", BASE + 1000L),
                new Assignment("B", "w1", BASE));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), List.of(), List.of(), waves, BASE);

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("A", "B", "C");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2, 2);
        // A：3000 - 1000 = 2000；B：3000 - 0 = 3000；C 无波次：3000
        assertThat(entries.get(0).netTimeMs()).isEqualTo(2000L);
        assertThat(entries.get(0).totalTimeMs()).isEqualTo(3000L);
        assertThat(entries.get(0).waveKey()).isEqualTo("w2");
        assertThat(entries.get(1).netTimeMs()).isEqualTo(3000L);
        assertThat(entries.get(1).waveKey()).isEqualTo("w1");
        assertThat(entries.get(2).netTimeMs()).isEqualTo(3000L);
        assertThat(entries.get(2).waveKey()).isNull();
    }

    @Test
    void 波次早于基准起跑时净计时为枪声计时加回提前量() {
        List<Runner> runners = List.of(new Runner("A", 3000L));
        List<Assignment> waves = List.of(new Assignment("A", "w0", BASE - 500L));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), List.of(), List.of(), waves, BASE);

        // 波次相对基准 -500：净计时 = 3000 - (-500) = 3500
        assertThat(entries.getFirst().netTimeMs()).isEqualTo(3500L);
        assertThat(entries.getFirst().status()).isEqualTo(EntryStatus.RANKED);
    }

    @Test
    void 负净计时标记INVALID_WAVE不排名但保留原始计时与波次归属() {
        List<Runner> runners = List.of(
                new Runner("good", 5000L),
                new Runner("bad", 1000L));
        List<Assignment> waves = List.of(
                new Assignment("good", "w1", BASE + 1000L),
                new Assignment("bad", "w2", BASE + 2000L));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), List.of(), List.of(), waves, BASE);

        // good 净计时 4000 排名第一；bad 净计时 1000-2000=-1000 为 INVALID_WAVE
        assertThat(entries).extracting(ResultEntry::bib).containsExactly("good", "bad");
        ResultEntry bad = entries.get(1);
        assertThat(bad.status()).isEqualTo(EntryStatus.INVALID_WAVE);
        assertThat(bad.rank()).isNull();
        assertThat(bad.netTimeMs()).isNull();
        assertThat(bad.totalTimeMs()).isNull();
        assertThat(bad.finishTimeMs()).isEqualTo(1000L);
        assertThat(bad.waveKey()).isEqualTo("w2");
        assertThat(bad.waveStartMs()).isEqualTo(BASE + 2000L);
        assertThat(bad.baseStartMs()).isEqualTo(BASE);
        assertThat(bad.invalidReason()).isEqualTo("INVALID_WAVE");
    }

    @Test
    void 同净计时并列同名次并按参赛号字典序稳定裁决() {
        List<Runner> runners = List.of(
                new Runner("b2", 3000L),
                new Runner("b1", 2000L),
                new Runner("b3", 4000L));
        // b1 晚起跑 1000 → 净 1000；b2 同时刻起跑 → 净 3000；b3 晚起跑 2000 → 净 2000... 调整成并列：
        // b1 净 2000（2000-0），b3 净 2000（4000-2000）
        List<Assignment> waves = List.of(
                new Assignment("b1", "w1", BASE),
                new Assignment("b3", "w2", BASE + 2000L));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), List.of(), List.of(), waves, BASE);

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("b1", "b3", "b2");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 1, 3);
        assertThat(entries.get(0).netTimeMs()).isEqualTo(2000L);
        assertThat(entries.get(1).netTimeMs()).isEqualTo(2000L);
        assertThat(entries.get(2).netTimeMs()).isEqualTo(3000L);
    }

    @Test
    void 加时处罚计入枪声总耗时后再做波次净计时校正() {
        List<Runner> runners = List.of(new Runner("A", 3000L));
        List<Penalty> penalties = List.of(
                new Penalty("A", PenaltyType.ADD_TIME, 600L, false));
        List<Assignment> waves = List.of(new Assignment("A", "w1", BASE + 1000L));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, penalties, List.of(), List.of(), waves, BASE);

        // 枪声总耗时 3600，净计时 3600-1000=2600
        assertThat(entries.getFirst().totalTimeMs()).isEqualTo(3600L);
        assertThat(entries.getFirst().netTimeMs()).isEqualTo(2600L);
    }

    private record Penalty(String bib, PenaltyType type, Long amountMs, boolean revoked)
            implements ResultCalculator.PenaltyView {
    }
}
