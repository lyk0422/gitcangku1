package com.example.starter.plan.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 幂等指纹纯逻辑测试：操作者/操作类型/字段/集合顺序与 null 语义均影响指纹；
 * 载荷以规范化 JSON 编码，字段值中的分隔符不会造成碰撞，且不依赖系统时钟。
 */
class RequestFingerprintTest {

    private final RequestFingerprint fingerprint = new RequestFingerprint(new ObjectMapper());

    @Test
    void sameOperatorAndPayloadProduceSameHash() {
        Map<String, Object> payload = payload("SCH-1", List.of(
                List.of("G1", "SEC-A", 100L, 200L)));
        String a = fingerprint.sha256("PUBLISH", "alice", payload);
        String b = fingerprint.sha256("PUBLISH", "alice", payload);
        assertThat(a).isEqualTo(b).hasSize(64);
    }

    @Test
    void differentOperatorProducesDifferentHash() {
        Map<String, Object> payload = payload("SCH-1", List.of());
        assertThat(fingerprint.sha256("PUBLISH", "alice", payload))
                .isNotEqualTo(fingerprint.sha256("PUBLISH", "bob", payload));
    }

    @Test
    void differentOperationTypeProducesDifferentHash() {
        Map<String, Object> payload = payload("SCH-1", List.of());
        assertThat(fingerprint.sha256("PUBLISH", "alice", payload))
                .isNotEqualTo(fingerprint.sha256("CANCEL", "alice", payload));
    }

    @Test
    void differentResultFieldsProduceDifferentHash() {
        assertThat(fingerprint.sha256("CREATE", "alice", payload("SCH-1", List.of())))
                .isNotEqualTo(fingerprint.sha256("CREATE", "alice", payload("SCH-2", List.of())));
    }

    @Test
    void collectionOrderIsSignificant() {
        Map<String, Object> p1 = payload("SCH-1", List.of(
                List.of("G1", "SEC-A", 100L, 200L),
                List.of("G2", "SEC-B", 300L, 400L)));
        Map<String, Object> p2 = payload("SCH-1", List.of(
                List.of("G2", "SEC-B", 300L, 400L),
                List.of("G1", "SEC-A", 100L, 200L)));
        assertThat(fingerprint.sha256("CREATE", "alice", p1))
                .isNotEqualTo(fingerprint.sha256("CREATE", "alice", p2));
    }

    @Test
    void nullSemanticsAreSignificant() {
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("arrival", null);
        withNull.put("departure", Instant.ofEpochMilli(200L).toEpochMilli());
        Map<String, Object> withoutNull = new LinkedHashMap<>();
        withoutNull.put("arrival", Instant.ofEpochMilli(200L).toEpochMilli());
        withoutNull.put("departure", Instant.ofEpochMilli(200L).toEpochMilli());
        assertThat(fingerprint.sha256("CREATE", "alice", withNull))
                .isNotEqualTo(fingerprint.sha256("CREATE", "alice", withoutNull));
    }

    @Test
    void embeddedDelimitersDoNotCollide() {
        // 值内含换行、竖线、引号等“分隔符”，规范化 JSON 转义后不应与另一条拼接结果碰撞
        Map<String, Object> split1 = new LinkedHashMap<>();
        split1.put("a", "x\ny|z");
        split1.put("b", "second");
        Map<String, Object> split2 = new LinkedHashMap<>();
        split2.put("a", "x");
        split2.put("b", "y|z\nsecond");
        assertThat(fingerprint.sha256("CREATE", "alice", split1))
                .isNotEqualTo(fingerprint.sha256("CREATE", "alice", split2));
    }

    @Test
    void canonicalJsonIsStableAndSorted() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("b", 2);
        payload.put("a", 1);
        String json = fingerprint.canonicalJson("PUBLISH", "alice", payload);
        // 根字段按字典序：op, operator, payload；载荷内字段 a 在 b 前
        assertThat(json).contains("\"op\":\"PUBLISH\"")
                .contains("\"operator\":\"alice\"")
                .containsPattern("\"payload\":\\{\"a\":1,\"b\":2\\}");
    }

    private static Map<String, Object> payload(String scheduleKey, List<List<Object>> rows) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scheduleKey", scheduleKey);
        payload.put("occupancies", rows);
        return payload;
    }
}
