package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定解析器单元测试：回溯、环依赖、撤回版本过滤、字典序解析顺序与能力互斥。
 */
class LockResolverTest {

    /** 测试用快照构造器：按 name -> 版本（版本号，是否撤回，依赖列表）组织，版本降序排列。 */
    private static RepositorySnapshot snapshot(long version, Object... groups) {
        return snapshotWithExclusions(Set.of(), groups);
    }

    /** 带互斥规则的快照构造器。 */
    private static RepositorySnapshot snapshotWithExclusions(Set<CapabilityPair> exclusions,
                                                             Object... groups) {
        Map<String, List<ArtifactVersion>> map = new TreeMap<>();
        AtomicLong id = new AtomicLong(1);
        for (int i = 0; i < groups.length; i += 2) {
            String name = (String) groups[i];
            @SuppressWarnings("unchecked")
            List<TestVersion> versions = (List<TestVersion>) groups[i + 1];
            List<ArtifactVersion> list = versions.stream()
                    .map(v -> new ArtifactVersion(id.getAndIncrement(), name, v.version(),
                            v.withdrawn(), v.deps(), v.caps()))
                    .toList();
            map.put(name, list);
        }
        return new RepositorySnapshot(0, map, exclusions);
    }

    private record TestVersion(int version, boolean withdrawn, List<DependencyRange> deps,
                               List<String> caps) {
    }

    private static TestVersion v(int version, DependencyRange... deps) {
        return new TestVersion(version, false, List.of(deps), List.of());
    }

    private static TestVersion w(int version, DependencyRange... deps) {
        return new TestVersion(version, true, List.of(deps), List.of());
    }

    /** 带能力声明的有效版本。 */
    private static TestVersion vc(int version, List<String> caps, DependencyRange... deps) {
        return new TestVersion(version, false, List.of(deps), caps);
    }

    private static DependencyRange dep(String name, int min, int max) {
        return new DependencyRange(name, min, max);
    }

    @Test
    void resolvesSingleRootWithoutDependencies() {
        RepositorySnapshot s = snapshot(0, "app", List.of(v(1)));
        assertThat(LockResolver.resolve(s, "app", 1))
                .isEqualTo(Map.of("app", 1));
    }

    @Test
    void requiresBacktrackingWhenHighestCandidateConflictsLater() {
        // app:1 -> B[1,2] 且 C[1,1]；B2 -> C[2,2]（与 C1 冲突），必须回退到 B1。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("b", 1, 2), dep("c", 1, 1))),
                "b", List.of(v(2, dep("c", 2, 2)), v(1)),
                "c", List.of(v(1)));

        Map<String, Integer> result = LockResolver.resolve(s, "app", 1);
        assertThat(result).isNotNull();
        assertThat(result).containsEntry("app", 1).containsEntry("b", 1).containsEntry("c", 1);
    }

    @Test
    void cannotGreedilyTakeHighestVersionAcrossMultipleLevels() {
        // app -> a[1,2]；a2 -> b[2,2]，a1 -> b[1,1]；b2 已撤回，b1 有效。
        // 贪心取 a2 将失败，必须回退到 a1/b1。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("a", 1, 2))),
                "a", List.of(v(2, dep("b", 2, 2)), v(1, dep("b", 1, 1))),
                "b", List.of(w(2), v(1)));

        assertThat(LockResolver.resolve(s, "app", 1))
                .isEqualTo(Map.of("app", 1, "a", 1, "b", 1));
    }

    @Test
    void detectsUnsatisfiableCycleAndReturnsNull() {
        // 根 app:1 固定，a1 -> app[2,2]，但 app 只能是 1，环冲突不可行。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("a", 1, 1)), v(2)),
                "a", List.of(v(1, dep("app", 2, 2))));

        assertThat(LockResolver.resolve(s, "app", 1)).isNull();
    }

    @Test
    void supportsConsistentCycle() {
        // app:1 -> a[1,1]，a1 -> app[1,1]：环但区间互相满足。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("a", 1, 1)), v(2, dep("a", 1, 1))),
                "a", List.of(v(1, dep("app", 1, 1))));

        assertThat(LockResolver.resolve(s, "app", 1))
                .isEqualTo(Map.of("app", 1, "a", 1));
    }

    @Test
    void selfDependencyMustMatchFixedRootVersion() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(2, dep("app", 2, 2)), v(1, dep("app", 1, 1))));
        assertThat(LockResolver.resolve(s, "app", 1)).isEqualTo(Map.of("app", 1));
        assertThat(LockResolver.resolve(s, "app", 2)).isEqualTo(Map.of("app", 2));
    }

    @Test
    void selfDependencyMismatchIsInfeasible() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("app", 2, 2)), v(2)));
        assertThat(LockResolver.resolve(s, "app", 1)).isNull();
    }

    @Test
    void skipsWithdrawnCandidates() {
        // b2 已撤回，只能取 b1。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("b", 1, 2))),
                "b", List.of(w(2), v(1)));
        assertThat(LockResolver.resolve(s, "app", 1))
                .isEqualTo(Map.of("app", 1, "b", 1));
    }

    @Test
    void returnsNullWhenAllCandidatesWithdrawn() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("b", 1, 2))),
                "b", List.of(w(2), w(1)));
        assertThat(LockResolver.resolve(s, "app", 1)).isNull();
    }

    @Test
    void returnsNullWhenDependencyNameMissing() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("ghost", 1, 1))));
        assertThat(LockResolver.resolve(s, "app", 1)).isNull();
    }

    @Test
    void intersectsRangesFromMultipleSelectedArtifacts() {
        // app -> b[1,3] 且 c1 -> b[3,3]，交集只剩 b3。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("b", 1, 3), dep("c", 1, 1))),
                "b", List.of(v(3), v(2), v(1)),
                "c", List.of(v(1, dep("b", 3, 3))));
        assertThat(LockResolver.resolve(s, "app", 1))
                .isEqualTo(Map.of("app", 1, "b", 3, "c", 1));
    }

    @Test
    void emptyRangeIntersectionIsInfeasible() {
        // app -> b[1,2] 且 c1 -> b[3,3]，交集为空。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("b", 1, 2), dep("c", 1, 1))),
                "b", List.of(v(3), v(2), v(1)),
                "c", List.of(v(1, dep("b", 3, 3))));
        assertThat(LockResolver.resolve(s, "app", 1)).isNull();
    }

    @Test
    void resolvesDependenciesInLexicographicNameOrder() {
        // 字典序下 zeta 先于 zeta-child 被解析；构造需要跨层回溯的场景。
        // app -> zeta[1,2]；zeta2 -> mid[2,2]（mid2 撤回），zeta1 -> mid[1,1]。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("zeta", 1, 2))),
                "zeta", List.of(v(2, dep("mid", 2, 2)), v(1, dep("mid", 1, 1))),
                "mid", List.of(w(2), v(1)));
        assertThat(LockResolver.resolve(s, "app", 1))
                .isEqualTo(Map.of("app", 1, "zeta", 1, "mid", 1));
    }

    @Test
    void resultKeysAreSortedByName() {
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, dep("zlib", 1, 1), dep("alib", 1, 1))),
                "zlib", List.of(v(1)),
                "alib", List.of(v(1)));
        Map<String, Integer> result = LockResolver.resolve(s, "root", 1);
        assertThat(result).isNotNull();
        assertThat(result.keySet()).containsExactly("alib", "root", "zlib");
    }
    // ------------------------------------------------------------------
    // 能力互斥
    // ------------------------------------------------------------------

    @Test
    void mutuallyExclusiveCapabilitiesForceBacktrackToLowerVersion() {
        // 合成场景：根提供 green，X2 提供 red，X1 提供 blue，red 与 green 互斥。
        // 候选降序先取 X2 冲突，必须回退到 X1 并锁定 blue。
        RepositorySnapshot s = snapshotWithExclusions(
                Set.of(CapabilityPair.of("red", "green")),
                "root", List.of(vc(1, List.of("green"), dep("x", 1, 2))),
                "x", List.of(vc(2, List.of("red")), vc(1, List.of("blue"))));

        Map<String, Integer> result = LockResolver.resolve(s, "root", 1);
        assertThat(result).isEqualTo(Map.of("root", 1, "x", 1));
    }

    @Test
    void exclusionPairIsUnordered() {
        // 反向登记 (green, red) 与 (red, green) 是同一对。
        RepositorySnapshot s = snapshotWithExclusions(
                Set.of(CapabilityPair.of("green", "red")),
                "root", List.of(vc(1, List.of("green"), dep("x", 1, 2))),
                "x", List.of(vc(2, List.of("red")), vc(1, List.of("blue"))));

        assertThat(LockResolver.resolve(s, "root", 1))
                .isEqualTo(Map.of("root", 1, "x", 1));
    }

    @Test
    void conflictWithinSingleArtifactIsInfeasible() {
        // 同一制品内部同时声明两个互斥能力：该版本自身即不可行。
        RepositorySnapshot s = snapshotWithExclusions(
                Set.of(CapabilityPair.of("red", "green")),
                "root", List.of(vc(1, List.of("green", "red"))));
        assertThat(LockResolver.resolve(s, "root", 1)).isNull();
    }

    @Test
    void conflictBetweenTransitiveDependenciesIsInfeasible() {
        // root -> a[1,1] 且 b[1,1]；a 提供 red，b 提供 green，二者互斥，无解。
        RepositorySnapshot s = snapshotWithExclusions(
                Set.of(CapabilityPair.of("red", "green")),
                "root", List.of(v(1, dep("a", 1, 1), dep("b", 1, 1))),
                "a", List.of(vc(1, List.of("red"))),
                "b", List.of(vc(1, List.of("green"))));
        assertThat(LockResolver.resolve(s, "root", 1)).isNull();
    }

    @Test
    void allCandidatesConflictReturnsNull() {
        // x 的全部版本都与根能力冲突，整体不可行。
        RepositorySnapshot s = snapshotWithExclusions(
                Set.of(CapabilityPair.of("red", "green")),
                "root", List.of(vc(1, List.of("green"), dep("x", 1, 2))),
                "x", List.of(vc(2, List.of("red")), vc(1, List.of("red"))));
        assertThat(LockResolver.resolve(s, "root", 1)).isNull();
    }

    @Test
    void nonConflictingCapabilitiesResolveHighestVersion() {
        // 能力不冲突时仍取最高版本。
        RepositorySnapshot s = snapshotWithExclusions(
                Set.of(CapabilityPair.of("red", "green")),
                "root", List.of(vc(1, List.of("green"), dep("x", 1, 2))),
                "x", List.of(vc(2, List.of("blue")), vc(1, List.of("red"))));
        assertThat(LockResolver.resolve(s, "root", 1))
                .isEqualTo(Map.of("root", 1, "x", 2));
    }

    @Test
    void artifactProvidingBothPairMembersCannotBeSelected() {
        // x2 自身同时提供 red 与 green（与根的 green 叠加仍冲突），回退到 x1。
        RepositorySnapshot s = snapshotWithExclusions(
                Set.of(CapabilityPair.of("red", "green")),
                "root", List.of(vc(1, List.of("green"), dep("x", 1, 2))),
                "x", List.of(vc(2, List.of("red", "green")), vc(1, List.of("blue"))));
        assertThat(LockResolver.resolve(s, "root", 1))
                .isEqualTo(Map.of("root", 1, "x", 1));
    }

    @Test
    void capabilityPairNormalizesOrderAndRejectsBlank() {
        CapabilityPair pair = CapabilityPair.of("green", "red");
        assertThat(pair.first()).isEqualTo("green");
        assertThat(pair.second()).isEqualTo("red");
        assertThat(pair.matches("red", "green")).isTrue();
        assertThat(pair.matches("green", "red")).isTrue();
        assertThat(pair.matches("red", "blue")).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> CapabilityPair.of(" ", "red"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}