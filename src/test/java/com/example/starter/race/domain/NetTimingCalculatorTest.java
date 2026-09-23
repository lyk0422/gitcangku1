package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 中止恢复净计时纯逻辑测试：补偿判定、窗口拒绝、多事件累积与净不变量。
 */
class NetTimingCalculatorTest {

    private static TimingView timing(String code, int position, long elapsedMillis) {
        return new TimingView() {
            @Override
            public String timingId() {
                return "t-" + code;
            }

            @Override
            public String bib() {
                return "a";
            }

            @Override
            public String checkpointCode() {
                return code;
            }

            @Override
            public int position() {
                return position;
            }

            @Override
            public long elapsedMillis() {
                return elapsedMillis;
            }
        };
    }

    @Test
    void 中止前已通过指定检查点补偿为零且净值不变() {
        NetTimingCalculator.SuspensionEvent event =
                new NetTimingCalculator.SuspensionEvent("e1", 2, 4000L, 6000L);
        NetTimingCalculator.NetOutcome outcome = NetTimingCalculator.computeRunnerNet(
                List.of(timing("p1", 1, 1000L), timing("p2", 2, 2000L)),
                10000L, List.of(event));

        assertThat(outcome.hasViolation()).isFalse();
        NetTimingCalculator.RunnerNet net = outcome.net();
        assertThat(net.compensations()).containsExactly(new NetTimingCalculator.Compensation(
                "e1", 0L, NetTimingCalculator.CompensationBasis.PASSED_CHECKPOINT));
        assertThat(net.netElapsedByCheckpoint())
                .containsEntry("p1", 1000L)
                .containsEntry("p2", 2000L);
        assertThat(net.netFinishTimeMs()).isEqualTo(10000L);
    }

    @Test
    void 已起跑未通过指定检查点者扣除中止时长() {
        NetTimingCalculator.SuspensionEvent event =
                new NetTimingCalculator.SuspensionEvent("e1", 2, 4000L, 6000L);
        NetTimingCalculator.NetOutcome outcome = NetTimingCalculator.computeRunnerNet(
                List.of(timing("p1", 1, 1000L), timing("p2", 2, 7000L)),
                10000L, List.of(event));

        assertThat(outcome.hasViolation()).isFalse();
        NetTimingCalculator.RunnerNet net = outcome.net();
        assertThat(net.compensations()).containsExactly(new NetTimingCalculator.Compensation(
                "e1", 2000L, NetTimingCalculator.CompensationBasis.AFFECTED));
        // 中止前的 p1 不变，恢复后的 p2 与完赛扣除 2000
        assertThat(net.netElapsedByCheckpoint())
                .containsEntry("p1", 1000L)
                .containsEntry("p2", 5000L);
        assertThat(net.netFinishTimeMs()).isEqualTo(8000L);
    }

    @Test
    void 中止前未起跑者补偿为零() {
        NetTimingCalculator.SuspensionEvent event =
                new NetTimingCalculator.SuspensionEvent("e1", 2, 4000L, 6000L);
        NetTimingCalculator.NetOutcome outcome = NetTimingCalculator.computeRunnerNet(
                List.of(), null, List.of(event));

        assertThat(outcome.hasViolation()).isFalse();
        assertThat(outcome.net().compensations()).containsExactly(
                new NetTimingCalculator.Compensation(
                        "e1", 0L, NetTimingCalculator.CompensationBasis.NOT_STARTED));
        assertThat(outcome.net().netFinishTimeMs()).isNull();
    }

    @Test
    void 受影响者分段落在窗口内为违规() {
        NetTimingCalculator.SuspensionEvent event =
                new NetTimingCalculator.SuspensionEvent("e1", 2, 4000L, 6000L);
        NetTimingCalculator.NetOutcome outcome = NetTimingCalculator.computeRunnerNet(
                List.of(timing("p1", 1, 1000L), timing("p2", 2, 5000L)),
                10000L, List.of(event));

        assertThat(outcome.hasViolation()).isTrue();
        assertThat(outcome.violation()).contains("中止窗口");
    }

    @Test
    void 受影响者完赛落在窗口内为违规() {
        NetTimingCalculator.SuspensionEvent event =
                new NetTimingCalculator.SuspensionEvent("e1", 2, 4000L, 6000L);
        NetTimingCalculator.NetOutcome outcome = NetTimingCalculator.computeRunnerNet(
                List.of(timing("p1", 1, 1000L)), 5000L, List.of(event));

        assertThat(outcome.hasViolation()).isTrue();
        assertThat(outcome.violation()).contains("中止窗口");
    }

    @Test
    void 多个不重叠事件按序累积且后一次基于前次净结果() {
        List<NetTimingCalculator.SuspensionEvent> events = List.of(
                new NetTimingCalculator.SuspensionEvent("e1", 2, 4000L, 6000L),
                new NetTimingCalculator.SuspensionEvent("e2", 3, 8000L, 9000L));
        NetTimingCalculator.NetOutcome outcome = NetTimingCalculator.computeRunnerNet(
                List.of(timing("p1", 1, 1000L), timing("p2", 2, 7000L),
                        timing("p3", 3, 11000L)),
                40000L, events);

        assertThat(outcome.hasViolation()).isFalse();
        NetTimingCalculator.RunnerNet net = outcome.net();
        assertThat(net.compensations()).containsExactly(
                new NetTimingCalculator.Compensation(
                        "e1", 2000L, NetTimingCalculator.CompensationBasis.AFFECTED),
                new NetTimingCalculator.Compensation(
                        "e2", 1000L, NetTimingCalculator.CompensationBasis.AFFECTED));
        // p2: 7000-2000=5000（第二事件窗口之前，不再扣）；p3: 11000-2000-1000=8000
        assertThat(net.netElapsedByCheckpoint())
                .containsEntry("p1", 1000L)
                .containsEntry("p2", 5000L)
                .containsEntry("p3", 8000L);
        assertThat(net.netFinishTimeMs()).isEqualTo(37000L);
    }

    @Test
    void 净分段不小于净完赛为违规() {
        NetTimingCalculator.NetOutcome outcome = NetTimingCalculator.computeRunnerNet(
                List.of(timing("p1", 1, 1000L)), 1000L, List.of());

        assertThat(outcome.hasViolation()).isTrue();
        assertThat(outcome.violation()).contains("净完赛");
    }

    @Test
    void 窗口校验只拒绝落在闭开区间内的记录() {
        List<NetTimingCalculator.SuspensionEvent> events = List.of(
                new NetTimingCalculator.SuspensionEvent("e1", 2, 4000L, 6000L));

        assertThat(NetTimingCalculator.windowViolation(3999L, events)).isNull();
        assertThat(NetTimingCalculator.windowViolation(4000L, events)).isNotNull();
        assertThat(NetTimingCalculator.windowViolation(5999L, events)).isNotNull();
        assertThat(NetTimingCalculator.windowViolation(6000L, events)).isNull();
    }
}
