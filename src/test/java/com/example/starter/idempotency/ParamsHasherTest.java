package com.example.starter.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParamsHasherTest {

    @Test
    void sameParamsInDifferentOrderProduceSameHash() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("participantId", "P-1");
        first.put("experimentId", "E-1");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("experimentId", "E-1");
        second.put("participantId", "P-1");

        assertThat(ParamsHasher.sha256(OperationType.ENROLL, first))
                .isEqualTo(ParamsHasher.sha256(OperationType.ENROLL, second));
    }

    @Test
    void differentParamValuesProduceDifferentHash() {
        assertThat(ParamsHasher.sha256(OperationType.ENROLL, Map.of(
                "experimentId", "E-1", "participantId", "P-1")))
                .isNotEqualTo(ParamsHasher.sha256(OperationType.ENROLL, Map.of(
                        "experimentId", "E-1", "participantId", "P-2")));
    }

    @Test
    void differentOperationsProduceDifferentHashEvenWithSameParams() {
        Map<String, Object> params = Map.of("experimentId", "E-1");
        assertThat(ParamsHasher.sha256(OperationType.CLOSE_EXPERIMENT, params))
                .isNotEqualTo(ParamsHasher.sha256(OperationType.CREATE_EXPERIMENT, params));
    }

    @Test
    void hashIsSha256Hex() {
        assertThat(ParamsHasher.sha256(OperationType.ENROLL, Map.of()))
                .matches("[0-9a-f]{64}");
    }
}
