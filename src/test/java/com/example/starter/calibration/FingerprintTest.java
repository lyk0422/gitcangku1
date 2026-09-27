package com.example.starter.calibration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.starter.calibration.service.Fingerprints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 请求指纹单元测试：规范化集合排序、操作者/操作类型/影响字段全部入指纹，
 * 不使用未转义分隔符拼接，避免顺序与注入歧义。
 */
class FingerprintTest {

    @Test
    void sameInputsProduceSameFingerprint() {
        Map<String, Object> p1 = Map.of("keys", List.of("A", "B"), "max", "1");
        Map<String, Object> p2 = Map.of("max", "1", "keys", List.of("A", "B"));
        assertEquals(Fingerprints.of("carol", "BATCH_RELEASE", p1),
                Fingerprints.of("carol", "BATCH_RELEASE", p2));
    }

    @Test
    void keySetOrderDoesNotMatterButComponentOrderIsNormalizedByCaller() {
        // 调用方保证集合排序：不同顺序的集合视为不同参数，放行/预算服务均先排序
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("keys", List.of("A", "B"));
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("keys", List.of("B", "A"));
        assertNotEquals(Fingerprints.of("c", "BATCH_RELEASE", ordered),
                Fingerprints.of("c", "BATCH_RELEASE", reversed));

        Map<String, Object> sorted1 = new LinkedHashMap<>();
        sorted1.put("keys", List.of("A", "B").stream().sorted().toList());
        Map<String, Object> sorted2 = new LinkedHashMap<>();
        sorted2.put("keys", List.of("B", "A").stream().sorted().toList());
        assertEquals(Fingerprints.of("c", "BATCH_RELEASE", sorted1),
                Fingerprints.of("c", "BATCH_RELEASE", sorted2));
    }

    @Test
    void differentActorOperationTypeOrParametersProduceDifferentFingerprint() {
        Map<String, Object> p = Map.of("keys", List.of("A"));
        String base = Fingerprints.of("carol", "BATCH_RELEASE", p);
        assertNotEquals(base, Fingerprints.of("dave", "BATCH_RELEASE", p));
        assertNotEquals(base, Fingerprints.of("carol", "OTHER_OP", p));
        assertNotEquals(base, Fingerprints.of("carol", "BATCH_RELEASE", Map.of("keys", List.of("B"))));
    }

    @Test
    void noUnescapedSeparatorCollisionBetweenNamesAndValues() {
        // 若用 name + "|" + magnitude 拼接会碰撞；规范化 JSON 不碰撞
        Map<String, Object> p1 = Map.of("components",
                List.of(Map.of("name", "a|b", "magnitude", "1"), Map.of("name", "c", "magnitude", "1")));
        Map<String, Object> p2 = Map.of("components",
                List.of(Map.of("name", "a", "magnitude", "1"), Map.of("name", "b|c", "magnitude", "1")));
        assertNotEquals(Fingerprints.of("u", "REPLACE_BUDGET", p1),
                Fingerprints.of("u", "REPLACE_BUDGET", p2));
    }
}
