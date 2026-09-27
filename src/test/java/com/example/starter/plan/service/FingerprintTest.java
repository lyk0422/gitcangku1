package com.example.starter.plan.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 幂等指纹编码单元测试：长度前缀编码对分隔符注入无歧义，
 * 集合先写元素个数，null 与空串、不同切分方式不得碰撞。
 */
class FingerprintTest {

    @Test
    void lengthPrefixEncodingPreventsSeparatorInjection() {
        // 内容自身包含冒号/数字前缀也不会产生歧义
        Fingerprint a = Fingerprint.of("OP").field("1:ab").field("c");
        Fingerprint b = Fingerprint.of("OP").field("1").field(":abc");
        assertThat(a.sha256()).isNotEqualTo(b.sha256());

        // null 与空串编码不同
        Fingerprint withNull = Fingerprint.of("OP").field(null);
        Fingerprint withEmpty = Fingerprint.of("OP").field("");
        assertThat(withNull.sha256()).isNotEqualTo(withEmpty.sha256());

        // 连续字段与单个合并字段不同（边界被长度锁死）
        Fingerprint split = Fingerprint.of("OP").field("ab").field("cd");
        Fingerprint merged = Fingerprint.of("OP").field("abcd");
        assertThat(split.sha256()).isNotEqualTo(merged.sha256());
    }

    @Test
    void collectionEncodingIncludesCountAndEachElement() {
        // 空集合与含空串元素的集合不同
        String emptySet = Fingerprint.of("OP").fields(List.of()).sha256();
        String oneBlank = Fingerprint.of("OP").fields(List.of("")).sha256();
        assertThat(emptySet).isNotEqualTo(oneBlank);

        // 不同切分不碰撞：["a", "bc"] 与 ["ab", "c"]
        String split1 = Fingerprint.of("OP").fields(List.of("a", "bc")).sha256();
        String split2 = Fingerprint.of("OP").fields(List.of("ab", "c")).sha256();
        assertThat(split1).isNotEqualTo(split2);
    }

    @Test
    void sameFieldsProduceSameDigest() {
        String h1 = Fingerprint.of("OP").field("u1").fields(List.of("a", "b")).sha256();
        String h2 = Fingerprint.of("OP").field("u1").fields(List.of("a", "b")).sha256();
        assertThat(h1).isEqualTo(h2);
    }
}
