package com.example.starter.blind;

import com.example.starter.blind.service.RandomTableDigester;
import com.example.starter.blind.service.RandomTableGenerator;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 随机表生成与摘要的纯单元测试：确定性可复算、每 4 条两 A 两 B、
 * 不同封存身份生成不同表、容量非法时失败；摘要稳定且随序列变化。
 */
class RandomTableTest {

    @Test
    void generate_isDeterministic_andBalancedPerGroup() {
        List<String> first = RandomTableGenerator.generate("EXP|1|1|SEAL-A", 8);
        List<String> second = RandomTableGenerator.generate("EXP|1|1|SEAL-A", 8);
        assertEquals(first, second, "相同身份与容量必须生成同一张表");
        assertEquals(8, first.size());
        for (int start = 0; start < 8; start += 4) {
            long a = first.subList(start, start + 4).stream().filter("A"::equals).count();
            assertEquals(2, a, "每个均衡小组必须两个 A 两个 B");
        }
        long totalA = first.stream().filter("A"::equals).count();
        assertEquals(4, totalA);
    }

    @Test
    void generate_differentSealIdentity_producesDifferentTable() {
        List<String> a = RandomTableGenerator.generate("EXP|1|1|SEAL-A", 4);
        List<String> b = RandomTableGenerator.generate("EXP|1|1|SEAL-B", 4);
        assertNotEquals(a, b, "不同封存编号应派生不同序列");
        assertEquals(new HashSet<>(a), Set.of("A", "B"));
    }

    @Test
    void generate_rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class,
                () -> RandomTableGenerator.generate("x", 0));
        assertThrows(IllegalArgumentException.class,
                () -> RandomTableGenerator.generate("x", 3));
        assertThrows(IllegalArgumentException.class,
                () -> RandomTableGenerator.generate("x", 6));
    }

    @Test
    void normalizeCodes_sortsDeduplicates_andRejectsUnsupported() {
        assertEquals(List.of("A", "B"),
                RandomTableGenerator.normalizeCodes(List.of("B", "A", "B")));
        assertThrows(IllegalArgumentException.class,
                () -> RandomTableGenerator.normalizeCodes(List.of("A", "C")));
        assertThrows(IllegalArgumentException.class,
                () -> RandomTableGenerator.normalizeCodes(List.of()));
    }

    @Test
    void digest_isStable_andChangesWithSequence() {
        String d1 = RandomTableDigester.digest("EXP", 1, 1, 4, "A,B", "AABB");
        String d1Again = RandomTableDigester.digest("EXP", 1, 1, 4, "A,B", "AABB");
        assertEquals(d1, d1Again);
        assertEquals(64, d1.length(), "SHA-256 十六进制摘要长度应为 64");
        String d2 = RandomTableDigester.digest("EXP", 1, 1, 4, "A,B", "ABAB");
        assertNotEquals(d1, d2, "序列顺序不同摘要必须不同");
        String d3 = RandomTableDigester.digest("EXP", 1, 2, 4, "A,B", "AABB");
        assertNotEquals(d1, d3, "版本身份不同摘要必须不同");
        // 摘要本身不泄露完整序列：摘要中不应出现连续序列明文
        assertTrue(d1.matches("[0-9a-f]{64}"));
    }
}
