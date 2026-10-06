package com.example.starter.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构化幂等指纹测试：操作、资源、全参数与期望版本参与比较；
 * 十进制按值比较，文本按原样比较，对象字段顺序无关。
 */
class IdempotencyAdvisorTest {

    private final IdempotencyAdvisor advisor = new IdempotencyAdvisor(null, new ObjectMapper());

    @Test
    void decimalsComparedByValueAndFieldOrderIrrelevant() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("requestId", "REQ-1");
        first.put("expectedVersion", 2);
        first.put("amount", new BigDecimal("1.0"));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("amount", new BigDecimal("1.00"));
        second.put("expectedVersion", 2);
        second.put("requestId", "REQ-1");

        assertThat(advisor.hashStructured("OP", "alice", "BOX-1", first))
                .isEqualTo(advisor.hashStructured("OP", "alice", "BOX-1", second));
    }

    @Test
    void textComparedAsIs() {
        Map<String, Object> lower = Map.of("requestId", "REQ-1", "note", "abc");
        Map<String, Object> upper = Map.of("requestId", "REQ-1", "note", "ABC");
        Map<String, Object> spaced = Map.of("requestId", "REQ-1", "note", "a b");
        Map<String, Object> doubleSpaced = Map.of("requestId", "REQ-1", "note", "a  b");

        assertThat(advisor.hashStructured("OP", "alice", "BOX-1", lower))
                .isNotEqualTo(advisor.hashStructured("OP", "alice", "BOX-1", upper));
        assertThat(advisor.hashStructured("OP", "alice", "BOX-1", spaced))
                .isNotEqualTo(advisor.hashStructured("OP", "alice", "BOX-1", doubleSpaced));
    }

    @Test
    void operationResourceActorAndParamsAllParticipate() {
        Map<String, Object> body = Map.of("requestId", "REQ-1", "expectedVersion", 1,
                "evidenceKeys", List.of("EV-1"), "containerKeys", List.of());
        String base = advisor.hashStructured("CONTAINER_PACK", "alice", "BOX-1", body);

        assertThat(advisor.hashStructured("CONTAINER_UNPACK", "alice", "BOX-1", body))
                .isNotEqualTo(base);
        assertThat(advisor.hashStructured("CONTAINER_PACK", "bob", "BOX-1", body))
                .isNotEqualTo(base);
        assertThat(advisor.hashStructured("CONTAINER_PACK", "alice", "BOX-2", body))
                .isNotEqualTo(base);
        Map<String, Object> otherVersion = Map.of("requestId", "REQ-1", "expectedVersion", 2,
                "evidenceKeys", List.of("EV-1"), "containerKeys", List.of());
        assertThat(advisor.hashStructured("CONTAINER_PACK", "alice", "BOX-1", otherVersion))
                .isNotEqualTo(base);
    }
}
