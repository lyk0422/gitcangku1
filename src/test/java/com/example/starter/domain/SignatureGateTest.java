package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 签名阈值校验器单元测试：阈值计数、撤销/非本策略钥匙排除、摘要不符与去重排序。
 */
class SignatureGateTest {

    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String OTHER_DIGEST =
            "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    private static ArtifactVersion artifact() {
        return new ArtifactVersion(1L, "app", 1, false, DIGEST, List.of());
    }

    private static SignaturePolicy policy(int threshold, String... keyIds) {
        return new SignaturePolicy(7L, List.of(keyIds), threshold,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static ArtifactSignature sig(String keyId, String digest) {
        return new ArtifactSignature(1L, keyId, digest, Instant.parse("2026-02-01T00:00:00Z"));
    }

    @Test
    void passesWhenEnoughQualifiedSignaturesAndReturnsSortedKeyIds() {
        SignaturePolicy policy = policy(2, "k1", "k2", "k3");
        SignatureGate.Result result = SignatureGate.evaluate(
                policy, Set.of(), artifact(),
                List.of(sig("k3", DIGEST), sig("k1", DIGEST)));

        assertThat(result).isInstanceOf(SignatureGate.Passed.class);
        assertThat(((SignatureGate.Passed) result).countedKeyIds())
                .containsExactly("k1", "k3");
    }

    @Test
    void revokedKeySignaturesDoNotCountTowardThreshold() {
        SignaturePolicy policy = policy(2, "k1", "k2");
        SignatureGate.Result result = SignatureGate.evaluate(
                policy, Set.of("k2"), artifact(),
                List.of(sig("k1", DIGEST), sig("k2", DIGEST)));

        assertThat(result).isInstanceOfSatisfying(SignatureGate.Failed.class,
                failed -> {
                    assertThat(failed.reason())
                            .isEqualTo(SignatureGate.FailureReason.INSUFFICIENT_SIGNATURES);
                    assertThat(failed.qualifiedCount()).isEqualTo(1);
                    assertThat(failed.threshold()).isEqualTo(2);
                });
    }

    @Test
    void keysOutsidePolicyAreIgnored() {
        SignaturePolicy policy = policy(2, "k1", "k2");
        SignatureGate.Result result = SignatureGate.evaluate(
                policy, Set.of(), artifact(),
                List.of(sig("k1", DIGEST), sig("legacy", DIGEST)));

        assertThat(result).isInstanceOfSatisfying(SignatureGate.Failed.class,
                failed -> assertThat(failed.qualifiedCount()).isEqualTo(1));
    }

    @Test
    void digestMismatchFromTrustedActiveKeyFailsNode() {
        SignaturePolicy policy = policy(1, "k1", "k2");
        SignatureGate.Result result = SignatureGate.evaluate(
                policy, Set.of(), artifact(),
                List.of(sig("k1", OTHER_DIGEST)));

        assertThat(result).isInstanceOfSatisfying(SignatureGate.Failed.class,
                failed -> assertThat(failed.reason())
                        .isEqualTo(SignatureGate.FailureReason.DIGEST_MISMATCH));
    }

    @Test
    void digestMismatchFromUntrustedOrRevokedKeyIsIgnored() {
        SignaturePolicy policy = policy(1, "k1");
        SignatureGate.Result result = SignatureGate.evaluate(
                policy, Set.of("k2"), artifact(),
                List.of(sig("k1", DIGEST), sig("k2", OTHER_DIGEST), sig("ghost", OTHER_DIGEST)));

        assertThat(result).isInstanceOf(SignatureGate.Passed.class);
        assertThat(((SignatureGate.Passed) result).countedKeyIds()).containsExactly("k1");
    }

    @Test
    void noSignaturesFailsWhenPolicyActive() {
        SignatureGate.Result result = SignatureGate.evaluate(
                policy(1, "k1"), Set.of(), artifact(), List.of());

        assertThat(result).isInstanceOfSatisfying(SignatureGate.Failed.class,
                failed -> {
                    assertThat(failed.reason())
                            .isEqualTo(SignatureGate.FailureReason.INSUFFICIENT_SIGNATURES);
                    assertThat(failed.qualifiedCount()).isZero();
                });
    }
}
