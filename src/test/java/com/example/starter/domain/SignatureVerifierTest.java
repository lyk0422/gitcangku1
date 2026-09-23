package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 签名阈值验证器单元测试：阈值计数、钥匙撤销过滤、摘要不符硬失败与排序计入集合。
 */
class SignatureVerifierTest {

    private static final String DIGEST = "a".repeat(64);
    private static final String OTHER_DIGEST = "b".repeat(64);

    private static SigningPolicy policy(int threshold, String... keyIds) {
        return new SigningPolicy(1L, List.of(keyIds), threshold, Instant.EPOCH);
    }

    private static ArtifactSignature signature(String keyId, String digest) {
        return new ArtifactSignature(1L, keyId, digest, Instant.EPOCH);
    }

    @Test
    void thresholdMetReturnsSortedSignerKeyIds() {
        SigningPolicy policy = policy(2, "k1", "k2", "k3");
        List<ArtifactSignature> signatures = List.of(
                signature("k3", DIGEST), signature("k1", DIGEST));

        SignatureVerifier.Result result = SignatureVerifier.verify(
                policy, DIGEST, signatures, Set.of());

        assertThat(result.success()).isTrue();
        assertThat(result.reason()).isNull();
        assertThat(result.signerKeyIds()).containsExactly("k1", "k3");
    }

    @Test
    void thresholdExactlyOnePasses() {
        SigningPolicy policy = policy(1, "k1");
        SignatureVerifier.Result result = SignatureVerifier.verify(
                policy, DIGEST, List.of(signature("k1", DIGEST)), Set.of());
        assertThat(result.success()).isTrue();
        assertThat(result.signerKeyIds()).containsExactly("k1");
    }

    @Test
    void insufficientTrustedSignaturesFails() {
        SigningPolicy policy = policy(2, "k1", "k2");
        SignatureVerifier.Result result = SignatureVerifier.verify(
                policy, DIGEST, List.of(signature("k1", DIGEST)), Set.of());
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(SignatureVerifier.FailureReason.INSUFFICIENT_SIGNATURES);
        assertThat(result.signerKeyIds()).containsExactly("k1");
    }

    @Test
    void untrustedKeySignaturesDoNotCountTowardThreshold() {
        SigningPolicy policy = policy(2, "k1", "k2");
        SignatureVerifier.Result result = SignatureVerifier.verify(
                policy, DIGEST,
                List.of(signature("k1", DIGEST), signature("outsider", DIGEST)),
                Set.of());
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(SignatureVerifier.FailureReason.INSUFFICIENT_SIGNATURES);
        assertThat(result.signerKeyIds()).containsExactly("k1");
    }

    @Test
    void revokedKeySignaturesDoNotCountTowardThreshold() {
        SigningPolicy policy = policy(2, "k1", "k2");
        SignatureVerifier.Result result = SignatureVerifier.verify(
                policy, DIGEST,
                List.of(signature("k1", DIGEST), signature("k2", DIGEST)),
                Set.of("k2"));
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(SignatureVerifier.FailureReason.INSUFFICIENT_SIGNATURES);
        assertThat(result.signerKeyIds()).containsExactly("k1");
    }

    @Test
    void revokedKeyNotInCurrentPolicyStillExcludedWhenListed() {
        // 历史策略可信、当前策略仍列出的已撤销钥匙不得计入。
        SigningPolicy policy = policy(1, "old", "new");
        SignatureVerifier.Result result = SignatureVerifier.verify(
                policy, DIGEST, List.of(signature("old", DIGEST)), Set.of("old"));
        assertThat(result.success()).isFalse();
        assertThat(result.signerKeyIds()).isEmpty();
    }

    @Test
    void digestMismatchFromTrustedActiveKeyIsHardFailure() {
        SigningPolicy policy = policy(1, "k1", "k2");
        SignatureVerifier.Result result = SignatureVerifier.verify(
                policy, DIGEST,
                List.of(signature("k1", OTHER_DIGEST), signature("k2", DIGEST)),
                Set.of());
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(SignatureVerifier.FailureReason.DIGEST_MISMATCH);
        // 匹配的钥匙仍被收集为证据，但整体失败。
        assertThat(result.signerKeyIds()).containsExactly("k2");
    }

    @Test
    void digestMismatchFromRevokedKeyIsIgnored() {
        // 已撤销钥匙的坏签名既不计入也不触发硬失败：撤销后历史签名不改写。
        SigningPolicy policy = policy(1, "k1", "k2");
        SignatureVerifier.Result result = SignatureVerifier.verify(
                policy, DIGEST,
                List.of(signature("k1", DIGEST), signature("k2", OTHER_DIGEST)),
                Set.of("k2"));
        assertThat(result.success()).isTrue();
        assertThat(result.signerKeyIds()).containsExactly("k1");
    }

    @Test
    void digestMismatchFromUntrustedKeyIsIgnored() {
        SigningPolicy policy = policy(1, "k1");
        SignatureVerifier.Result result = SignatureVerifier.verify(
                policy, DIGEST,
                List.of(signature("k1", DIGEST), signature("outsider", OTHER_DIGEST)),
                Set.of());
        assertThat(result.success()).isTrue();
        assertThat(result.signerKeyIds()).containsExactly("k1");
    }

    @Test
    void duplicateKeySignaturesAreDeduplicated() {
        // 同一钥匙仅一份签名由数据库保证；纯函数对重复输入做去重防御。
        SigningPolicy policy = policy(2, "k1", "k2");
        SignatureVerifier.Result result = SignatureVerifier.verify(
                policy, DIGEST,
                List.of(signature("k1", DIGEST), signature("k1", DIGEST)),
                Set.of());
        assertThat(result.success()).isFalse();
        assertThat(result.signerKeyIds()).containsExactly("k1");
    }
}
