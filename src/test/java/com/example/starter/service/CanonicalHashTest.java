package com.example.starter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 幂等参数规范化哈希单元测试：
 * 字段顺序无关、十进制数值同值同参、文本原样、列表顺序有意义。
 */
class CanonicalHashTest {

    private final AirspaceReviewService service = new AirspaceReviewService(
            null, null, null, new ObjectMapper(), Clock.systemUTC(), null);

    @Test
    void fieldOrderDoesNotChangeHash() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("routeId", "r1");
        a.put("operator", "op");
        a.put("requestId", "req1");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("requestId", "req1");
        b.put("operator", "op");
        b.put("routeId", "r1");
        assertEquals(service.canonicalHash(a), service.canonicalHash(b));
    }

    @Test
    void decimalEqualValuesAreSameParameter() {
        Map<String, Object> intValue = Map.of("expectedVersion", 1);
        Map<String, Object> decimalValue = Map.of("expectedVersion", 1.0d);
        Map<String, Object> decimalString = Map.of("expectedVersion", new java.math.BigDecimal("1.00"));
        String base = service.canonicalHash(intValue);
        assertEquals(base, service.canonicalHash(decimalValue));
        assertEquals(base, service.canonicalHash(decimalString));
        // 数值不同则哈希不同
        assertNotEquals(base, service.canonicalHash(Map.of("expectedVersion", 2)));
    }

    @Test
    void textIsKeptVerbatim() {
        assertNotEquals(
                service.canonicalHash(Map.of("operator", "Alice")),
                service.canonicalHash(Map.of("operator", "alice")));
        assertNotEquals(
                service.canonicalHash(Map.of("operator", "Alice")),
                service.canonicalHash(Map.of("operator", " Alice")));
    }

    @Test
    void listOrderAndFullParametersMatter() {
        assertEquals(
                service.canonicalHash(List.of(1, 2, 3)),
                service.canonicalHash(List.of(1.0d, 2.0d, 3.0d)));
        assertNotEquals(
                service.canonicalHash(List.of(1, 2, 3)),
                service.canonicalHash(List.of(3, 2, 1)));
        // 资源与操作者均参与比较
        Map<String, Object> full = Map.of("routeId", "r1", "operator", "op1", "v", 1);
        assertNotEquals(service.canonicalHash(full),
                service.canonicalHash(Map.of("routeId", "r2", "operator", "op1", "v", 1)));
        assertNotEquals(service.canonicalHash(full),
                service.canonicalHash(Map.of("routeId", "r1", "operator", "op2", "v", 1)));
    }
}
