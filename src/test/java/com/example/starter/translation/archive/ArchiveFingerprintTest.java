package com.example.starter.translation.archive;

import com.example.starter.translation.archive.ArchiveDtos.BatchArchiveItem;
import com.example.starter.translation.archive.ArchiveDtos.BatchArchiveRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等指纹纯单元测试：操作者、操作类型、规范化对象集合都参与指纹；
 * 集合经规范化后序列化而非分隔符拼接，避免歧义碰撞。
 */
class ArchiveFingerprintTest {

    private final ArchiveFingerprint fingerprint = new ArchiveFingerprint(new ObjectMapper());

    @Test
    @DisplayName("语种集合规范化：trim、小写、去重、字典序，不依赖请求顺序")
    void normalizeLanguageSet() {
        assertThat(ArchiveFingerprint.normalizeLanguageSet(List.of("JA", " en", "En", "ja ", "de")))
                .containsExactly("de", "en", "ja");
    }

    @Test
    @DisplayName("同参指纹稳定：大小写、空白与顺序不同但语义相同的集合得到同一指纹")
    void equivalentRequestsSameFingerprint() {
        String op = "POST /api/documents/1/archives";
        String first = fingerprint.single(op, "alice", 3, List.of("EN", " ja"));
        String second = fingerprint.single(op, "alice", 3, List.of("JA", "en ", "en"));
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("操作者不同则指纹不同：指纹必须包含操作者")
    void differentActorsDifferentFingerprint() {
        String op = "POST /api/documents/1/archives";
        assertThat(fingerprint.single(op, "alice", 1, List.of("en")))
                .isNotEqualTo(fingerprint.single(op, "bob", 1, List.of("en")));
    }

    @Test
    @DisplayName("操作类型或影响结果字段不同则指纹不同")
    void differentOperationAndFields() {
        String alice = "alice";
        String create = "POST /api/documents/1/archives";
        String revoke = "POST /api/documents/1/archives/1/revoke";
        assertThat(fingerprint.single(create, alice, 1, List.of("en")))
                .isNotEqualTo(fingerprint.single(create, alice, 2, List.of("en")));
        assertThat(fingerprint.single(create, alice, 1, List.of("en")))
                .isNotEqualTo(fingerprint.single(create, alice, 1, List.of("ja")));
        assertThat(fingerprint.action(revoke, alice, 1, "原因A"))
                .isNotEqualTo(fingerprint.action(revoke, alice, 1, "原因B"));
    }

    @Test
    @DisplayName("集合不经未转义分隔符拼接：含逗号的值与两个独立语种指纹不同")
    void noDelimiterConcatenationCollision() {
        String op = "POST /api/documents/1/archives";
        String singleValueWithComma = fingerprint.single(op, "alice", 1, List.of("en,ja"));
        String twoLanguages = fingerprint.single(op, "alice", 1, List.of("en", "ja"));
        assertThat(singleValueWithComma).isNotEqualTo(twoLanguages);
    }

    @Test
    @DisplayName("批量指纹：项顺序不同得到相同指纹；内容不同则不同")
    void batchOrderInsensitive() {
        String op = "POST /api/documents/1/archives/batch";
        BatchArchiveRequest ab = new BatchArchiveRequest("r1", List.of(
                new BatchArchiveItem(1, List.of("en")),
                new BatchArchiveItem(2, List.of("JA", "en"))));
        BatchArchiveRequest ba = new BatchArchiveRequest("r2", List.of(
                new BatchArchiveItem(2, List.of("en", "ja")),
                new BatchArchiveItem(1, List.of(" EN "))));
        assertThat(fingerprint.batch(op, "alice", ab))
                .isEqualTo(fingerprint.batch(op, "alice", ba));

        BatchArchiveRequest different = new BatchArchiveRequest("r3", List.of(
                new BatchArchiveItem(1, List.of("en")),
                new BatchArchiveItem(2, List.of("en"))));
        assertThat(fingerprint.batch(op, "alice", ab))
                .isNotEqualTo(fingerprint.batch(op, "alice", different));
    }
}
