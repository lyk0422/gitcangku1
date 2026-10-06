package com.example.starter.baggage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等请求指纹单元测试：验证结构化防碰撞编码——
 * 对象键序无关、数组保序、十进制按数值归一（1 与 1.0 同指纹）、
 * 文本原样（大小写/空白敏感），操作者/操作/资源/任一参数不同则指纹必异。
 */
class IdempotencyFingerprintTest {

    private final IdempotencyService idempotencyService =
            new IdempotencyService(null, null, new ObjectMapper());

    private String fingerprint(String operator, String operation, String resource, Object params) {
        return idempotencyService.fingerprint(operator, operation, resource, params);
    }

    @Test
    void sameLogicalRequest_producesSameHashRegardlessOfKeyOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("bagTags", List.of("B1", "B2"));
        first.put("expectedVersion", 1);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("expectedVersion", 1);
        second.put("bagTags", List.of("B1", "B2"));

        assertThat(fingerprint("op-1", "LOAD", "LEG1", first))
                .isEqualTo(fingerprint("op-1", "LOAD", "LEG1", second));
    }

    @Test
    void decimalNumbersComparedByValue() {
        assertThat(fingerprint("op", "OP", "R", Map.of("v", 1)))
                .as("整数 1 与小数 1.0 十进制值相同")
                .isEqualTo(fingerprint("op", "OP", "R", Map.of("v", 1.0)));
        assertThat(fingerprint("op", "OP", "R", Map.of("v", 100)))
                .isEqualTo(fingerprint("op", "OP", "R", Map.of("v", 1.00e2)));
        assertThat(fingerprint("op", "OP", "R", Map.of("v", 1)))
                .isNotEqualTo(fingerprint("op", "OP", "R", Map.of("v", 2)));
    }

    @Test
    void textComparedVerbatim_caseAndWhitespaceSensitive() {
        assertThat(fingerprint("op", "OP", "R", Map.of("tag", "B1")))
                .isNotEqualTo(fingerprint("op", "OP", "R", Map.of("tag", "b1")));
        assertThat(fingerprint("op", "OP", "R", Map.of("tag", "B1")))
                .isNotEqualTo(fingerprint("op", "OP", "R", Map.of("tag", "B1 ")));
        assertThat(fingerprint("op", "OP", "R", Map.of("tag", "B 1")))
                .isEqualTo(fingerprint("op", "OP", "R", Map.of("tag", "B 1")));
    }

    @Test
    void differentIdentityOrParams_produceDifferentHash() {
        String base = fingerprint("op-1", "LOAD", "LEG1",
                Map.of("expectedVersion", 1, "bagTags", List.of("B1")));
        assertThat(base).isNotEqualTo(fingerprint("op-2", "LOAD", "LEG1",
                Map.of("expectedVersion", 1, "bagTags", List.of("B1"))));
        assertThat(base).isNotEqualTo(fingerprint("op-1", "SEAL", "LEG1",
                Map.of("expectedVersion", 1, "bagTags", List.of("B1"))));
        assertThat(base).isNotEqualTo(fingerprint("op-1", "LOAD", "LEG2",
                Map.of("expectedVersion", 1, "bagTags", List.of("B1"))));
        assertThat(base).isNotEqualTo(fingerprint("op-1", "LOAD", "LEG1",
                Map.of("expectedVersion", 2, "bagTags", List.of("B1"))));
        assertThat(base).isNotEqualTo(fingerprint("op-1", "LOAD", "LEG1",
                Map.of("expectedVersion", 1, "bagTags", List.of("B2"))));
    }

    @Test
    void arrayOrderMattersButStructureDoesNotCollide() {
        assertThat(fingerprint("op", "OP", "R", List.of("B1", "B2")))
                .isNotEqualTo(fingerprint("op", "OP", "R", List.of("B2", "B1")));
        // 不同结构（标量/数组/对象/布尔/null）不会因编码碰撞
        assertThat(fingerprint("op", "OP", "R", "B1"))
                .isNotEqualTo(fingerprint("op", "OP", "R", List.of("B1")));
        assertThat(fingerprint("op", "OP", "R", List.of("B1")))
                .isNotEqualTo(fingerprint("op", "OP", "R", Map.of("0", "B1")));
        assertThat(fingerprint("op", "OP", "R", true))
                .isNotEqualTo(fingerprint("op", "OP", "R", "true"));
        assertThat(fingerprint("op", "OP", "R", "a:b"))
                .isNotEqualTo(fingerprint("op", "OP", "R", Map.of("a", "b")));
    }

    @Test
    void nestedObjectsAndNullsAreCanonical() {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("generations", new LinkedHashMap<>(Map.of("B2", 2, "B1", 1)));
        one.put("missing", null);
        Map<String, Object> two = new LinkedHashMap<>();
        two.put("missing", null);
        two.put("generations", new LinkedHashMap<>(Map.of("B1", 1, "B2", 2)));
        assertThat(fingerprint("op", "RETAG", "ID-1", one))
                .isEqualTo(fingerprint("op", "RETAG", "ID-1", two));
    }
}
