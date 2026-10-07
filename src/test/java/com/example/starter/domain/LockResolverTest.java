package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定解析器单元测试：回溯、环依赖、撤回版本过滤、字典序解析顺序与体积预算回退。
 */
class LockResolverTest {

    /** 测试用快照构造器：按 name -> 版本（版本号，是否撤回，大小，依赖列表）组织，版本降序排列。 */
    private static RepositorySnapshot snapshot(long version, Object... groups) {
        Map<String, List<ArtifactVersion>> map = new TreeMap<>();
        AtomicLong id = new AtomicLong(1);
        for (int i = 0; i < groups.length; i += 2) {
            String name = (String) groups[i];
            @SuppressWarnings("unchecked")
            List<TestVersion> versions = (List<TestVersion>) groups[i + 1];
            List<ArtifactVersion> list = versions.stream()
                    .map(v -> new ArtifactVersion(id.getAndIncrement(), name, v.version(),
                            v.withdrawn(), v.sizeBytes(), v.deps()))
                    .toList();
            map.put(name, list);
        }
        return new RepositorySnapshot(version, map);
    }

    private record TestVersion(int version, boolean withdrawn, long sizeBytes,
                               List<DependencyRange> deps) {
    }

    private static TestVersion v(int version, DependencyRange... deps) {
        return new TestVersion(version, false, 0L, List.of(deps));
    }

    private static TestVersion vs(int version, long sizeBytes, DependencyRange... deps) {
        return new TestVersion(version, false, sizeBytes, List.of(deps));
    }

    private static TestVersion w(int version, DependencyRange... deps) {
        return new TestVersion(version, true, 0L, List.of(deps));
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
    // 体积预算
    // ------------------------------------------------------------------

    /** 题干合成场景：root 大小 2，a2 大小 9、a1 大小 5，共享 b 大小 3。 */
    private static RepositorySnapshot syntheticSnapshot() {
        return snapshot(0,
                "root", List.of(vs(1, 2, dep("a", 1, 2))),
                "a", List.of(vs(2, 9, dep("b", 1, 1)), vs(1, 5, dep("b", 1, 1))),
                "b", List.of(vs(1, 3)));
    }

    @Test
    void budgetForcesFallbackFromA2ToA1AndCountsSharedChildOnce() {
        // 预算 10：2+9+3=14 超限回退 a1；2+5+3=10 恰好；b 被两个父依赖只计一次。
        LockSolution solution = LockResolver.resolveWithBudget(
                syntheticSnapshot(), "root", 1, 10L);
        assertThat(solution).isNotNull();
        assertThat(solution.versions()).containsExactly(
                Map.entry("a", 1), Map.entry("b", 1), Map.entry("root", 1));
        assertThat(solution.totalSizeBytes()).isEqualTo(10L);
    }

    @Test
    void largerBudgetKeepsHighestVersionAndStillCountsSharedChildOnce() {
        // 预算 14：a2 可行，2+9+3=14（b 只计一次，不是 17）。
        LockSolution solution = LockResolver.resolveWithBudget(
                syntheticSnapshot(), "root", 1, 14L);
        assertThat(solution).isNotNull();
        assertThat(solution.versions()).containsEntry("a", 2);
        assertThat(solution.totalSizeBytes()).isEqualTo(14L);
    }

    @Test
    void budgetBelowEveryCombinationReturnsNull() {
        // 最小组合也要 10 字节；预算 9 时所有组合超预算。
        assertThat(LockResolver.resolveWithBudget(syntheticSnapshot(), "root", 1, 9L))
                .isNull();
    }

    @Test
    void zeroBudgetAllowsOnlyAllZeroSizeGraph() {
        RepositorySnapshot zeroOk = snapshot(0,
                "app", List.of(v(1, dep("lib", 1, 1))),
                "lib", List.of(v(1)));
        LockSolution solution = LockResolver.resolveWithBudget(zeroOk, "app", 1, 0L);
        assertThat(solution).isNotNull();
        assertThat(solution.totalSizeBytes()).isZero();

        RepositorySnapshot rootWithSize = snapshot(0,
                "app", List.of(vs(1, 1)),
                "lib", List.of(v(1)));
        assertThat(LockResolver.resolveWithBudget(rootWithSize, "app", 1, 0L)).isNull();
    }

    @Test
    void sharedChildReachedViaMultipleParentsIsCountedOnce() {
        // app -> x[1,1]、y[1,1]；x1 与 y1 都依赖 b:1（大小 3），app 大小 2。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(vs(1, 2, dep("x", 1, 1), dep("y", 1, 1))),
                "x", List.of(vs(1, 0, dep("b", 1, 1))),
                "y", List.of(vs(1, 0, dep("b", 1, 1))),
                "b", List.of(vs(1, 3)));
        LockSolution solution = LockResolver.resolveWithBudget(s, "app", 1, 5L);
        assertThat(solution).isNotNull();
        assertThat(solution.totalSizeBytes()).isEqualTo(5L);
    }

    @Test
    void exactSumOverflowingLongIsTreatedAsBudgetShortfall() {
        // 根大小 Long.MAX_VALUE，子制品大小 1：精确总和溢出 long，禁止溢出变负后误判可行。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(vs(1, Long.MAX_VALUE, dep("lib", 1, 1))),
                "lib", List.of(vs(1, 1L)));
        assertThat(LockResolver.resolveWithBudget(s, "app", 1, Long.MAX_VALUE)).isNull();
    }

    @Test
    void rootAloneExceedingBudgetReturnsNull() {
        RepositorySnapshot s = snapshot(0, "app", List.of(vs(1, 5)));
        assertThat(LockResolver.resolveWithBudget(s, "app", 1, 4L)).isNull();
        LockSolution exact = LockResolver.resolveWithBudget(s, "app", 1, 5L);
        assertThat(exact).isNotNull();
        assertThat(exact.totalSizeBytes()).isEqualTo(5L);
    }

    @Test
    void nullBudgetIgnoresSizes() {
        // 不限预算时体积不影响原有回溯结论。
        Map<String, Integer> result = LockResolver.resolve(syntheticSnapshot(), "root", 1);
        assertThat(result).containsEntry("a", 2).containsEntry("b", 1).containsEntry("root", 1);
    }
}
