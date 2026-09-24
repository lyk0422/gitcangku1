package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 重解析诊断与差异分析的单元测试。
 */
class ReresolveAnalysisTest {

    /** 与 LockResolverTest 相同的快照构造器。 */
    private static RepositorySnapshot snapshot(long version, Object... groups) {
        Map<String, List<ArtifactVersion>> map = new TreeMap<>();
        AtomicLong id = new AtomicLong(1);
        for (int i = 0; i < groups.length; i += 2) {
            String name = (String) groups[i];
            @SuppressWarnings("unchecked")
            List<TestVersion> versions = (List<TestVersion>) groups[i + 1];
            List<ArtifactVersion> list = versions.stream()
                    .map(v -> new ArtifactVersion(id.getAndIncrement(), name, v.version(),
                            v.withdrawn(), v.deps()))
                    .toList();
            map.put(name, list);
        }
        return new RepositorySnapshot(version, map);
    }

    private record TestVersion(int version, boolean withdrawn, List<DependencyRange> deps) {
    }

    private static TestVersion v(int version, DependencyRange... deps) {
        return new TestVersion(version, false, List.of(deps));
    }

    private static TestVersion w(int version, DependencyRange... deps) {
        return new TestVersion(version, true, List.of(deps));
    }

    private static DependencyRange dep(String name, int min, int max) {
        return new DependencyRange(name, min, max);
    }

    // ------------------------------------------------------------------
    // 诊断版解析与原解析结果一致
    // ------------------------------------------------------------------

    @Test
    void diagnosisAgreesWithResolveForFeasibleBacktrackingCase() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("b", 1, 2), dep("c", 1, 1))),
                "b", List.of(v(2, dep("c", 2, 2)), v(1)),
                "c", List.of(v(1)));

        Map<String, Integer> plain = LockResolver.resolve(s, "app", 1);
        ResolutionResult diagnosed = LockResolver.resolveWithDiagnosis(s, "app", 1);

        assertThat(diagnosed.feasible()).isTrue();
        assertThat(diagnosed.solution()).isEqualTo(plain)
                .isEqualTo(Map.of("app", 1, "b", 1, "c", 1));
    }

    @Test
    void infeasibleWhenAllCandidatesWithdrawnReportsNameAndWithdrawnVersions() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("b", 1, 2))),
                "b", List.of(w(2), w(1)));

        ResolutionResult result = LockResolver.resolveWithDiagnosis(s, "app", 1);
        assertThat(result.feasible()).isFalse();
        assertThat(result.blocker().name()).isEqualTo("b");
        assertThat(result.blocker().reason()).isEqualTo(InfeasibleBlocker.VERSIONS_WITHDRAWN);
        assertThat(result.blocker().versions()).containsExactly(1, 2);
    }

    @Test
    void infeasibleWhenNameMissingReportsMissingVersion() {
        RepositorySnapshot s = snapshot(0, "app", List.of(v(1, dep("ghost", 3, 3))));

        ResolutionResult result = LockResolver.resolveWithDiagnosis(s, "app", 1);
        assertThat(result.feasible()).isFalse();
        assertThat(result.blocker().name()).isEqualTo("ghost");
        assertThat(result.blocker().reason()).isEqualTo(InfeasibleBlocker.VERSIONS_MISSING);
        assertThat(result.blocker().versions()).containsExactly(3);
    }

    @Test
    void infeasibleRangeIntersectionIsReported() {
        // 选择顺序：a 先于 n；a1 固定后 n 同时被 app 要求 [1,2]、被 a1 要求 [3,3]，交集为空。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("a", 1, 1), dep("n", 1, 2))),
                "a", List.of(v(1, dep("n", 3, 3))),
                "n", List.of(v(3), v(2), v(1)));

        ResolutionResult result = LockResolver.resolveWithDiagnosis(s, "app", 1);
        assertThat(result.feasible()).isFalse();
        assertThat(result.blocker().name()).isEqualTo("n");
        assertThat(result.blocker().reason())
                .isEqualTo(InfeasibleBlocker.RANGE_INTERSECTION_EMPTY);
    }

    @Test
    void infeasibleCandidateConflictingWithChosenIsReportedAtDeepestNode() {
        // app 固定 b1、c1；c1 要求 b[3,3]，与每个 b 候选冲突，最深失败点为 c。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("b", 1, 2), dep("c", 1, 1))),
                "b", List.of(v(2), v(1)),
                "c", List.of(v(1, dep("b", 3, 3))));

        ResolutionResult result = LockResolver.resolveWithDiagnosis(s, "app", 1);
        assertThat(result.feasible()).isFalse();
        assertThat(result.blocker().name()).isEqualTo("c");
        assertThat(result.blocker().reason())
                .isEqualTo(InfeasibleBlocker.RANGE_INTERSECTION_EMPTY);
        assertThat(result.blocker().versions()).containsExactly(1);
    }

    @Test
    void infeasibleSingleCandidateAgainstFixedOneIsReportedAtThatNode() {
        // 区间内唯一候选 b1 声明 c[2,2]，而已固定 c1 → 本节点区间冲突。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("b", 1, 1), dep("c", 1, 1))),
                "b", List.of(v(1, dep("c", 2, 2))),
                "c", List.of(v(1)));

        ResolutionResult result = LockResolver.resolveWithDiagnosis(s, "app", 1);
        assertThat(result.feasible()).isFalse();
        assertThat(result.blocker().name()).isEqualTo("c");
        assertThat(result.blocker().reason())
                .isEqualTo(InfeasibleBlocker.RANGE_INTERSECTION_EMPTY);
        assertThat(result.blocker().versions()).containsExactly(1, 2);
    }

    // ------------------------------------------------------------------
    // 差异分析
    // ------------------------------------------------------------------

    @Test
    void identicalCollectionsProduceNoDiff() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("lib", 1, 2))),
                "lib", List.of(v(2), v(1)));
        Map<String, Integer> locked = Map.of("app", 1, "lib", 1);
        List<ReresolveDiffer.Diff> diffs =
                ReresolveDiffer.diff(locked, locked, s);
        assertThat(diffs).isEmpty();
    }

    @Test
    void versionUpWhileOldStillValidIsSuperseded() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("lib", 1, 2))),
                "lib", List.of(v(2), v(1)));
        List<ReresolveDiffer.Diff> diffs = ReresolveDiffer.diff(
                Map.of("app", 1, "lib", 1),
                Map.of("app", 1, "lib", 2), s);
        assertThat(diffs).hasSize(1);
        ReresolveDiffer.Diff d = diffs.get(0);
        assertThat(d.name()).isEqualTo("lib");
        assertThat(d.changeType()).isEqualTo(ReresolveDiffer.VERSION_CHANGED);
        assertThat(d.originalVersion()).isEqualTo(1);
        assertThat(d.newVersion()).isEqualTo(2);
        assertThat(d.reason()).isEqualTo(ReresolveDiffer.SUPERSEDED_BY_HIGHER);
    }

    @Test
    void withdrawnOriginalVersionIsReportedAsWithdrawn() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("lib", 1, 2))),
                "lib", List.of(v(2), w(1)));
        List<ReresolveDiffer.Diff> diffs = ReresolveDiffer.diff(
                Map.of("app", 1, "lib", 1),
                Map.of("app", 1, "lib", 2), s);
        assertThat(diffs).extracting("name", "reason")
                .containsExactly(tuple(
                        "lib", ReresolveDiffer.ORIGINAL_WITHDRAWN));
    }

    @Test
    void downgradeBecauseOldVersionRequiresMissingDependencyIsRangeChange() {
        // 原锁定 lib2，它现在依赖 newdep[1,1]（可行解中无法满足：newdep 不存在），
        // 可行解回退 lib1。注意：实际可行解不会包含 newdep。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("lib", 1, 2))),
                "lib", List.of(v(2, dep("newdep", 1, 1)), v(1)));
        // 该快照下整体其实不可行；这里仅直接验证降级原因规则。
        List<ReresolveDiffer.Diff> diffs = ReresolveDiffer.diff(
                Map.of("app", 1, "lib", 2),
                Map.of("app", 1, "lib", 1), s);
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).reason()).isEqualTo(ReresolveDiffer.RANGE_NO_LONGER_SATISFIED);
        assertThat(diffs.get(0).newVersion()).isEqualTo(1);
    }

    @Test
    void addedAndRemovedNamesAreClassified() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("newlib", 1, 1))),
                "oldlib", List.of(v(1)),
                "newlib", List.of(v(1)));
        List<ReresolveDiffer.Diff> diffs = ReresolveDiffer.diff(
                Map.of("app", 1, "oldlib", 1),
                Map.of("app", 1, "newlib", 1), s);
        assertThat(diffs).extracting("name", "changeType", "reason")
                .containsExactly(
                        tuple("newlib", ReresolveDiffer.ADDED,
                                ReresolveDiffer.SUPERSEDED_BY_HIGHER),
                        tuple("oldlib", ReresolveDiffer.REMOVED,
                                ReresolveDiffer.RANGE_NO_LONGER_SATISFIED));
    }

    @Test
    void removedWithdrawnVersionIsReportedAsWithdrawn() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1)),
                "oldlib", List.of(w(1)));
        List<ReresolveDiffer.Diff> diffs = ReresolveDiffer.diff(
                Map.of("app", 1, "oldlib", 1),
                Map.of("app", 1), s);
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).changeType()).isEqualTo(ReresolveDiffer.REMOVED);
        assertThat(diffs.get(0).reason()).isEqualTo(ReresolveDiffer.ORIGINAL_WITHDRAWN);
    }
}
