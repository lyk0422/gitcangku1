package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内容校验摘要格式策略单元测试。
 */
class ChecksumPolicyTest {

    private static final String VALID = "sha256:" + "a".repeat(64);

    @Test
    void acceptsCanonicalSha256LowerHex() {
        assertThat(ChecksumPolicy.isValid(VALID)).isTrue();
        assertThat(ChecksumPolicy.isValid("sha256:" + "0123456789abcdef".repeat(4))).isTrue();
    }

    @Test
    void rejectsNullBlankAndWrongPrefix() {
        assertThat(ChecksumPolicy.isValid(null)).isFalse();
        assertThat(ChecksumPolicy.isValid("")).isFalse();
        assertThat(ChecksumPolicy.isValid("   ")).isFalse();
        assertThat(ChecksumPolicy.isValid("sha1:" + "a".repeat(64))).isFalse();
        assertThat(ChecksumPolicy.isValid("a".repeat(64))).isFalse();
    }

    @Test
    void rejectsWrongLengthAndUpperCase() {
        assertThat(ChecksumPolicy.isValid("sha256:" + "a".repeat(63))).isFalse();
        assertThat(ChecksumPolicy.isValid("sha256:" + "a".repeat(65))).isFalse();
        assertThat(ChecksumPolicy.isValid("sha256:" + "A".repeat(64))).isFalse();
        assertThat(ChecksumPolicy.isValid("sha256:" + "g".repeat(64))).isFalse();
    }

    @Test
    void requiresTwoWitnesses() {
        assertThat(ChecksumPolicy.REQUIRED_WITNESSES).isEqualTo(2);
    }
}
