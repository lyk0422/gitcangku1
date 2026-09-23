package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link NetCalculator} 纯逻辑单元测试：覆盖受影响补偿、已过检查点补偿0、
 * 多事件累计、未起跑/未计时口径以及净不变量失败分支。
 */
class NetCalculatorTest {

    /** 轻量分段视图桩。 */
    private record T(String timingId, String bib, String code, int position, long elapsed)
            implements TimingView {
        @Override
        public String timingId() {
            return timingId;
        }

        @Override
        public String bib() {
            return bib;
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
            return elapsed;
        }
    }

    /** 轻量已恢复中止事件视图桩。 */
    private record S(String key, String code, int pos, long start, long resume)
            implements SuspensionView {
        @Override
        public String eventKey() {
            return key;
        }

        @Override
        public String checkpointKey() {
            return code;
        }

        @Override
        public int checkpointPosition() {
            return pos;
        }

        @Override
        public long startElapsedMs() {
            return start;
        }

        @Override
        public boolean resumed() {
            return true;
        }

        @Override
        public Long resumeElapsedMs() {
            return resume;
        }

        @Override
        public Long durationMs() {
            return resume - start;
        }
    }

    @Test
    void 未通过起始检查点的已起跑者仅对恢复后的分段与完赛补偿() {
        // 起始检查点位置3；选手只到位置1（中止开始前），位置2/3在恢复后
        List<TimingView> timings = List.of(
                new T("t1", "a", "c1", 1, 500),
                new T("t2", "a", "c2", 2, 2_500),
                new T("t3", "a", "c3", 3, 3_500));
        SuspensionView suspension = new S("ev", "c3", 3, 1_000, 2_000);

        NetCalculator.NetRunnerResult result =
                NetCalculator.computeRunner(4_000L, timings, List.of(suspension));

        assertThat(result.checkpoints()).extracting(NetCalculator.NetCheckpoint::netElapsedMs)
                .containsExactly(500L, 1_500L, 2_500L);
        assertThat(result.checkpoints()).extracting(NetCalculator.NetCheckpoint::compensationMs)
                .containsExactly(0L, 1_000L, 1_000L);
        // 完赛在恢复之后：净完赛=4000-1000=3000
        assertThat(result.netFinishTimeMs()).isEqualTo(3_000L);
        assertThat(result.finishCompensationMs()).isEqualTo(1_000L);
    }

    @Test
    void 中止开始前已通过起始检查点者补偿0() {
        // 位置3的记录 elapsed=800 < start=1000，即已通过起始检查点 c3
        List<TimingView> timings = List.of(
                new T("t1", "a", "c1", 1, 600),
                new T("t2", "a", "c3", 3, 800));
        SuspensionView suspension = new S("ev", "c3", 3, 1_000, 2_000);

        NetCalculator.NetRunnerResult result =
                NetCalculator.computeRunner(4_000L, timings, List.of(suspension));

        assertThat(result.checkpoints()).extracting(NetCalculator.NetCheckpoint::compensationMs)
                .containsOnly(0L);
        assertThat(result.netFinishTimeMs()).isEqualTo(4_000L);
        assertThat(result.finishCompensationMs()).isZero();
    }

    @Test
    void 未起跑者与未计时者不补偿() {
        SuspensionView suspension = new S("ev", "c1", 1, 1_000, 2_000);
        // 无任何分段：未起跑
        NetCalculator.NetRunnerResult notStarted =
                NetCalculator.computeRunner(4_000L, List.of(), List.of(suspension));
        assertThat(notStarted.checkpoints()).isEmpty();
        assertThat(notStarted.netFinishTimeMs()).isEqualTo(4_000L);
        assertThat(notStarted.finishCompensationMs()).isZero();

        // 有分段但无完赛耗时
        List<TimingView> timings = List.of(new T("t1", "u", "c2", 2, 2_500));
        NetCalculator.NetRunnerResult untimed =
                NetCalculator.computeRunner(null, timings, List.of(suspension));
        assertThat(untimed.netFinishTimeMs()).isNull();
        assertThat(untimed.checkpoints()).extracting(NetCalculator.NetCheckpoint::netElapsedMs)
                .containsExactly(1_500L);
    }

    @Test
    void 多个不重叠事件按区间累计补偿() {
        // 事件1 [1000,2000) D=1000，起始检查点位置4；事件2 [3000,4500) D=1500，起始检查点位置5
        List<TimingView> timings = List.of(
                new T("t1", "a", "c1", 1, 500),    // 两窗口前
                new T("t2", "a", "c2", 2, 2_500),  // 事件1后、事件2前
                new T("t3", "a", "c3", 3, 5_000)); // 事件2后
        List<SuspensionView> suspensions = List.of(
                new S("e1", "c4", 4, 1_000, 2_000),
                new S("e2", "c5", 5, 3_000, 4_500));

        NetCalculator.NetRunnerResult result =
                NetCalculator.computeRunner(6_000L, timings, suspensions);

        assertThat(result.checkpoints()).extracting(NetCalculator.NetCheckpoint::netElapsedMs)
                .containsExactly(500L, 1_500L, 2_500L);
        assertThat(result.checkpoints()).extracting(NetCalculator.NetCheckpoint::compensationMs)
                .containsExactly(0L, 1_000L, 2_500L);
        // 完赛在事件2恢复之后：累计补偿 1000+1500=2500
        assertThat(result.netFinishTimeMs()).isEqualTo(3_500L);
        assertThat(result.finishCompensationMs()).isEqualTo(2_500L);
    }

    @Test
    void 净分段不再严格递增时抛出且调用方据以回滚() {
        // 窗口内记录(elapsed1500)与恢复后记录(原始2500-补偿1000=1500)净值相等 -> 非严格递增
        List<TimingView> timings = List.of(
                new T("t1", "a", "c1", 1, 1_500),
                new T("t2", "a", "c2", 2, 2_500));
        SuspensionView suspension = new S("ev", "c3", 3, 1_000, 2_000);

        assertThatThrownBy(() -> NetCalculator.computeRunner(3_000L, timings, List.of(suspension)))
                .isInstanceOf(NetTimeInvalidException.class)
                .hasMessageContaining("严格递增");
    }

    @Test
    void 净分段不小于净完赛时抛出() {
        // 位置2在恢复后净值=1500，净完赛=3000-1000=2000（合法）；构造净值反超：完赛恰在恢复边界
        // c2 原始2500-补偿1000=1500；完赛2500-补偿1000=1500 -> 净分段不小于净完赛
        List<TimingView> timings = List.of(new T("t2", "a", "c2", 2, 2_500));
        SuspensionView suspension = new S("ev", "c3", 3, 1_000, 2_000);
        // 完赛必须严格大于分段原始值，取2500会被提交层拦截，这里仅直接验证纯逻辑不变量
        assertThatThrownBy(() -> NetCalculator.computeRunner(2_500L, timings, List.of(suspension)))
                .isInstanceOf(NetTimeInvalidException.class)
                .hasMessageContaining("净完赛");
    }
}
