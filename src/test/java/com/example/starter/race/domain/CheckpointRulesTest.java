package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CheckpointRules} 纯逻辑单元测试：乱序相邻约束、重复检查点与完赛耗时边界。
 */
class CheckpointRulesTest {

    private record Timing(
            String timingId,
            String bib,
            String checkpointCode,
            int position,
            long elapsedMillis
    ) implements TimingView {
    }

    private static Timing timing(int position, long elapsedMillis) {
        return new Timing("t-" + position, "a", "cp" + position, position, elapsedMillis);
    }

    @Test
    void 无相邻记录时任何合法耗时都通过() {
        assertThat(CheckpointRules.validate(List.of(), 1, 100L, 1000L)).isNull();
    }

    @Test
    void 乱序到达时与前后相邻记录严格递增才合法() {
        // 已有 position1=100、position3=300，中间 position2 必须在 (100,300) 开区间内
        List<Timing> existing = List.of(timing(1, 100L), timing(3, 300L));

        assertThat(CheckpointRules.validate(existing, 2, 200L, 1000L)).isNull();
        // 等于前序耗时 -> 非严格递增，拒绝
        assertThat(CheckpointRules.validate(existing, 2, 100L, 1000L)).isNotNull();
        // 等于后序耗时 -> 拒绝
        assertThat(CheckpointRules.validate(existing, 2, 300L, 1000L)).isNotNull();
        // 小于前序 -> 拒绝
        assertThat(CheckpointRules.validate(existing, 2, 90L, 1000L)).isNotNull();
        // 大于后序 -> 拒绝
        assertThat(CheckpointRules.validate(existing, 2, 301L, 1000L)).isNotNull();
    }

    @Test
    void 后补首个检查点时必须小于最近后序() {
        List<Timing> existing = List.of(timing(2, 500L));
        assertThat(CheckpointRules.validate(existing, 1, 499L, 1000L)).isNull();
        assertThat(CheckpointRules.validate(existing, 1, 500L, 1000L)).isNotNull();
    }

    @Test
    void 后补末尾检查点时必须大于最近前序且小于完赛耗时() {
        List<Timing> existing = List.of(timing(1, 100L));
        assertThat(CheckpointRules.validate(existing, 3, 999L, 1000L)).isNull();
        assertThat(CheckpointRules.validate(existing, 3, 100L, 1000L)).isNotNull();
        // 等于完赛耗时也不允许（必须严格小于）
        assertThat(CheckpointRules.validate(existing, 3, 1000L, 1000L)).isNotNull();
    }

    @Test
    void 非紧邻的中间记录不参与相邻校验() {
        // 已有 position1 与 position4，插入 position3 只与 position1（最近前序）比较
        List<Timing> existing = List.of(timing(1, 100L), timing(4, 900L));
        assertThat(CheckpointRules.validate(existing, 3, 850L, 1000L)).isNull();
    }

    @Test
    void 同一检查点重复提交被拒绝() {
        List<Timing> existing = List.of(timing(2, 200L));
        assertThat(CheckpointRules.validate(existing, 2, 250L, 1000L))
                .contains("同一选手同一检查点");
    }

    @Test
    void 尚无完赛耗时或耗时越界被拒绝() {
        assertThat(CheckpointRules.validate(List.of(), 1, 100L, null)).isNotNull();
        assertThat(CheckpointRules.validate(List.of(), 1, 0L, 1000L)).isNotNull();
        assertThat(CheckpointRules.validate(List.of(), 1, 86_400_001L, 86_400_000L)).isNotNull();
        assertThat(CheckpointRules.validate(List.of(), 1, 500L, 499L)).isNotNull();
    }
}
