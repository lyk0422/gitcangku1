package com.example.starter.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等参数指纹工具的单元测试。
 */
class FingerprintsTest {

    @Test
    void sameInputsProduceSameFingerprint() {
        assertThat(Fingerprints.sha256("APPLY", "P1", "T", "2026-10-01T20:00:00Z"))
                .isEqualTo(Fingerprints.sha256("APPLY", "P1", "T", "2026-10-01T20:00:00Z"));
    }

    @Test
    void differentInputsProduceDifferentFingerprint() {
        assertThat(Fingerprints.sha256("APPLY", "P1"))
                .isNotEqualTo(Fingerprints.sha256("APPLY", "P2"));
        assertThat(Fingerprints.sha256("APPLY", "P"))
                .isNotEqualTo(Fingerprints.sha256("APPROVE", "P"));
    }

    @Test
    void fingerprintIsHexSha256Length() {
        assertThat(Fingerprints.sha256("x")).matches("[0-9a-f]{64}");
    }
}
