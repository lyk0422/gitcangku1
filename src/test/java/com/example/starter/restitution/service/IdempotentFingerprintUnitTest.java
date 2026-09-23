package com.example.starter.restitution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等指纹纯单元测试：集合换序同参、参数内容差异指纹不同。
 */
class IdempotentFingerprintUnitTest {

    private final IdempotentExecutor executor =
            new IdempotentExecutor(null, null, new ObjectMapper(), null);

    @Test
    void reorderedCollectionsProduceSameFingerprint() {
        Object bodyA = Map.of("items", List.of("Vase", "Scroll", "Seal"));
        Object bodyB = Map.of("items", List.of("Seal", "Vase", "Scroll"));

        assertThat(executor.fingerprint("POST /api/cases", bodyA))
                .isEqualTo(executor.fingerprint("POST /api/cases", bodyB));
    }

    @Test
    void differentParamsAndOperationsProduceDifferentFingerprints() {
        String base = executor.fingerprint("POST /api/cases",
                Map.of("items", List.of("Vase")));

        assertThat(base).isNotEqualTo(executor.fingerprint("POST /api/cases",
                Map.of("items", List.of("Scroll"))));
        assertThat(base).isNotEqualTo(executor.fingerprint("POST /api/cases/other",
                Map.of("items", List.of("Vase"))));
        assertThat(base).isNotEqualTo(executor.fingerprint("POST /api/cases", null));
    }

    @Test
    void nestedReorderedCollectionsAreCanonical() {
        Object bodyA = Map.of("claimKey", "c1", "items", List.of("a", "b"));
        Object bodyB = Map.of("items", List.of("b", "a"), "claimKey", "c1");

        assertThat(executor.fingerprint("op", bodyA))
                .isEqualTo(executor.fingerprint("op", bodyB));
    }
}
