package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 见证闸门单元测试：撤回、摘要缺失、双人见证、摘要一致、撤销不计入与稳定原因。
 */
class AttestationGateTest {

    private static final String C1 = "sha256:" + "a".repeat(64);
    private static final String C2 = "sha256:" + "b".repeat(64);

    private static Attestation witness(String role, String checksum, boolean active) {
        return new Attestation(role.hashCode(), 1L, role, checksum, active,
                Instant.parse("2026-09-28T00:00:00Z"), null, null);
    }

    private static ArtifactVersion version(String checksum, Attestation... attestations) {
        return new ArtifactVersion(1L, "lib", 1, false, List.of(), checksum, List.of(attestations));
    }

    @Test
    void eligibleWhenTwoDifferentRolesWithMatchingChecksum() {
        ArtifactVersion v = version(C1, witness("alice", C1, true), witness("bob", C1, true));
        assertThat(AttestationGate.isLockEligible(v)).isTrue();
        assertThat(AttestationGate.violationReasons(v)).isEmpty();
    }

    @Test
    void withdrawnIsNotEligible() {
        ArtifactVersion v = new ArtifactVersion(1L, "lib", 1, true, List.of(), C1,
                List.of(witness("alice", C1, true), witness("bob", C1, true)));
        assertThat(AttestationGate.isLockEligible(v)).isFalse();
        assertThat(AttestationGate.violationReasons(v).get(0)).contains("已撤回");
    }

    @Test
    void missingChecksumReportsActualRequiredAndDelta() {
        ArtifactVersion v = version(null);
        List<String> reasons = AttestationGate.violationReasons(v);
        assertThat(AttestationGate.isLockEligible(v)).isFalse();
        assertThat(String.join(";", reasons)).contains("实际 0 条", "要求 1 条", "差额 1 条");
    }

    @Test
    void oneWitnessIsInsufficientWithDeltaOne() {
        ArtifactVersion v = version(C1, witness("alice", C1, true));
        List<String> reasons = AttestationGate.violationReasons(v);
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0)).contains("实际 1 个", "要求 2 个不同角色", "差额 1 个");
    }

    @Test
    void sameRoleTwiceCountsAsOneRole() {
        ArtifactVersion v = version(C1, witness("alice", C1, true), witness("alice", C1, true));
        // 同角色重复记录在库上由唯一索引阻止；闸门口径下仍按不同角色计数为 1。
        assertThat(AttestationGate.isLockEligible(v)).isFalse();
    }

    @Test
    void revokedWitnessDoesNotCount() {
        ArtifactVersion v = version(C1, witness("alice", C1, true), witness("bob", C1, false));
        List<String> reasons = AttestationGate.violationReasons(v);
        assertThat(AttestationGate.isLockEligible(v)).isFalse();
        assertThat(reasons.get(0)).contains("实际 1 个", "差额 1 个");
    }

    @Test
    void mismatchingChecksumWitnessDoesNotCountAndReportsDifference() {
        ArtifactVersion v = version(C1, witness("alice", C1, true), witness("bob", C2, true));
        List<String> reasons = AttestationGate.violationReasons(v);
        assertThat(AttestationGate.isLockEligible(v)).isFalse();
        // bob 的摘要与登记摘要不一致：既不计入双人见证，也单独给出不一致原因。
        assertThat(String.join(";", reasons)).contains("不一致");
        assertThat(String.join(";", reasons)).contains("bob");
    }

    @Test
    void reasonsAreStableAndOrdered() {
        ArtifactVersion v = version(C1);
        assertThat(AttestationGate.violationReasons(v))
                .hasSize(1)
                .first().asString().contains("有效见证角色数不足");

        ArtifactVersion noChecksum = version(null);
        assertThat(AttestationGate.violationReasons(noChecksum))
                .hasSize(1)
                .first().asString().contains("未登记内容校验摘要");
    }
}
