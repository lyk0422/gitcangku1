package com.example.starter.plan.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 幂等指纹构建器单元测试：长度前缀编码保证字段边界无歧义，
 * 集合元素内容包含分隔符时也不会产生碰撞。
 */
class FingerprintBuilderTest {

    @Test
    void fieldBoundariesAreUnambiguous() {
        String h1 = new FingerprintBuilder("OP").field("ab").field("c").sha256();
        String h2 = new FingerprintBuilder("OP").field("a").field("bc").sha256();
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void delimiterCharactersInValuesDoNotCollide() {
        // 值内包含换行与冒号（拼接式编码的注入向量）时，不同字段划分仍产生不同指纹
        String h1 = new FingerprintBuilder("OP").field("1:a\n2:b").field("x").sha256();
        String h2 = new FingerprintBuilder("OP").field("1:a").field("2:b\nx").sha256();
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void nullAndEmptyStringAreDistinct() {
        String nullHash = new FingerprintBuilder("OP").field(null).sha256();
        String emptyHash = new FingerprintBuilder("OP").field("").sha256();
        assertThat(nullHash).isNotEqualTo(emptyHash);
    }

    @Test
    void sameInputProducesSameHash() {
        String h1 = new FingerprintBuilder("OP").field("k").field(null).field("v").sha256();
        String h2 = new FingerprintBuilder("OP").field("k").field(null).field("v").sha256();
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64);
    }

    @Test
    void opTypeParticipatesInHash() {
        String h1 = new FingerprintBuilder("OP_A").field("k").sha256();
        String h2 = new FingerprintBuilder("OP_B").field("k").sha256();
        assertThat(h1).isNotEqualTo(h2);
    }
}
