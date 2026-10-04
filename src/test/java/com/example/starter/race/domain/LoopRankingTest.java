package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResultCalculator#computeLoop} 的纯逻辑单元测试：
 * 绕圈赛事按末圈FINISH加生效处罚排名，漏圈/逆序标记 LAP_INCOMPLETE。
 */
class LoopRankingTest {

    private record Runner(String bib, Long finishTimeMs) implements ResultCalculator.RunnerView {
    }

    private record Penalty(String bib, PenaltyType type, Long amountMs, boolean revoked)
            implements ResultCalculator.PenaltyView {
    }

    private record Pass(String bib, int lapNo, LapPoint point, long elapsedMillis)
            implements LapPassView {
    }

    private static List<Pass> fullLaps(String bib, long lap1Finish, long lap2Finish) {
        return List.of(
                new Pass(bib, 1, LapPoint.A, lap1Finish - 20_000),
                new Pass(bib, 1, LapPoint.B, lap1Finish - 10_000),
                new Pass(bib, 1, LapPoint.FINISH, lap1Finish),
                new Pass(bib, 2, LapPoint.A, lap2Finish - 20_000),
                new Pass(bib, 2, LapPoint.B, lap2Finish - 10_000),
                new Pass(bib, 2, LapPoint.FINISH, lap2Finish));
    }

    @Test
    void 两圈齐备按末圈FINISH加生效处罚排名() {
        List<ResultEntry> entries = ResultCalculator.computeLoop(
                List.of(new Runner("a", null), new Runner("b", null)),
                List.of(new Penalty("a", PenaltyType.ADD_TIME, 5_000L, false)),
                2,
                List.of(
                        // 乱序到达不影响计算
                        new Pass("a", 2, LapPoint.FINISH, 120_000),
                        new Pass("a", 1, LapPoint.A, 40_000),
                        new Pass("a", 1, LapPoint.B, 50_000),
                        new Pass("a", 1, LapPoint.FINISH, 60_000),
                        new Pass("a", 2, LapPoint.A, 70_000),
                        new Pass("a", 2, LapPoint.B, 80_000),
                        new Pass("b", 1, LapPoint.A, 30_000),
                        new Pass("b", 1, LapPoint.B, 45_000),
                        new Pass("b", 1, LapPoint.FINISH, 55_000),
                        new Pass("b", 2, LapPoint.A, 65_000),
                        new Pass("b", 2, LapPoint.B, 75_000),
                        new Pass("b", 2, LapPoint.FINISH, 110_000)));

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("b", "a");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2);
        ResultEntry a = entries.get(1);
        assertThat(a.status()).isEqualTo(EntryStatus.RANKED);
        assertThat(a.finishTimeMs()).isEqualTo(120_000L);
        assertThat(a.penaltyMs()).isEqualTo(5_000L);
        assertThat(a.totalTimeMs()).isEqualTo(125_000L);
        assertThat(a.checkpointCount()).isEqualTo(6);
        assertThat(a.coveredCheckpointCount()).isEqualTo(6);
        assertThat(a.missingCheckpoints()).isEmpty();
    }

    @Test
    void 漏圈选手标记LAP_INCOMPLETE并列出缺失点() {
        List<Pass> passes = new java.util.ArrayList<>(fullLaps("a", 60_000, 120_000));
        passes.removeIf(pass -> pass.lapNo() == 1 && pass.point() == LapPoint.B);

        List<ResultEntry> entries = ResultCalculator.computeLoop(
                List.of(new Runner("a", null)), List.of(), 2, passes);

        assertThat(entries).hasSize(1);
        ResultEntry entry = entries.getFirst();
        assertThat(entry.status()).isEqualTo(EntryStatus.LAP_INCOMPLETE);
        assertThat(entry.rank()).isNull();
        assertThat(entry.totalTimeMs()).isNull();
        assertThat(entry.coveredCheckpointCount()).isEqualTo(5);
        assertThat(entry.missingCheckpoints()).containsExactly("1:B");
    }

    @Test
    void 逆序记录标记LAP_INCOMPLETE() {
        List<ResultEntry> entries = ResultCalculator.computeLoop(
                List.of(new Runner("a", null)),
                List.of(),
                2,
                List.of(
                        new Pass("a", 1, LapPoint.A, 50_000),
                        new Pass("a", 1, LapPoint.B, 40_000),
                        new Pass("a", 1, LapPoint.FINISH, 60_000),
                        new Pass("a", 2, LapPoint.A, 70_000),
                        new Pass("a", 2, LapPoint.B, 80_000),
                        new Pass("a", 2, LapPoint.FINISH, 90_000)));

        assertThat(entries.getFirst().status()).isEqualTo(EntryStatus.LAP_INCOMPLETE);
    }

    @Test
    void 无圈次记录为UNTIMED取消资格优先且已撤销处罚不计() {
        List<ResultEntry> entries = ResultCalculator.computeLoop(
                List.of(new Runner("a", null), new Runner("b", null), new Runner("c", null)),
                List.of(
                        new Penalty("b", PenaltyType.DISQUALIFY, null, false),
                        new Penalty("c", PenaltyType.ADD_TIME, 5_000L, true)),
                2,
                fullLaps("c", 60_000, 120_000));

        assertThat(entries).extracting(ResultEntry::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.UNTIMED, EntryStatus.DISQUALIFIED);
        assertThat(entries).extracting(ResultEntry::bib)
                .containsExactly("c", "a", "b");
        // 已撤销的加时不计入
        assertThat(entries.getFirst().totalTimeMs()).isEqualTo(120_000L);
    }

    @Test
    void 并列末圈FINISH同名次并跳号() {
        List<Pass> passes = new java.util.ArrayList<>(fullLaps("a", 60_000, 120_000));
        passes.addAll(fullLaps("b", 60_000, 120_000));
        passes.addAll(fullLaps("c", 60_000, 130_000));

        List<ResultEntry> entries = ResultCalculator.computeLoop(
                List.of(new Runner("a", null), new Runner("b", null), new Runner("c", null)),
                List.of(), 2, passes);

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("a", "b", "c");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 1, 3);
    }
}