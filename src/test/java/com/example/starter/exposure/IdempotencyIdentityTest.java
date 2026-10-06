package com.example.starter.exposure;

import com.example.starter.exposure.exposure.IdempotencyIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 幂等身份指纹单元测试：结构化序列化确定性、无分隔符碰撞、空值与列表顺序语义。
 */
class IdempotencyIdentityTest {

    @Test
    @DisplayName("键序无关：相同键值集合生成相同指纹")
    void deterministicRegardlessOfKeyOrder() {
        Map<String, Object> first = new HashMap<>();
        first.put("campaignId", "c1");
        first.put("visitorId", "v1");
        first.put("rotation", true);
        Map<String, Object> second = new HashMap<>();
        second.put("rotation", true);
        second.put("visitorId", "v1");
        second.put("campaignId", "c1");
        assertEquals(IdempotencyIdentity.of(first), IdempotencyIdentity.of(second));
    }

    @Test
    @DisplayName("结构化比较无分隔符碰撞：含分隔符的参数不会与拼接结果混淆")
    void noSeparatorCollision() {
        // 若用 "a|b" 风格拼接，以下两组参数会产生相同文本
        String structured = IdempotencyIdentity.of(Map.of("campaignId", "c|1", "visitorId", "v"));
        String otherSplit = IdempotencyIdentity.of(Map.of("campaignId", "c", "visitorId", "1|v"));
        assertNotEquals(structured, otherSplit);

        String merged = IdempotencyIdentity.of(Map.of("campaignId", "c|1|v"));
        assertNotEquals(structured, merged);
        assertNotEquals(otherSplit, merged);
    }

    @Test
    @DisplayName("参数差异必产生不同指纹：布尔、数值、null 均参与身份")
    void anyParamDifferenceChangesFingerprint() {
        String base = IdempotencyIdentity.of(Map.of("reservationId", "r1", "expectedVersion", 0L));
        assertNotEquals(base, IdempotencyIdentity.of(Map.of("reservationId", "r1", "expectedVersion", 1L)));
        assertNotEquals(base, IdempotencyIdentity.of(Map.of("reservationId", "r2", "expectedVersion", 0L)));

        Map<String, Object> withNull = new HashMap<>();
        withNull.put("reservationId", "r1");
        withNull.put("expectedVersion", null);
        // null 与缺省字段语义不同：null 显式出现在身份中
        assertNotEquals(IdempotencyIdentity.of(withNull),
                IdempotencyIdentity.of(Map.of("reservationId", "r1")));
        assertEquals(IdempotencyIdentity.of(withNull), IdempotencyIdentity.of(withNull));
    }

    @Test
    @DisplayName("有序列表顺序敏感：创意顺序不同则身份不同")
    void listOrderMatters() {
        assertNotEquals(
                IdempotencyIdentity.of(Map.of("creativeIds", List.of("A", "B"))),
                IdempotencyIdentity.of(Map.of("creativeIds", List.of("B", "A"))));
        assertEquals(
                IdempotencyIdentity.of(Map.of("creativeIds", List.of("A", "B"))),
                IdempotencyIdentity.of(Map.of("creativeIds", List.of("A", "B"))));
    }
}
