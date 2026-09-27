package com.example.starter.blind.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 扩容随机表生成器单元测试：容量合法、A/B 各半、序列只含集合内代码、偶数约束。
 */
class RandomTableGeneratorTest {

    private final RandomTableGenerator generator = new RandomTableGenerator();

    @Test
    void generateBalanced_evenCapacity_halfAHalfB() {
        for (int capacity : new int[]{2, 4, 6, 8, 64}) {
            List<String> sequence = generator.generateBalanced(capacity);
            assertEquals(capacity, sequence.size(), "序列长度应等于容量");
            assertEquals(capacity / 2, count(sequence, "A"), "A 应占一半: capacity=" + capacity);
            assertEquals(capacity / 2, count(sequence, "B"), "B 应占一半: capacity=" + capacity);
            assertTrue(sequence.stream().allMatch(Set.of("A", "B")::contains),
                    "序列只能包含处理代码集合 {A,B}");
        }
    }

    @Test
    void generateBalanced_rejectsZeroNegativeOrOdd() {
        assertThrows(IllegalArgumentException.class, () -> generator.generateBalanced(0));
        assertThrows(IllegalArgumentException.class, () -> generator.generateBalanced(-4));
        IllegalArgumentException odd = assertThrows(IllegalArgumentException.class,
                () -> generator.generateBalanced(5));
        assertTrue(odd.getMessage().contains("正偶数"));
    }

    @Test
    void treatmentCodesSnapshot_isSortedCommaJoined() {
        assertEquals("A,B", generator.treatmentCodesSnapshot());
    }

    private long count(List<String> sequence, String code) {
        return sequence.stream().filter(code::equals).count();
    }
}
