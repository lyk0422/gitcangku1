package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 来源策略校验器单元测试：缺证明、撤销、摘要不匹配、等级不足、完整传递路径与合规主流程。
 */
class ProvenancePolicyEvaluatorTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private record Coord(String name, int version) {
        @Override
        public String toString() {
            return name + ":" + version;
        }
    }

    /** 构造 name -> 选中版本（含依赖声明）的解析结果，按名称升序。 */
    private static TreeMap<String, ArtifactVersion> chosen(Object... nodes) {
        TreeMap<String, ArtifactVersion> map = new TreeMap<>();
        AtomicLong id = new AtomicLong(1);
        for (int i = 0; i < nodes.length; i += 2) {
            String name = (String) nodes[i];
            @SuppressWarnings("unchecked")
            List<DependencyRange> deps = (List<DependencyRange>) nodes[i + 1];
            // 版本取坐标标记，未提供时默认 1。
            int version = name.equals("app") ? 1 : (name.startsWith("lib") ? 2 : 1);
            map.put(name, new ArtifactVersion(id.getAndIncrement(), name, version, false, deps));
        }
        return map;
    }

    private static DependencyRange dep(String name, int min, int max) {
        return new DependencyRange(name, min, max);
    }

    private static PolicyCoordinate pc(String name, int level, String digest) {
        return new PolicyCoordinate(name, level, digest);
    }

    private static ProvenanceAttestation attest(long id, String name, int version,
                                                String repo, String digest, int level,
                                                boolean revoked) {
        return new ProvenanceAttestation(id, name, version, repo, digest, level,
                "alice", revoked, T0);
    }

    private static ProvenancePolicy policy(PolicyCoordinate... coordinates) {
        return new ProvenancePolicy("graph-a", 1, T0, List.of(coordinates));
    }

    /** 构造证明查询表：坐标 -> 证明（null 表示无证明）。 */
    private static ProvenancePolicyEvaluator.AttestationLookup lookup(Map<Coord, ProvenanceAttestation> table) {
        return (name, version) -> {
            ProvenanceAttestation a = table.get(new Coord(name, version));
            if (a == null) {
                return new ProvenancePolicyEvaluator.LookupResult(
                        ProvenancePolicyEvaluator.LookupResult.State.ABSENT, null);
            }
            if (a.revoked()) {
                return new ProvenancePolicyEvaluator.LookupResult(
                        ProvenancePolicyEvaluator.LookupResult.State.REVOKED, a);
            }
            return new ProvenancePolicyEvaluator.LookupResult(
                    ProvenancePolicyEvaluator.LookupResult.State.VALID, a);
        };
    }

    @Test
    void allCoordinatesValidProducesNoViolations() {
        // app:1 -> lib[2,2] -> util[1,1]
        TreeMap<String, ArtifactVersion> chosen = chosen(
                "app", List.of(dep("lib", 2, 2)),
                "lib", List.of(dep("util", 1, 1)),
                "util", List.of());
        Map<Coord, ProvenanceAttestation> table = Map.of(
                new Coord("app", 1), attest(1, "app", 1, "repo", "d-app", 3, false),
                new Coord("lib", 2), attest(2, "lib", 2, "repo", "d-lib", 3, false),
                new Coord("util", 1), attest(3, "util", 1, "repo", "d-util", 3, false));
        ProvenancePolicy policy = policy(
                pc("app", 1, ""), pc("lib", 2, "d-lib"), pc("util", 2, ""));

        List<ProvenancePolicyEvaluator.CoordinateProvenance> inspected =
                ProvenancePolicyEvaluator.inspect(chosen, "app", policy, lookup(table));
        assertThat(inspected).allMatch(ProvenancePolicyEvaluator.CoordinateProvenance::matched);
        assertThat(ProvenancePolicyEvaluator.violations(inspected, policy)).isEmpty();
    }

    @Test
    void missingAttestationOnTransitiveCoordinateReportsFullPath() {
        TreeMap<String, ArtifactVersion> chosen = chosen(
                "app", List.of(dep("lib", 2, 2)),
                "lib", List.of(dep("util", 1, 1)),
                "util", List.of());
        Map<Coord, ProvenanceAttestation> table = Map.of(
                new Coord("app", 1), attest(1, "app", 1, "repo", "d", 1, false),
                new Coord("lib", 2), attest(2, "lib", 2, "repo", "d", 1, false));
        ProvenancePolicy policy = policy(pc("app", 1, ""), pc("lib", 1, ""), pc("util", 1, ""));

        List<ProvenanceViolation> violations = ProvenancePolicyEvaluator.evaluate(
                chosen, "app", policy, lookup(table));

        assertThat(violations).hasSize(1);
        ProvenanceViolation v = violations.get(0);
        assertThat(v.reason()).isEqualTo(ProvenanceViolation.MISSING_ATTESTATION);
        assertThat(v.path()).containsExactly("app:1", "lib:2", "util:1");
        assertThat(v.detail()).contains("util:1");
    }

    @Test
    void revokedAttestationIsDistinctFromMissingAndReportsPath() {
        TreeMap<String, ArtifactVersion> chosen = chosen("app", List.of());
        Map<Coord, ProvenanceAttestation> table = Map.of(
                new Coord("app", 1), attest(7, "app", 1, "repo", "d", 5, true));
        List<ProvenanceViolation> violations = ProvenancePolicyEvaluator.evaluate(
                chosen, "app", policy(pc("app", 1, "")), lookup(table));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).reason()).isEqualTo(ProvenanceViolation.ATTESTATION_REVOKED);
        assertThat(violations.get(0).path()).containsExactly("app:1");
    }

    @Test
    void digestMismatchAndInsufficientLevelAreDistinctReasons() {
        TreeMap<String, ArtifactVersion> chosen = chosen(
                "app", List.of(dep("lib", 2, 2)),
                "lib", List.of());
        Map<Coord, ProvenanceAttestation> table = Map.of(
                new Coord("app", 1), attest(1, "app", 1, "repo", "wrong-digest", 5, false),
                new Coord("lib", 2), attest(2, "lib", 2, "repo", "d", 1, false));
        ProvenancePolicy policy = policy(
                pc("app", 1, "expected-digest"),
                pc("lib", 5, ""));

        List<ProvenanceViolation> violations = ProvenancePolicyEvaluator.evaluate(
                chosen, "app", policy, lookup(table));

        assertThat(violations).extracting(ProvenanceViolation::reason)
                .containsExactly(ProvenanceViolation.DIGEST_MISMATCH,
                        ProvenanceViolation.LEVEL_INSUFFICIENT);
        assertThat(violations.get(0).detail()).contains("expected-digest").contains("wrong-digest");
        assertThat(violations.get(1).detail()).contains("required>=5").contains("actual=1");
    }

    @Test
    void coordinateWithoutPolicyEntryStillRequiresValidAttestation() {
        TreeMap<String, ArtifactVersion> chosen = chosen(
                "app", List.of(dep("lib", 2, 2)),
                "lib", List.of());
        Map<Coord, ProvenanceAttestation> table = Map.of(
                new Coord("app", 1), attest(1, "app", 1, "repo", "d", 9, false));
        // 策略只列了 app，lib 未列但仍须有效证明。
        ProvenanceViolation violation = ProvenancePolicyEvaluator.evaluate(
                chosen, "app", policy(pc("app", 1, "")), lookup(table))
                .get(0);
        assertThat(violation.reason()).isEqualTo(ProvenanceViolation.MISSING_ATTESTATION);
        assertThat(violation.path()).containsExactly("app:1", "lib:2");
    }

    @Test
    void provenancePathsFollowDependencyEdgesAndRootPathIsSingleton() {
        TreeMap<String, ArtifactVersion> chosen = chosen(
                "app", List.of(dep("lib", 2, 2)),
                "lib", List.of(dep("util", 1, 1)),
                "util", List.of());
        Map<Coord, ProvenanceAttestation> table = Map.of(
                new Coord("app", 1), attest(1, "app", 1, "r", "d", 1, false),
                new Coord("lib", 2), attest(2, "lib", 2, "r", "d", 1, false),
                new Coord("util", 1), attest(3, "util", 1, "r", "d", 1, false));

        List<ProvenancePolicyEvaluator.CoordinateProvenance> inspected =
                ProvenancePolicyEvaluator.inspect(chosen, "app",
                        policy(pc("app", 1, ""), pc("lib", 1, ""), pc("util", 1, "")),
                        lookup(table));

        assertThat(inspected).extracting(cp -> cp.name() + "|" + cp.path())
                .containsExactly(
                        "app|[app:1]",
                        "lib|[app:1, lib:2]",
                        "util|[app:1, lib:2, util:1]");
    }
}
