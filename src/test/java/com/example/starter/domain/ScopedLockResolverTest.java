package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 私有作用域解析器单元测试：合成场景、子作用域共享、祖先链环、嵌套层数与跨域回溯。
 */
class ScopedLockResolverTest {

    /** 测试用快照构造器：按 name -> 版本列表组织，版本降序排列。 */
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
        return new DependencyRange(name, min, max, DependencyScope.PUBLIC);
    }

    private static DependencyRange pdep(String name, int min, int max) {
        return new DependencyRange(name, min, max, DependencyScope.PRIVATE);
    }

    private static ScopedLockSolution.ResolvedScope scopeAt(ScopedLockSolution solution, int index) {
        return solution.scopes().get(index);
    }

    @Test
    void syntheticScenarioResolvesX1InParentAndX2InChildScope() {
        // 根公开依赖 X[1,1] 且私有依赖 P[1,1]；P 公开依赖 X[2,2]。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("x", 1, 1), pdep("p", 1, 1))),
                "p", List.of(v(1, dep("x", 2, 2))),
                "x", List.of(v(2), v(1)));

        ScopedLockSolution solution = ScopedLockResolver.resolve(s, "app", 1);

        assertThat(solution).isNotNull();
        assertThat(solution.scopes()).hasSize(2);
        assertThat(scopeAt(solution, 0).entries()).containsExactly(
                Map.entry("app", 1), Map.entry("x", 1));
        assertThat(scopeAt(solution, 0).path()).isEmpty();
        assertThat(scopeAt(solution, 1).parentArtifactName()).isEqualTo("app");
        assertThat(scopeAt(solution, 1).parentIndex()).isEqualTo(0);
        assertThat(scopeAt(solution, 1).depth()).isEqualTo(2);
        assertThat(scopeAt(solution, 1).path()).containsExactly("app");
        assertThat(scopeAt(solution, 1).entries()).containsExactly(
                Map.entry("p", 1), Map.entry("x", 2));
        // 同名不同域选不同版本，不合并成最高版本。
        assertThat(solution.edges()).containsExactly(
                new ScopedLockSolution.ResolvedEdge(0, "app", "p", 1, DependencyScope.PRIVATE, 1),
                new ScopedLockSolution.ResolvedEdge(0, "app", "x", 1, DependencyScope.PUBLIC, 0),
                new ScopedLockSolution.ResolvedEdge(1, "p", "x", 2, DependencyScope.PUBLIC, 1));
    }

    @Test
    void sameGraphAllPublicIsInfeasible() {
        // 改为全 PUBLIC：X 需要同时满足 [1,1] 与 [2,2]，无解。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("x", 1, 1), dep("p", 1, 1))),
                "p", List.of(v(1, dep("x", 2, 2))),
                "x", List.of(v(2), v(1)));
        assertThat(ScopedLockResolver.resolve(s, "app", 1)).isNull();
    }

    @Test
    void multiplePrivateEdgesFromSameParentShareOneChildScope() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, pdep("a", 1, 1), pdep("b", 1, 1))),
                "a", List.of(v(1, dep("y", 1, 2))),
                "b", List.of(v(1, dep("y", 1, 2))),
                "y", List.of(v(2), v(1)));

        ScopedLockSolution solution = ScopedLockResolver.resolve(s, "app", 1);

        assertThat(solution).isNotNull();
        assertThat(solution.scopes()).hasSize(2);
        assertThat(scopeAt(solution, 1).entries()).containsExactly(
                Map.entry("a", 1), Map.entry("b", 1), Map.entry("y", 2));
    }

    @Test
    void sharedChildScopePropagatesConflicts() {
        // 同一父制品的私有边共用子域：a 要 y[1,1]，b 要 y[2,2]，交集为空。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, pdep("a", 1, 1), pdep("b", 1, 1))),
                "a", List.of(v(1, dep("y", 1, 1))),
                "b", List.of(v(1, dep("y", 2, 2))),
                "y", List.of(v(2), v(1)));
        assertThat(ScopedLockResolver.resolve(s, "app", 1)).isNull();
    }

    @Test
    void privateEdgesFromDifferentParentsGetDistinctScopes() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("a", 1, 1), dep("b", 1, 1))),
                "a", List.of(v(1, pdep("x", 1, 1))),
                "b", List.of(v(1, pdep("x", 2, 2))),
                "x", List.of(v(2), v(1)));

        ScopedLockSolution solution = ScopedLockResolver.resolve(s, "app", 1);

        assertThat(solution).isNotNull();
        assertThat(solution.scopes()).hasSize(3);
        assertThat(scopeAt(solution, 1).parentArtifactName()).isEqualTo("a");
        assertThat(scopeAt(solution, 1).entries()).containsExactly(Map.entry("x", 1));
        assertThat(scopeAt(solution, 2).parentArtifactName()).isEqualTo("b");
        assertThat(scopeAt(solution, 2).entries()).containsExactly(Map.entry("x", 2));
    }

    @Test
    void sameNameVersionOnAncestorChainIsCycle() {
        // app:1 私有依赖 b，b:1 又私有依赖 app[1,1]：祖先链重复 app:1，不可行。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, pdep("b", 1, 1))),
                "b", List.of(v(1, pdep("app", 1, 1))));
        assertThat(ScopedLockResolver.resolve(s, "app", 1)).isNull();
    }

    @Test
    void publicClosureInChildScopeCannotRepeatAncestor() {
        // 子域 PUBLIC 闭包引入与祖先相同的 name+version 同样视为环。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, pdep("p", 1, 1))),
                "p", List.of(v(1, dep("app", 1, 1))));
        assertThat(ScopedLockResolver.resolve(s, "app", 1)).isNull();
    }

    @Test
    void childScopeMayCarryDifferentVersionOfAncestorName() {
        // app:1 私有依赖 b，b:1 私有依赖 app[2,2]：不同版本不构成环。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(2), v(1, pdep("b", 1, 1))),
                "b", List.of(v(1, pdep("app", 2, 2))));

        ScopedLockSolution solution = ScopedLockResolver.resolve(s, "app", 1);

        assertThat(solution).isNotNull();
        assertThat(solution.scopes()).hasSize(3);
        assertThat(scopeAt(solution, 2).entries()).containsExactly(Map.entry("app", 2));
        assertThat(scopeAt(solution, 2).depth()).isEqualTo(3);
    }

    @Test
    void nestingUpToFourLevelsIsAllowed() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, pdep("a", 1, 1))),
                "a", List.of(v(1, pdep("b", 1, 1))),
                "b", List.of(v(1, pdep("c", 1, 1))),
                "c", List.of(v(1)));

        ScopedLockSolution solution = ScopedLockResolver.resolve(s, "app", 1);

        assertThat(solution).isNotNull();
        assertThat(solution.scopes()).hasSize(4);
        assertThat(scopeAt(solution, 3).depth()).isEqualTo(4);
        assertThat(scopeAt(solution, 3).path()).containsExactly("app", "a", "b");
    }

    @Test
    void nestingBeyondFourLevelsThrows() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, pdep("a", 1, 1))),
                "a", List.of(v(1, pdep("b", 1, 1))),
                "b", List.of(v(1, pdep("c", 1, 1))),
                "c", List.of(v(1, pdep("d", 1, 1))),
                "d", List.of(v(1)));

        assertThatThrownBy(() -> ScopedLockResolver.resolve(s, "app", 1))
                .isInstanceOf(ScopedLockResolver.ScopeDepthExceededException.class);
    }

    @Test
    void backtracksParentCandidateWhenChildScopeInfeasible() {
        // a2 的私有依赖 q 不存在，必须回退到 a1。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("a", 1, 2))),
                "a", List.of(v(2, pdep("q", 9, 9)), v(1)));

        ScopedLockSolution solution = ScopedLockResolver.resolve(s, "app", 1);

        assertThat(solution).isNotNull();
        assertThat(solution.scopes()).hasSize(1);
        assertThat(scopeAt(solution, 0).entries()).containsExactly(
                Map.entry("a", 1), Map.entry("app", 1));
    }

    @Test
    void skipsWithdrawnCandidatesInChildScope() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, pdep("p", 1, 1))),
                "p", List.of(v(1, dep("x", 1, 2))),
                "x", List.of(w(2), v(1)));

        ScopedLockSolution solution = ScopedLockResolver.resolve(s, "app", 1);

        assertThat(solution).isNotNull();
        assertThat(scopeAt(solution, 1).entries()).containsExactly(
                Map.entry("p", 1), Map.entry("x", 1));
    }

    @Test
    void rootFixedVersionDoesNotFallBack() {
        // 根 app:1 私有依赖缺失的 q；存在 app:2 但根精确版本不回退。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(2), v(1, pdep("q", 1, 1))));
        assertThat(ScopedLockResolver.resolve(s, "app", 1)).isNull();
    }
}
