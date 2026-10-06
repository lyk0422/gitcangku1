package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResultCalculator} COUNTBACK 模式单元测试：净总成绩相同用倒序段向量破同分，
 * 全部比较值相同才并列跳号，处罚只加总成绩不改分段，漏点仍不排名。
 */
class CountbackRankingTest {

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

    private record Penalty(
            String bib,
            PenaltyType type,
            Long amountMs,
            boolean revoked
    ) implements ResultCalculator.PenaltyView {
    }

    private static List<Checkpoint> oneCheckpoint() {
        return List.of(new Checkpoint("cp1", 1));
    }

    private static Timing timing(String bib, String code, int position, long elapsed) {
        return new Timing("t-" + bib + "-" + code, bib, code, position, elapsed);
    }

    private static List<ResultEntry> countback(
            List<Runner> runners,
            List<ResultCalculator.PenaltyView> penalties,
            List<Checkpoint> checkpoints,
            List<Timing> timings) {
        return ResultCalculator.compute(
                runners, penalties, checkpoints, timings, RankingMode.COUNTBACK);
    }

    @Test
    void 总成绩相同末段较小者优先_合成场景B第一() {
        // 合成场景：A 累计40、完赛100；B 累计60、完赛100；总成绩均100，
        // 最后段 A=100-40=60、B=100-60=40，B 第一。
        List<Runner> runners = List.of(new Runner("A", 100L), new Runner("B", 100L));
        List<Timing> timings = List.of(
                timing("A", "cp1", 1, 40L),
                timing("B", "cp1", 1, 60L));

        List<ResultEntry> entries =
                countback(runners, List.of(), oneCheckpoint(), timings);

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("B", "A");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2);
        assertThat(entries).extracting(ResultEntry::totalTimeMs).containsExactly(100L, 100L);
        // 倒序段向量：末段在前
        assertThat(entries.get(0).segmentMillisDesc()).containsExactly(40L, 60L);
        assertThat(entries.get(1).segmentMillisDesc()).containsExactly(60L, 40L);
    }

    @Test
    void 各段全同才并列并跳号_合成场景BC并列1A为3() {
        // C 与 B 各段都相同：B、C 并列第1，A 为第3（跳号，不以参赛号破并列）。
        List<Runner> runners = List.of(
                new Runner("A", 100L), new Runner("B", 100L), new Runner("C", 100L));
        List<Timing> timings = List.of(
                timing("A", "cp1", 1, 40L),
                timing("B", "cp1", 1, 60L),
                timing("C", "cp1", 1, 60L));

        List<ResultEntry> entries =
                countback(runners, List.of(), oneCheckpoint(), timings);

        // 并列者内部按参赛号字典序展示，但名次相同
        assertThat(entries).extracting(ResultEntry::bib).containsExactly("B", "C", "A");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 1, 3);
    }

    @Test
    void 末段相同则比较倒数第二段() {
        // 两个检查点：X 累计 30、60，Y 累计 40、60，完赛均 100。
        // 末段均 40；倒数第二段 X=60-30=30、Y=60-40=20，Y 较小优先。
        List<Checkpoint> checkpoints =
                List.of(new Checkpoint("cp1", 1), new Checkpoint("cp2", 2));
        List<Runner> runners = List.of(new Runner("X", 100L), new Runner("Y", 100L));
        List<Timing> timings = List.of(
                timing("X", "cp1", 1, 30L), timing("X", "cp2", 2, 60L),
                timing("Y", "cp1", 1, 40L), timing("Y", "cp2", 2, 60L));

        List<ResultEntry> entries = countback(runners, List.of(), checkpoints, timings);

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("Y", "X");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2);
        assertThat(entries.get(0).segmentMillisDesc()).containsExactly(40L, 20L, 40L);
        assertThat(entries.get(1).segmentMillisDesc()).containsExactly(40L, 30L, 30L);
    }

    @Test
    void 处罚只加总成绩不改分段() {
        // A 完赛 100 无处罚；B 完赛 90 + 加时 10，总成绩均 100。
        // B 的末段（90-50=40）小于 A 的末段（100-40=60），B 优先。
        List<Runner> runners = List.of(new Runner("A", 100L), new Runner("B", 90L));
        List<Timing> timings = List.of(
                timing("A", "cp1", 1, 40L),
                timing("B", "cp1", 1, 50L));
        List<ResultCalculator.PenaltyView> penalties = List.of(
                new Penalty("B", PenaltyType.ADD_TIME, 10L, false));

        List<ResultEntry> entries =
                countback(runners, penalties, oneCheckpoint(), timings);

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("B", "A");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2);
        assertThat(entries.get(0).totalTimeMs()).isEqualTo(100L);
        assertThat(entries.get(0).penaltyMs()).isEqualTo(10L);
        // 段向量不含处罚：末段=90-50=40
        assertThat(entries.get(0).segmentMillisDesc()).containsExactly(40L, 50L);
    }

    @Test
    void 总耗时不同则不比较分段直接按总耗时排序() {
        // A 总成绩 100，B 总成绩 101：即使 B 末段更短也排在后面。
        List<Runner> runners = List.of(new Runner("A", 100L), new Runner("B", 101L));
        List<Timing> timings = List.of(
                timing("A", "cp1", 1, 40L),
                timing("B", "cp1", 1, 90L));

        List<ResultEntry> entries =
                countback(runners, List.of(), oneCheckpoint(), timings);

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("A", "B");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2);
    }

    @Test
    void 漏点选手不排名且无段向量() {
        List<Checkpoint> checkpoints =
                List.of(new Checkpoint("cp1", 1), new Checkpoint("cp2", 2));
        List<Runner> runners = List.of(
                new Runner("full", 100L), new Runner("partial", 50L));
        List<Timing> timings = List.of(
                timing("full", "cp1", 1, 40L), timing("full", "cp2", 2, 60L),
                timing("partial", "cp1", 1, 10L));

        List<ResultEntry> entries = countback(runners, List.of(), checkpoints, timings);

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("full", "partial");
        assertThat(entries.get(0).status()).isEqualTo(EntryStatus.RANKED);
        assertThat(entries.get(0).segmentMillisDesc()).containsExactly(40L, 20L, 40L);
        assertThat(entries.get(1).status()).isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        assertThat(entries.get(1).rank()).isNull();
        assertThat(entries.get(1).segmentMillisDesc()).isEmpty();
    }

    @Test
    void 未配置检查点时段向量仅为完赛耗时() {
        // 无检查点：段向量=[完赛耗时]，总成绩相同则完赛耗时小者优先。
        List<Runner> runners = List.of(new Runner("A", 100L), new Runner("B", 90L));
        List<ResultCalculator.PenaltyView> penalties = List.of(
                new Penalty("B", PenaltyType.ADD_TIME, 10L, false));

        List<ResultEntry> entries =
                countback(runners, penalties, List.of(), List.of());

        assertThat(entries).extracting(ResultEntry::bib).containsExactly("B", "A");
        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 2);
        assertThat(entries.get(0).segmentMillisDesc()).containsExactly(90L);
        assertThat(entries.get(1).segmentMillisDesc()).containsExactly(100L);
    }

    @Test
    void TOTAL_TIME模式不计算段向量且同耗时即并列() {
        // 旧普通赛事算法不变：A 累计40、B 累计60、完赛均100，TOTAL_TIME 下并列第1。
        List<Runner> runners = List.of(new Runner("A", 100L), new Runner("B", 100L));
        List<Timing> timings = List.of(
                timing("A", "cp1", 1, 40L),
                timing("B", "cp1", 1, 60L));

        List<ResultEntry> entries = ResultCalculator.compute(
                runners, List.of(), oneCheckpoint(), timings, RankingMode.TOTAL_TIME);

        assertThat(entries).extracting(ResultEntry::rank).containsExactly(1, 1);
        assertThat(entries).extracting(ResultEntry::segmentMillisDesc)
                .containsOnly(List.of());
    }
}
