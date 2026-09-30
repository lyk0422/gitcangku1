package com.example.starter.batch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 过敏原集合规范化单元测试：trim、去空、去重、按 Unicode 代码点稳定排序，
 * 以及禁止靠未转义拼接比较集合的碰撞场景。
 */
class AllergensTest {

    @Test
    void normalize_trims_dropsBlank_deduplicates_andStablySorts() {
        List<String> normalized = Allergens.normalize(
                List.of(" peanut ", "milk", "milk", "", "  ", "egg", "peanut"));
        // 去重、去空白后按代码点升序：egg < milk < peanut
        assertEquals(List.of("egg", "milk", "peanut"), normalized);
    }

    @Test
    void normalize_nullAndEmptyYieldEmptyList() {
        assertEquals(List.of(), Allergens.normalize(null));
        assertEquals(List.of(), Allergens.normalize(List.of()));
        assertEquals(List.of(), Allergens.normalize(List.of(" ", "  ")));
    }

    @Test
    void normalize_isImmutable() {
        List<String> normalized = Allergens.normalize(List.of("milk", "egg"));
        assertThrows(UnsupportedOperationException.class, () -> normalized.add("peanut"));
    }

    @Test
    void same_isOrderAndDuplicateInsensitive_butElementSensitive() {
        // 换序、重复不影响集合相等判断
        assertTrue(Allergens.same(
                Allergens.normalize(List.of("milk", "egg")),
                Allergens.normalize(List.of("egg", "milk", "egg"))));
        // 元素不同则不兼容
        assertFalse(Allergens.same(
                Allergens.normalize(List.of("milk")),
                Allergens.normalize(List.of("milk", "peanut"))));
        // 空集合只与空集合相等
        assertTrue(Allergens.same(List.of(), List.of()));
        assertFalse(Allergens.same(List.of(), List.of("milk")));
    }

    @Test
    void naiveJoinWouldCollide_butNormalizedSetsDiffer() {
        // 经典拼接碰撞：["a,b"] 与 ["a","b"] 若用未转义 join(",") 会被误判为相同
        List<String> one = Allergens.normalize(List.of("a,b"));
        List<String> two = Allergens.normalize(List.of("a", "b"));
        assertEquals("a,b", String.join(",", one));
        assertEquals("a,b", String.join(",", two),
                "朴素拼接确实把两个不同集合压成同一字符串");
        assertFalse(Allergens.same(one, two),
                "规范化集合逐元素比较必须判定二者不兼容");
        assertEquals(List.of("a,b"), one);
        assertEquals(List.of("a", "b"), two);
        // 指纹片段带逐项名称前缀，也不会碰撞
        assertNotEquals(Allergens.fingerprintParts(one), Allergens.fingerprintParts(two));
    }

    @Test
    void codePointOrdering_isStableForUnicode() {
        // 大小写按代码点数值：大写在前；中文按代码点排序确定且可复现
        assertEquals(List.of("Milk", "milk"),
                Allergens.normalize(List.of("milk", "Milk")));
        List<String> chinese = Allergens.normalize(List.of("花生", "乳", "蛋"));
        assertEquals(List.of(List.of("乳", "花生", "蛋")), List.of(chinese));
        // 重复执行结果完全一致（稳定）
        assertEquals(chinese, Allergens.normalize(List.of("花生", "乳", "蛋", "乳")));
    }
}
