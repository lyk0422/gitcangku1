package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link NetTimeCalculator} 的纯逻辑测试：受影响判定、窗口拒绝、
 * 净不变量（严格递增/非负/小于净完赛）与多事件叠加。
 */
class NetTimeCalculatorTest {

    private record Runner(String bib, Long finishTimeMs)
            implements ResultCalculator.RunnerView {
    }

    private record Timing(
            String timingId, String bib, String checkpointCode, int position, long elapsedMillis)
            implements TimingView {
    }

    private record Event(
            String eventKey, int checkpointPosition, long startElapsedMs, long resumeElapsedMs)
            implements NetTimeCalculator.SuspensionEventView {
    }

    private static Timing timing(String bib, String code, int position, long elapsed) {
        return new Timing("t-" + bib + "-" + code, bib, code, position, elapsed);
    }

    @Test
    void 无中止事件时净值等于原始值且补偿为空() {
        Map<String, NetTimeCalculator.NetRunnerResult> nets = NetTimeCalculator.computeAll(
                List.of(),
                List.of(new Runner("a", 10_000L)),
                List.of(timing("a", "k1", 1, 3_000L)));

        NetTimeCalculator.NetRunnerResult net = nets.get("a");
        assertThat(net.netFinishMs()).isEqualTo(10_000L);
        assertThat(net.netElapsedByCheckpoint()).containsEntry("k1", 3_000L);
        assertThat(net.compensations()).isEmpty();
    }

    @Test
    void 已通过受影响起始检查点者补偿0且不扣减() {
        // 中止 [6000,10000)，受影响起始检查点 k1(pos1)；a 在中止前已通过 k1
        Event event = new Event("e1", 1, 6_000L, 10_000L);
        Map<String, NetTimeCalculator.NetRunnerResult> nets = NetTimeCalculator.computeAll(
                List.of(event),
                List.of(new Runner("a", 20_000L)),
                List.of(timing("a", "k1", 1, 3_000L), timing("a", "k2", 2, 12_000L)));

        NetTimeCalculator.NetRunnerResult net = nets.get("a");
        assertThat(net.compensations())
                .containsExactly(new NetTimeCalculator.CompensationEntry("e1", false, 0L));
        assertThat(net.netFinishMs()).isEqualTo(20_000L);
        assertThat(net.netElapsedByCheckpoint())
                .containsEntry("k1", 3_000L)
                .containsEntry("k2", 12_000L);
    }

    @Test
    void 未通过起始检查点的已起跑者扣除恢复后分段与完赛() {
        Event event = new Event("e1", 1, 6_000L, 10_000L);
        Map<String, NetTimeCalculator.NetRunnerResult> nets = NetTimeCalculator.computeAll(
                List.of(event),
                List.of(new Runner("b", 20_000L)),
                List.of(timing("b", "k1", 1, 11_000L), timing("b", "k2", 2, 16_000L)));

        NetTimeCalculator.NetRunnerResult net = nets.get("b");
        assertThat(net.compensations())
                .containsExactly(new NetTimeCalculator.CompensationEntry("e1", true, 4_000L));
        assertThat(net.netFinishMs()).isEqualTo(16_000L);
        assertThat(net.netElapsedByCheckpoint())
                .containsEntry("k1", 7_000L)
                .containsEntry("k2", 12_000L);
    }

    @Test
    void 未起跑与中止前已完赛者均不受影响() {
        Event event = new Event("e1", 1, 6_000L, 10_000L);
        Map<String, NetTimeCalculator.NetRunnerResult> nets = NetTimeCalculator.computeAll(
                List.of(event),
                List.of(new Runner("idle", null), new Runner("done", 5_000L)),
                List.of());

        assertThat(nets.get("idle").compensations())
                .containsExactly(new NetTimeCalculator.CompensationEntry("e1", false, 0L));
        assertThat(nets.get("idle").netFinishMs()).isNull();
        assertThat(nets.get("done").compensations())
                .containsExactly(new NetTimeCalculator.CompensationEntry("e1", false, 0L));
        assertThat(nets.get("done").netFinishMs()).isEqualTo(5_000L);
    }

    @Test
    void 记录落在中止窗口内抛出违例() {
        Event event = new Event("e1", 1, 6_000L, 10_000L);
        // 分段 7000 落在 [6000,10000) 内
        assertThatThrownBy(() -> NetTimeCalculator.computeAll(
                List.of(event),
                List.of(new Runner("a", 20_000L)),
                List.of(timing("a", "k1", 1, 7_000L))))
                .isInstanceOf(NetTimeCalculator.NetTimeViolationException.class)
                .hasMessageContaining("中止窗口");
    }

    @Test
    void 完赛记录落在中止窗口内抛出违例() {
        Event event = new Event("e1", 1, 6_000L, 10_000L);
        // 受影响选手完赛 8000 落在 [6000,10000) 内
        assertThatThrownBy(() -> NetTimeCalculator.computeAll(
                List.of(event),
                List.of(new Runner("a", 8_000L)),
                List.of(timing("a", "k1", 1, 7_500L))))
                .isInstanceOf(NetTimeCalculator.NetTimeViolationException.class)
                .hasMessageContaining("中止窗口");
    }

    @Test
    void 净分段不严格递增或不小于净完赛抛出违例() {
        // 原始输入本身乱序（绕过提交期校验的防御性检查）
        assertThatThrownBy(() -> NetTimeCalculator.computeAll(
                List.of(),
                List.of(new Runner("a", 10_000L)),
                List.of(timing("a", "k1", 1, 5_000L), timing("a", "k2", 2, 4_000L))))
                .isInstanceOf(NetTimeCalculator.NetTimeViolationException.class)
                .hasMessageContaining("严格递增");

        // 净分段不小于净完赛
        assertThatThrownBy(() -> NetTimeCalculator.computeAll(
                List.of(),
                List.of(new Runner("a", 4_000L)),
                List.of(timing("a", "k1", 1, 5_000L))))
                .isInstanceOf(NetTimeCalculator.NetTimeViolationException.class)
                .hasMessageContaining("不小于净完赛");
    }

    @Test
    void 多个不重叠中止事件基于前次净结果叠加扣减() {
        // E1 [1000,3000) 扣2000，E2 [5000,9000) 扣4000
        Event e1 = new Event("e1", 1, 1_000L, 3_000L);
        Event e2 = new Event("e2", 2, 5_000L, 9_000L);
        Map<String, NetTimeCalculator.NetRunnerResult> nets = NetTimeCalculator.computeAll(
                List.of(e2, e1), // 故意乱序传入，内部按开始点排序
                List.of(new Runner("y", 15_000L)),
                List.of(timing("y", "k1", 1, 3_500L), timing("y", "k2", 2, 11_000L)));

        NetTimeCalculator.NetRunnerResult net = nets.get("y");
        // E1：中止前无任何检查点 -> 受影响；k1 3500-2000=1500，k2 11000-2000=9000，完赛 13000
        // E2：净 k1=1500<5000 未过 k2 -> 受影响；k2 9000-4000=5000，完赛 13000-4000=9000
        assertThat(net.netElapsedByCheckpoint())
                .containsEntry("k1", 1_500L)
                .containsEntry("k2", 5_000L);
        assertThat(net.netFinishMs()).isEqualTo(9_000L);
        assertThat(net.compensations())
                .containsExactly(
                        new NetTimeCalculator.CompensationEntry("e1", true, 2_000L),
                        new NetTimeCalculator.CompensationEntry("e2", true, 4_000L));
    }

    @Test
    void 后一事件按前次净结果判定已通过起始检查点() {
        // E1 [1000,3000)；E2 起始检查点 k2(pos2)，开始点 5000
        Event e1 = new Event("e1", 1, 1_000L, 3_000L);
        Event e2 = new Event("e2", 2, 5_000L, 6_000L);
        Map<String, NetTimeCalculator.NetRunnerResult> nets = NetTimeCalculator.computeAll(
                List.of(e1, e2),
                List.of(new Runner("x", 20_000L)),
                // k2 原始 6500，E1 扣 2000 后净 4500 < 5000：E2 视为中止前已过 k2 -> 补偿0
                List.of(timing("x", "k1", 1, 3_500L), timing("x", "k2", 2, 6_500L)));

        NetTimeCalculator.NetRunnerResult net = nets.get("x");
        assertThat(net.compensations())
                .containsExactly(
                        new NetTimeCalculator.CompensationEntry("e1", true, 2_000L),
                        new NetTimeCalculator.CompensationEntry("e2", false, 0L));
        assertThat(net.netElapsedByCheckpoint())
                .containsEntry("k1", 1_500L)
                .containsEntry("k2", 4_500L);
        assertThat(net.netFinishMs()).isEqualTo(18_000L);
    }
}
