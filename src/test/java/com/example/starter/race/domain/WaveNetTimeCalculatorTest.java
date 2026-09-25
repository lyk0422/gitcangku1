package com.example.starter.race.domain;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分批起跑净计时的纯逻辑测试：波次偏移换算、按净计时排序、
 * 并列稳定裁决、净计时为负标记 INVALID_WAVE 且保留原始计时、无波次选手沿用枪声计时。
 */
class WaveNetTimeCalculatorTest {

    private static final long BASE = 1_700_000_000_000L;

    private record Runner(String bib, Long finishTimeMs) implements ResultCalculator.RunnerView {
    }

    private record Wave(String waveKey, long startAt) implements ResultCalculator.WaveView {
    }

    private List<ResultEntry> compute(
            List<Runner> runners, Map<String, Wave> waveByBib) {
        return ResultCalculator.compute(runners, List.of(), List.of(), List.of(),
                waveByBib, BASE);
    }

    @Test
    void 波次选手按净计时排名而无波次选手沿用枪声计时() {
        // 基准起跑 BASE；wave-a 晚 60000ms 起跑；a 枪声 100000 -> 净 40000 排第1
        // b 无波次，枪声 60000 -> 净 60000 排第2；c 枪声 90000 -> 排第3
        List<Runner> runners = List.of(
                new Runner("a", 100_000L),
                new Runner("b", 60_000L),
                new Runner("c", 90_000L));
        Map<String, Wave> waves = Map.of("a", new Wave("wave-a", BASE + 60_000L));

        List<ResultEntry> entries = compute(runners, waves);

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("a", "b", "c");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2, 3);
        ResultEntry a = entries.get(0);
        assertThat(a.gunTimeMs()).isEqualTo(100_000L);
        assertThat(a.netTimeMs()).isEqualTo(40_000L);
        assertThat(a.waveKey()).isEqualTo("wave-a");
        ResultEntry b = entries.get(1);
        assertThat(b.netTimeMs()).isEqualTo(60_000L);
        assertThat(b.gunTimeMs()).isEqualTo(60_000L);
        assertThat(b.waveKey()).isNull();
    }

    @Test
    void 同净计时同名次并跳号且并列按参赛号字典序() {
        // a 波次晚 1000 起跑，枪声 6000 -> 净 5000；b 无波次枪声 5000，并列第1
        List<Runner> runners = List.of(new Runner("b", 5_000L), new Runner("a", 6_000L));
        Map<String, Wave> waves = Map.of("a", new Wave("w", BASE + 1_000L));

        List<ResultEntry> entries = compute(runners, waves);

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("a", "b");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 1);
        assertThat(entries).extracting(ResultEntry::netTimeMs).containsExactly(5_000L, 5_000L);
    }

    @Test
    void 净计时为负标记INVALID_WAVE不排名但保留原始计时() {
        // a 波次晚 100000 起跑，但枪声仅 50000 -> 净 -50000
        List<Runner> runners = List.of(
                new Runner("a", 50_000L),
                new Runner("b", 60_000L));
        Map<String, Wave> waves = Map.of("a", new Wave("late", BASE + 100_000L));

        List<ResultEntry> entries = compute(runners, waves);

        // b 正常排名第1；a 不排名列于其后
        assertThat(entries).extracting(ResultEntry::bib).containsExactly("b", "a");
        ResultEntry b = entries.get(0);
        assertThat(b.status()).isEqualTo(EntryStatus.RANKED);
        assertThat(b.rank()).isEqualTo(1);
        ResultEntry a = entries.get(1);
        assertThat(a.status()).isEqualTo(EntryStatus.INVALID_WAVE);
        assertThat(a.rank()).isNull();
        assertThat(a.netTimeMs()).isNull();
        assertThat(a.invalidReason()).isEqualTo("INVALID_WAVE");
        // 原始计时与枪声计时保留
        assertThat(a.finishTimeMs()).isEqualTo(50_000L);
        assertThat(a.gunTimeMs()).isEqualTo(50_000L);
        assertThat(a.waveKey()).isEqualTo("late");
    }

    @Test
    void 零净计时仍合法参与排名() {
        List<Runner> runners = List.of(new Runner("a", 30_000L));
        Map<String, Wave> waves = Map.of("a", new Wave("w", BASE + 30_000L));

        List<ResultEntry> entries = compute(runners, waves);

        assertThat(entries.getFirst().status()).isEqualTo(EntryStatus.RANKED);
        assertThat(entries.getFirst().netTimeMs()).isZero();
        assertThat(entries.getFirst().invalidReason()).isNull();
    }
}
