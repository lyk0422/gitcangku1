package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 见证闸门口径下的锁定解析单元测试：
 * 高版本未见证时回溯到合格低版本；全部未见证时解析失败。
 */
class AttestedLockResolverTest {

    private static final String C = "sha256:" + "c".repeat(64);
    private static final Instant T = Instant.parse("2026-09-28T00:00:00Z");

    private static Attestation w(String role) {
        return new Attestation(role.hashCode(), 0L, role, C, true, T, null, null);
    }

    /** 带完整双人见证的版本。 */
    private static TestVersion ok(int version, DependencyRange... deps) {
        return new TestVersion(version, false, List.of(deps),
                C, List.of(w("alice"), w("bob")));
    }

    /** 无摘要无见证的版本（区间可行但不能入锁闭包）。 */
    private static TestVersion no(int version, DependencyRange... deps) {
        return new TestVersion(version, false, List.of(deps), null, List.of());
    }

    /** 已撤回版本。 */
    private static TestVersion withdrawn(int version, DependencyRange... deps) {
        return new TestVersion(version, true, List.of(deps), C, List.of(w("alice"), w("bob")));
    }

    private record TestVersion(int version, boolean withdrawn, List<DependencyRange> deps,
                               String checksum, List<Attestation> attestations) {
    }

    private static RepositorySnapshot snapshot(Object... groups) {
        Map<String, List<ArtifactVersion>> map = new TreeMap<>();
        AtomicLong id = new AtomicLong(1);
        for (int i = 0; i < groups.length; i += 2) {
            String name = (String) groups[i];
            @SuppressWarnings("unchecked")
            List<TestVersion> versions = (List<TestVersion>) groups[i + 1];
            List<ArtifactVersion> list = versions.stream()
                    .map(v -> new ArtifactVersion(id.getAndIncrement(), name, v.version(),
                            v.withdrawn(), v.deps(), v.checksum(), v.attestations()))
                    .toList();
            map.put(name, list);
        }
        return new RepositorySnapshot(0, map);
    }

    private static DependencyRange dep(String name, int min, int max) {
        return new DependencyRange(name, min, max);
    }

    @Test
    void fallsBackFromHigherUnattestedToLowerAttestedVersion() {
        // app:1 -> lib[1,2]；lib2 无见证，lib1 双人见证，必须回退选 lib1。
        RepositorySnapshot s = snapshot(
                "app", List.of(ok(1, dep("lib", 1, 2))),
                "lib", List.of(no(2), ok(1)));

        Map<String, Integer> result = LockResolver.resolve(s, "app", 1,
                AttestationGate::isLockEligible);
        assertThat(result).isEqualTo(Map.of("app", 1, "lib", 1));
    }

    @Test
    void returnsNullWhenEveryCandidateUnattested() {
        RepositorySnapshot s = snapshot(
                "app", List.of(ok(1, dep("lib", 1, 2))),
                "lib", List.of(no(2), no(1)));

        assertThat(LockResolver.resolve(s, "app", 1, AttestationGate::isLockEligible)).isNull();
    }

    @Test
    void withdrawnCandidateSkippedEvenIfAttested() {
        RepositorySnapshot s = snapshot(
                "app", List.of(ok(1, dep("lib", 1, 2))),
                "lib", List.of(withdrawn(2), ok(1)));

        assertThat(LockResolver.resolve(s, "app", 1, AttestationGate::isLockEligible))
                .isEqualTo(Map.of("app", 1, "lib", 1));
    }

    @Test
    void rootWithoutAttestationIsRejected() {
        RepositorySnapshot s = snapshot(
                "app", List.of(no(1)),
                "lib", List.of(ok(1)));

        try {
            LockResolver.resolve(s, "app", 1, AttestationGate::isLockEligible);
            assertThat(false).as("根版本未见证应抛出 IllegalArgumentException").isTrue();
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("app:1");
        }
    }
}
