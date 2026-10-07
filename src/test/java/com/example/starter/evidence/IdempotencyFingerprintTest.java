package com.example.starter.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等指纹单元测试：无碰撞 TLV 结构、十进制同值同参、文本原样、Base64 按解码字节规范化。
 */
class IdempotencyFingerprintTest {

    private final IdempotencyAdvisor advisor = new IdempotencyAdvisor(null, new ObjectMapper());

    private Map<String, Object> body(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    void decimalEqualValuesProduceSameHash() {
        String integerForm = advisor.hash("OP", "alice", "res", body("offset", 1));
        String decimalForm = advisor.hash("OP", "alice", "res", body("offset", 1.0));
        assertThat(decimalForm).isEqualTo(integerForm);
    }

    @Test
    void numericAndTextAreNotEqual() {
        String numeric = advisor.hash("OP", "alice", "res", body("offset", 1));
        String text = advisor.hash("OP", "alice", "res", body("offset", "1"));
        assertThat(text).isNotEqualTo(numeric);
    }

    @Test
    void textValuesArePreservedVerbatim() {
        String withPadding = advisor.hash("OP", "alice", "res", body("note", " a "));
        String trimmed = advisor.hash("OP", "alice", "res", body("note", "a"));
        assertThat(trimmed).isNotEqualTo(withPadding);
    }

    @Test
    void fieldNameConcatenationCannotCollide() {
        // 朴素字符串拼接会把 ("a","bc")/("ab","c") 混成同一片段；TLV 长度前缀必须区分。
        String first = advisor.hash("OP", "alice", "res", body("a", "bc"));
        String second = advisor.hash("OP", "alice", "res", body("ab", "c"));
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void operationActorAndResourceAllParticipate() {
        Map<String, Object> params = body("offset", 0);
        String base = advisor.hash("OP_A", "alice", "res-1", params);
        assertThat(advisor.hash("OP_B", "alice", "res-1", params)).isNotEqualTo(base);
        assertThat(advisor.hash("OP_A", "bob", "res-1", params)).isNotEqualTo(base);
        assertThat(advisor.hash("OP_A", "alice", "res-2", params)).isNotEqualTo(base);
    }

    @Test
    void equivalentBase64EncodingsProduceSameHash() {
        // "ab" 的标准编码 YWI=；省略填充/插入换行属于等价写法，按解码字节应同参。
        String canonical = "YWI=";
        String noPadding = "YWI";
        String withNewline = "YW\nI=";
        String hashCanonical = advisor.hash("OP", "alice", "res",
                body("dataBase64", ChunkEncoding.canonical(canonical)));
        String hashNoPadding = advisor.hash("OP", "alice", "res",
                body("dataBase64", ChunkEncoding.canonical(noPadding)));
        String hashNewline = advisor.hash("OP", "alice", "res",
                body("dataBase64", ChunkEncoding.canonical(withNewline)));
        assertThat(hashNoPadding).isEqualTo(hashCanonical);
        assertThat(hashNewline).isEqualTo(hashCanonical);
        // 不同解码字节必须形成不同参数。
        String hashOther = advisor.hash("OP", "alice", "res",
                body("dataBase64", ChunkEncoding.canonical("YWM=")));
        assertThat(hashOther).isNotEqualTo(hashCanonical);
    }
}
