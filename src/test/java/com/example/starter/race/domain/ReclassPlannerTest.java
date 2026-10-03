package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReclassPlanner} 的纯逻辑单元测试：净迁移人数与容量边界。
 */
class ReclassPlannerTest {

    @Test
    void 迁入超出容量时返回违规组别() {
        String violation = ReclassPlanner.firstCapacityViolation(
                Map.of("A", 2, "B", 1),
                Map.of("A", 3, "B", 2),
                List.of(
                        new ReclassPlanner.Migration("A", "B"),
                        new ReclassPlanner.Migration("A", "B")));
        // B: 1 + 2 = 3 > 2
        assertThat(violation).isEqualTo("B");
    }

    @Test
    void 同批互换组别净迁移为零时不违规() {
        String violation = ReclassPlanner.firstCapacityViolation(
                Map.of("A", 1, "B", 1),
                Map.of("A", 1, "B", 1),
                List.of(
                        new ReclassPlanner.Migration("A", "B"),
                        new ReclassPlanner.Migration("B", "A")));
        assertThat(violation).isNull();
    }

    @Test
    void 未分组选手迁入只增加目标组别人数() {
        String violation = ReclassPlanner.firstCapacityViolation(
                Map.of("B", 1),
                Map.of("B", 2),
                List.of(new ReclassPlanner.Migration(null, "B")));
        assertThat(violation).isNull();

        String exceeded = ReclassPlanner.firstCapacityViolation(
                Map.of("B", 2),
                Map.of("B", 2),
                List.of(new ReclassPlanner.Migration(null, "B")));
        assertThat(exceeded).isEqualTo("B");
    }

    @Test
    void 恰好达到容量上限不违规() {
        String violation = ReclassPlanner.firstCapacityViolation(
                Map.of("A", 2, "B", 0),
                Map.of("A", 3, "B", 2),
                List.of(
                        new ReclassPlanner.Migration("A", "B"),
                        new ReclassPlanner.Migration("A", "B")));
        // A: 0, B: 2 = 容量上限
        assertThat(violation).isNull();
    }
}