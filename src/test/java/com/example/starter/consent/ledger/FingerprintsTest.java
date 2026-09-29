package com.example.starter.consent.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 指纹工具单元测试：集合规范化、转义安全与稳定性。
 */
class FingerprintsTest {

    @Test
    void normalizeDeduplicatesAndSortsByDictionaryOrder() {
        assertThat(Fingerprints.normalizeObjects(List.of("rec-b", "rec-a", "rec-a", "rec-c")))
                .containsExactly("rec-a", "rec-b", "rec-c");
    }

    @Test
    void digestIsStableRegardlessOfInputOrder() {
        List<String> one = Fingerprints.normalizeObjects(List.of("rec-a", "rec-b", "rec-c"));
        List<String> two = Fingerprints.normalizeObjects(List.of("rec-c", "rec-b", "rec-a"));
        assertThat(Fingerprints.digestObjects(one)).isEqualTo(Fingerprints.digestObjects(two));
    }

    @Test
    void digestChangesWhenSetChanges() {
        String a = Fingerprints.digestObjects(Fingerprints.normalizeObjects(List.of("rec-a", "rec-b")));
        String b = Fingerprints.digestObjects(Fingerprints.normalizeObjects(List.of("rec-a", "rec-c")));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void digestIs64CharHex() {
        String digest = Fingerprints.digestObjects(Fingerprints.normalizeObjects(List.of("x")));
        assertThat(digest).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void jsonFieldsDoNotCollideOnUnescapedSeparators() {
        // 若直接以分隔符拼接，两组键会碰撞；JSON 结构保证不碰撞
        Map<String, Object> one = Fingerprints.fields("k", List.of("a/b", "c"));
        Map<String, Object> two = Fingerprints.fields("k", List.of("a", "b/c"));
        assertThat(Fingerprints.of(one)).isNotEqualTo(Fingerprints.of(two));
    }

    @Test
    void specialCharactersAreJsonEscapedAndStable() {
        Map<String, Object> one = Fingerprints.fields("k", List.of("a\"b", "c\nd"));
        Map<String, Object> two = Fingerprints.fields("k", List.of("a\"b", "c\nd"));
        assertThat(Fingerprints.of(one)).isEqualTo(Fingerprints.of(two));
    }

    @Test
    void fingerprintCoversAllResultFields() {
        Map<String, Object> base = Fingerprints.fields("operator", "s1", "operation", "QUERY", "balance", 8L);
        Map<String, Object> changedOperator = Fingerprints.fields("operator", "s2", "operation", "QUERY",
                "balance", 8L);
        Map<String, Object> changedOperation = Fingerprints.fields("operator", "s1", "operation", "CLEAR",
                "balance", 8L);
        Map<String, Object> changedBalance = Fingerprints.fields("operator", "s1", "operation", "QUERY",
                "balance", 7L);
        String baseFingerprint = Fingerprints.of(base);
        assertThat(Fingerprints.of(changedOperator)).isNotEqualTo(baseFingerprint);
        assertThat(Fingerprints.of(changedOperation)).isNotEqualTo(baseFingerprint);
        assertThat(Fingerprints.of(changedBalance)).isNotEqualTo(baseFingerprint);
    }

    @Test
    void sha256MatchesKnownVector() {
        // SHA-256("abc") 标准测试向量
        assertThat(Fingerprints.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
