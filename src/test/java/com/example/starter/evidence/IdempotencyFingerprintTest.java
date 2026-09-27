package com.example.starter.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等指纹纯单元测试：指纹覆盖操作者、操作类型、规范化集合与全部结果相关字段；
 * 不使用未转义分隔符拼接（换行/引号等特殊字符不会造成碰撞），集合先排序再哈希。
 */
class IdempotencyFingerprintTest {

    private final IdempotencyAdvisor advisor = new IdempotencyAdvisor(null, new ObjectMapper());

    private Map<String, Object> item(String reservationKey, String evidenceKey, String benchKey,
                                     String startAt, String endAt) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("reservationKey", reservationKey);
        entry.put("evidenceKey", evidenceKey);
        entry.put("benchKey", benchKey);
        entry.put("startAt", startAt);
        entry.put("endAt", endAt);
        return entry;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> canonical(Object items) {
        List<Map<String, Object>> sorted = ((List<Map<String, Object>>) items).stream()
                .sorted(Comparator.comparing((Map<String, Object> m) -> (String) m.get("evidenceKey"))
                        .thenComparing(m -> (String) m.get("benchKey"))
                        .thenComparing(m -> (String) m.get("startAt")))
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", sorted);
        return payload;
    }

    @Test
    void reorderedNormalizedCollectionsProduceSameFingerprint() {
        List<Map<String, Object>> orderA = List.of(
                item("R1", "EV1", "BENCH-A", "10:00", "11:00"),
                item("R2", "EV2", "BENCH-A", "12:00", "13:00"),
                item("R3", "EV3", "BENCH-B", "09:00", "10:00"));
        List<Map<String, Object>> orderB = List.of(
                orderA.get(2), orderA.get(0), orderA.get(1));

        String hashA = advisor.hashCanonical("EXAM_RESERVE", "lab-alice", canonical(orderA));
        String hashB = advisor.hashCanonical("EXAM_RESERVE", "lab-alice", canonical(orderB));
        assertThat(hashA).isEqualTo(hashB);

        // 未规范化（直接按提交顺序）的集合指纹不同，证明排序是规范化的一部分。
        Map<String, Object> rawA = new LinkedHashMap<>();
        rawA.put("items", orderA);
        Map<String, Object> rawB = new LinkedHashMap<>();
        rawB.put("items", orderB);
        assertThat(advisor.hashCanonical("EXAM_RESERVE", "lab-alice", rawA))
                .isNotEqualTo(advisor.hashCanonical("EXAM_RESERVE", "lab-alice", rawB));
    }

    @Test
    void differentActorOrOperationOrResultFieldsProduceDifferentFingerprint() {
        Object payload = canonical(List.of(item("R1", "EV1", "BENCH-A", "10:00", "11:00")));
        String base = advisor.hashCanonical("EXAM_RESERVE", "lab-alice", payload);

        assertThat(advisor.hashCanonical("EXAM_START", "lab-alice", payload)).isNotEqualTo(base);
        assertThat(advisor.hashCanonical("EXAM_RESERVE", "lab-bob", payload)).isNotEqualTo(base);

        // 任一结果相关字段变化都改变指纹。
        assertThat(advisor.hashCanonical("EXAM_RESERVE", "lab-alice",
                canonical(List.of(item("R1", "EV1", "BENCH-B", "10:00", "11:00")))))
                .isNotEqualTo(base);
    }

    @Test
    void specialCharactersDoNotCollideBecauseJsonEscapesSeparators() {
        // 若采用未转义分隔符拼接，("a","b") 与 ("a\nb","") 会碰撞；JSON 转义后必须不同。
        String plain = advisor.hashCanonical("OP", "a", Map.of("k", "b"));
        String newline = advisor.hashCanonical("OP", "a\nb", Map.of("k", ""));
        String quotes = advisor.hashCanonical("OP", "a", Map.of("k", "\"b\""));
        String delimiter = advisor.hashCanonical("OP", "a\"op\"", Map.of("k", "b"));
        assertThat(plain).isNotEqualTo(newline);
        assertThat(plain).isNotEqualTo(quotes);
        assertThat(plain).isNotEqualTo(delimiter);

        // 相同特殊字符输入仍稳定复现。
        assertThat(advisor.hashCanonical("OP", "a\nb", Map.of("k", ""))).isEqualTo(newline);
    }

    @Test
    void legacyHashIncludesOperationActorEvidenceKeyAndBody() {
        String hash = advisor.hash("EXAM_START", "lab-alice", "EV1", Map.of("commandKey", "C1"));
        assertThat(hash).isNotEqualTo(
                advisor.hash("EXAM_START", "lab-bob", "EV1", Map.of("commandKey", "C1")));
        assertThat(hash).isNotEqualTo(
                advisor.hash("EXAM_CANCEL", "lab-alice", "EV1", Map.of("commandKey", "C1")));
        assertThat(hash).isNotEqualTo(
                advisor.hash("EXAM_START", "lab-alice", "EV2", Map.of("commandKey", "C1")));
        assertThat(hash).isEqualTo(
                advisor.hash("EXAM_START", "lab-alice", "EV1", Map.of("commandKey", "C1")));
    }
}
