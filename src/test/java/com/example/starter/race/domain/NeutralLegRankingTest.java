package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResultCalculator} 中立赛段模式单元测试：净竞技用时排名、
 * 扣时明细、漏点不估计扣除、普通赛事沿用原排名。
 */
class NeutralLegRankingTest {

    private record Runner(String bib, Long finishTimeMs) implements ResultCalculator.RunnerView {
    }

    private record Checkpoint(String checkpointCode, int position) implements CheckpointView {
    }

    private record Timing(
            String timingId,
            String bib,
            String checkpointCode,
            int position,
            long elapsedMillis
    ) implements TimingView {
    }

    private static final List<Checkpoint> CHECKPOINTS =
            List.of(new Checkpoint("cp1", 1), new Checkpoint("cp2", 2));

    private static Timing timing(String bib, String code, int position, long elapsed) {
        return new Timing("t-" + bib + "-" + code, bib, code, position, elapsed);
    }

    @Test
    void 中立区间扣时后按净竞技用时排名() {
        // a：完赛100，cp1=20、cp2=70，区间[1,2]扣50，净50
        // b：完赛90，cp1=10、cp2=15，区间[1,2]扣5，净85
        List<Runner> runners = List.of(new Runner("a", 100L), new Runner("b", 90L));
        List<Timing> timings = List.of(
                timing("a", "cp1", 1, 20L), timing("a", "cp2", 2, 70L),
                timing("b", "cp1", 1, 10L), timing("b", "cp2", 2, 15L));
        List<NeutralLegSpec> legs = List.of(new NeutralLegSpec(1, 2));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), CHECKPOINTS, timings, legs);

        // 原始耗时 b(90) 更短，但净竞技用时 a(50) < b(85)，a 排第一
        assertThat(entries).extracting(ResultEntry::bib).containsExactly("a", "b");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2);
        ResultEntry a = entries.get(0);
        assertThat(a.totalTimeMs()).isEqualTo(100L);
        assertThat(a.neutralDeductionMs()).isEqualTo(50L);
        assertThat(a.netTimeMs()).isEqualTo(50L);
        ResultEntry b = entries.get(1);
        assertThat(b.neutralDeductionMs()).isEqualTo(5L);
        assertThat(b.netTimeMs()).isEqualTo(85L);
    }

    @Test
    void 发枪零点区间与相接区间累计扣时() {
        // 区间[0,1]与[1,2]相接：扣时=cp1 + (cp2-cp1) = cp2
        List<Runner> runners = List.of(new Runner("a", 100L));
        List<Timing> timings = List.of(
                timing("a", "cp1", 1, 20L), timing("a", "cp2", 2, 70L));
        List<NeutralLegSpec> legs = List.of(
                new NeutralLegSpec(0, 1), new NeutralLegSpec(1, 2));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), CHECKPOINTS, timings, legs);

        assertThat(entries.get(0).neutralDeductionMs()).isEqualTo(70L);
        assertThat(entries.get(0).netTimeMs()).isEqualTo(30L);
    }

    @Test
    void 净值并列同名次且加时计入净值() {
        List<Runner> runners = List.of(new Runner("a", 100L), new Runner("b", 95L));
        List<Timing> timings = List.of(
                timing("a", "cp1", 1, 20L), timing("a", "cp2", 2, 70L),
                timing("b", "cp1", 1, 10L), timing("b", "cp2", 2, 60L));
        List<NeutralLegSpec> legs = List.of(new NeutralLegSpec(1, 2));
        // a 净 50；b 净 45 + 加时 5 = 50，并列第一
        List<ResultCalculator.PenaltyView> penalties = List.of(new ResultCalculator.PenaltyView() {
            @Override
            public String bib() {
                return "b";
            }

            @Override
            public PenaltyType type() {
                return PenaltyType.ADD_TIME;
            }

            @Override
            public Long amountMs() {
                return 5L;
            }

            @Override
            public boolean revoked() {
                return false;
            }
        });

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, penalties, CHECKPOINTS, timings, legs);

        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 1);
        assertThat(entries.get(1).netTimeMs()).isEqualTo(50L);
        assertThat(entries.get(1).penaltyMs()).isEqualTo(5L);
    }

    @Test
    void 中立端点漏点不排名且不做估计扣除() {
        // a 漏 cp2（中立终点），虽完赛耗时短也不排名，扣时为 null
        List<Runner> runners = List.of(new Runner("a", 50L), new Runner("b", 900L));
        List<Timing> timings = List.of(
                timing("a", "cp1", 1, 20L),
                timing("b", "cp1", 1, 100L), timing("b", "cp2", 2, 800L));
        List<NeutralLegSpec> legs = List.of(new NeutralLegSpec(1, 2));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), CHECKPOINTS, timings, legs);

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("b", "a");
        assertThat(entries.get(0).status()).isEqualTo(EntryStatus.RANKED);
        assertThat(entries.get(0).netTimeMs()).isEqualTo(200L);
        ResultEntry a = entries.get(1);
        assertThat(a.status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(a.neutralDeductionMs()).isNull();
        assertThat(a.netTimeMs()).isNull();
    }

    @Test
    void 未启用中立模式的普通赛事保留原排名且无净值字段() {
        List<Runner> runners = List.of(new Runner("a", 100L), new Runner("b", 90L));
        List<Timing> timings = List.of(
                timing("a", "cp1", 1, 20L), timing("a", "cp2", 2, 70L),
                timing("b", "cp1", 1, 10L), timing("b", "cp2", 2, 15L));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), CHECKPOINTS, timings, List.of());

        // 按原总耗时排名：b(90) 在 a(100) 前
        assertThat(entries).extracting(ResultEntry::bib).containsExactly("b", "a");
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.neutralDeductionMs()).isNull();
            assertThat(entry.netTimeMs()).isNull();
        });
    }
}
