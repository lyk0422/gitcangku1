package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 规范化集合与摘要的纯单元测试：稳定排序、JSON 转义防碰撞、重复检测与 SHA-256 已知向量。
 */
class CanonicalSetUtilTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void canonicalizeSortsKeysAndProducesStableJsonAndDigest() {
        CanonicalSetUtil.CanonicalSet first =
                CanonicalSetUtil.canonicalize(List.of("b", "a", "c"), objectMapper);
        CanonicalSetUtil.CanonicalSet second =
                CanonicalSetUtil.canonicalize(List.of("c", "b", "a"), objectMapper);

        assertThat(first.orderedKeys()).containsExactly("a", "b", "c");
        assertThat(first.canonicalJson()).isEqualTo("[\"a\",\"b\",\"c\"]");
        // 与提交顺序无关：同一集合规范化结果与摘要完全一致
        assertThat(second.canonicalJson()).isEqualTo(first.canonicalJson());
        assertThat(second.digest()).isEqualTo(first.digest()).hasSize(64);
    }

    @Test
    void keysContainingDelimitersAreJsonEscapedAndCannotCollide() {
        // 若用裸分隔符拼接，["a","b"] 与 ["a,b"] 会碰撞；JSON 转义后两者摘要必须不同
        CanonicalSetUtil.CanonicalSet twoKeys =
                CanonicalSetUtil.canonicalize(List.of("a", "b"), objectMapper);
        CanonicalSetUtil.CanonicalSet oneKeyWithComma =
                CanonicalSetUtil.canonicalize(List.of("a,b"), objectMapper);
        CanonicalSetUtil.CanonicalSet quoted =
                CanonicalSetUtil.canonicalize(List.of("a\"b", "c\\d"), objectMapper);

        assertThat(twoKeys.canonicalJson()).isEqualTo("[\"a\",\"b\"]");
        assertThat(oneKeyWithComma.canonicalJson()).isEqualTo("[\"a,b\"]");
        assertThat(twoKeys.digest()).isNotEqualTo(oneKeyWithComma.digest());
        assertThat(quoted.canonicalJson()).isEqualTo("[\"a\\\"b\",\"c\\\\d\"]");
    }

    @Test
    void duplicateKeysAreRejected() {
        assertThatThrownBy(() -> CanonicalSetUtil.canonicalize(List.of("a", "a"), objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
    }

    @Test
    void emptySetCanonicalizesToEmptyJsonArray() {
        CanonicalSetUtil.CanonicalSet empty =
                CanonicalSetUtil.canonicalize(List.of(), objectMapper);
        assertThat(empty.orderedKeys()).isEmpty();
        assertThat(empty.canonicalJson()).isEqualTo("[]");
        assertThat(empty.digest()).isEqualTo(CanonicalSetUtil.sha256Hex("[]".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sha256MatchesKnownVector() throws Exception {
        // NIST 已知向量：SHA-256("abc")
        String expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        assertThat(CanonicalSetUtil.sha256Hex("abc".getBytes(StandardCharsets.UTF_8))).isEqualTo(expected);
    }
}
