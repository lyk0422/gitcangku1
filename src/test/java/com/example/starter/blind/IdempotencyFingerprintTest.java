package com.example.starter.blind;

import com.example.starter.blind.service.IdempotencyService;
import com.example.starter.blind.service.RetirementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 幂等指纹与代码集合规范化的纯单元测试（不启动 Spring 上下文）：
 * 规范化对象集合使同一逻辑操作指纹一致；指纹由 JSON 序列化生成，
 * 不使用未转义分隔符拼接，参数中的分隔符不会造成指纹碰撞。
 */
class IdempotencyFingerprintTest {

    private final IdempotencyService idempotencyService =
            new IdempotencyService(null, new ObjectMapper(), null);

    @Test
    void normalizeCodes_trimsUppercasesDedupesAndSorts() {
        assertEquals(List.of("A", "B"),
                RetirementService.normalizeCodes(List.of("b", " A ", "a", "B")));
        assertEquals(List.of("A"), RetirementService.normalizeCodes(List.of("A")));
        assertThrows(ApiException.class, () -> RetirementService.normalizeCodes(null));
        assertThrows(ApiException.class, () -> RetirementService.normalizeCodes(List.of()));
        assertThrows(ApiException.class, () -> RetirementService.normalizeCodes(List.of("AB")));
        assertThrows(ApiException.class, () -> RetirementService.normalizeCodes(List.of("1")));
        assertThrows(ApiException.class, () -> RetirementService.normalizeCodes(List.of(" ")));
    }

    @Test
    void fingerprint_sameNormalizedSetSameFingerprint_regardlessOfInputOrder() {
        String op = "code.retire";
        Map<String, Object> canonical = Map.of("experimentId", "E1",
                "codes", RetirementService.normalizeCodes(List.of("b", "A", "a")),
                "reason", "r");
        Map<String, Object> reordered = Map.of("experimentId", "E1",
                "codes", RetirementService.normalizeCodes(List.of("A", "B")),
                "reason", "r");
        assertEquals(idempotencyService.fingerprint(op, canonical),
                idempotencyService.fingerprint(op, reordered));
    }

    @Test
    void fingerprint_differsOnAnyResultRelevantField() {
        String op = "code.retire";
        Map<String, Object> base = Map.of("experimentId", "E1",
                "codes", List.of("A"), "reason", "r");
        String baseFingerprint = idempotencyService.fingerprint(op, base);
        assertNotEquals(baseFingerprint, idempotencyService.fingerprint(op,
                Map.of("experimentId", "E1", "codes", List.of("B"), "reason", "r")));
        assertNotEquals(baseFingerprint, idempotencyService.fingerprint(op,
                Map.of("experimentId", "E1", "codes", List.of("A"), "reason", "r2")));
        assertNotEquals(baseFingerprint, idempotencyService.fingerprint(op,
                Map.of("experimentId", "E2", "codes", List.of("A"), "reason", "r")));
        assertNotEquals(baseFingerprint, idempotencyService.fingerprint("code.other", base));
    }

    @Test
    void fingerprint_delimiterInValuesCannotCollideAcrossFields() {
        // 值中携带分隔符不得与另一组字段值产生相同指纹（JSON 序列化而非拼接）
        String op = "code.retire";
        String first = idempotencyService.fingerprint(op,
                Map.of("experimentId", "E|1", "codes", List.of("A"), "reason", "r"));
        String second = idempotencyService.fingerprint(op,
                Map.of("experimentId", "E", "codes", List.of("A"), "reason", "1|r"));
        assertNotEquals(first, second);
    }
}
