package com.example.starter.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构化请求指纹单元测试：身份含操作、操作者、资源及全部参数，
 * 结构化比较避免分隔符碰撞；同参同指纹，异参异指纹。
 */
class IdempotencyAdvisorTest {

    private final IdempotencyAdvisor advisor = new IdempotencyAdvisor(null, new ObjectMapper());

    @Test
    void sameInputsProduceSameHashAndDifferentParamsProduceDifferentHash() {
        String h1 = advisor.hashStructured("REG_TRANSFER_CHECKIN", "ted", "EV-1",
                Map.of("checkpointNo", 1, "sealNo", "SEAL-1"));
        String h2 = advisor.hashStructured("REG_TRANSFER_CHECKIN", "ted", "EV-1",
                Map.of("checkpointNo", 1, "sealNo", "SEAL-1"));
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64);

        assertThat(advisor.hashStructured("REG_TRANSFER_CHECKIN", "ted", "EV-1",
                Map.of("checkpointNo", 1, "sealNo", "SEAL-2"))).isNotEqualTo(h1);
        assertThat(advisor.hashStructured("REG_TRANSFER_CHECKIN", "alice", "EV-1",
                Map.of("checkpointNo", 1, "sealNo", "SEAL-1"))).isNotEqualTo(h1);
        assertThat(advisor.hashStructured("REG_TRANSFER_ACCEPT", "ted", "EV-1",
                Map.of("checkpointNo", 1, "sealNo", "SEAL-1"))).isNotEqualTo(h1);
        assertThat(advisor.hashStructured("REG_TRANSFER_CHECKIN", "ted", "EV-2",
                Map.of("checkpointNo", 1, "sealNo", "SEAL-1"))).isNotEqualTo(h1);
    }

    @Test
    void separatorInParameterValuesDoesNotCollide() {
        // 朴素分隔符拼接下两者均为 "OP\nx\ny\nz\nB"，会碰撞；结构化指纹必须区分。
        String caseA = advisor.hashStructured("OP", "x\ny", "z", "B");
        String caseB = advisor.hashStructured("OP", "x", "y\nz", "B");
        assertThat(caseA).isNotEqualTo(caseB);

        String naiveA = String.join("\n", "OP", "x\ny", "z", "B");
        String naiveB = String.join("\n", "OP", "x", "y\nz", "B");
        assertThat(naiveA).isEqualTo(naiveB);
    }

    @Test
    void nestedParametersArePartOfIdentity() {
        Map<String, Object> body1 = Map.of("requestId", "R1", "expectedVersion", 3,
                "checkpoints", java.util.List.of(Map.of("deadlineAt", "2030-06-01T10:00:00")));
        Map<String, Object> body2 = Map.of("requestId", "R1", "expectedVersion", 4,
                "checkpoints", java.util.List.of(Map.of("deadlineAt", "2030-06-01T10:00:00")));
        assertThat(advisor.hashStructured("OP", "a", "EV-1", body1))
                .isNotEqualTo(advisor.hashStructured("OP", "a", "EV-1", body2));
    }
}