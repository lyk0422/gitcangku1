package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 私有作用域锁定解析器单元测试：合成场景、环检测、嵌套上限、子域共享与跨域回溯。
 */
class ScopedLockResolverTest {

    /** 测试用快照构造器：按 name -> 版本（版本号，是否撤回，依赖列表）组织，版本降序排列。 */
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

    private static DependencyRange pub(String name, int min, int max) {
        return new DependencyRange(name, min, max, DependencyScope.PUBLIC);
    }

    private static DependencyRange pri(String name, int min, int max) {
        return new DependencyRange(name, min, max, DependencyScope.PRIVATE);
    }

    private static Map<String, Integer> versions(
            Map<List<String>, Map<String, ArtifactVersion>> solution, List<String> scope) {
        Map<String, Integer> result = new TreeMap<>();
        solution.get(scope).forEach((name, artifact) -> result.put(name, artifact.version()));
        return result;
    }

    @Test
    void syntheticScenarioResolvesParentAndChildScopeIndependently() {
        // 合成场景：根公开依赖 X1、私有依赖 P，P 公开依赖 X2。
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, pub("x", 1, 1), pri("p", 1, 1))),
                "p", List.of(v(1, pub("x", 2, 2))),
                "x", List.of(v(2), v(1)));

        Map<List<String>, Map<String, ArtifactVersion>> solution =
                ScopedLockResolver.resolve(s, "root", 1);

        assertThat(solution).isNotNull();
        assertThat(versions(solution, List.of())).isEqualTo(Map.of("root", 1, "x", 1));
        assertThat(versions(solution, List.of("root"))).isEqualTo(Map.of("p", 1, "x", 2));
    }

    @Test
    void sameGraphWithAllPublicDependenciesIsInfeasible() {
        // 改为全 PUBLIC：x 需同时满足 [1,1] 与 [2,2]，交集为空。
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, pub("x", 1, 1), pub("p", 1, 1))),
                "p", List.of(v(1, pub("x", 2, 2))),
                "x", List.of(v(2), v(1)));

        assertThat(ScopedLockResolver.resolve(s, "root", 1)).isNull();
        assertThat(LockResolver.resolve(s, "root", 1)).isNull();
    }

    @Test
    void privateSelfDependencySameVersionIsCycle() {
        // 根私有依赖 P，P 又私有依赖同版本 P：不能靠创建子域逃避环。
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, pri("p", 1, 1))),
                "p", List.of(v(1, pri("p", 1, 1)), v(2)));

        assertThat(ScopedLockResolver.resolve(s, "root", 1)).isNull();
    }

    @Test
    void indirectPrivateCycleSameNameVersionIsRejected() {
        // root -P-> A，A -P-> B，B -P-> A@1：A@1 已在祖先链中。
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, pri("a", 1, 1))),
                "a", List.of(v(1, pri("b", 1, 1))),
                "b", List.of(v(1, pri("a", 1, 1))));

        assertThat(ScopedLockResolver.resolve(s, "root", 1)).isNull();
    }

    @Test
    void differentVersionInAncestorChainIsNotCycle() {
        // P@2 私有依赖 P@1：版本不同，不构成环。
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, pri("p", 2, 2))),
                "p", List.of(v(2, pri("p", 1, 1)), v(1)));

        Map<List<String>, Map<String, ArtifactVersion>> solution =
                ScopedLockResolver.resolve(s, "root", 1);

        assertThat(solution).isNotNull();
        assertThat(versions(solution, List.of("root"))).isEqualTo(Map.of("p", 2));
        assertThat(versions(solution, List.of("root", "p"))).isEqualTo(Map.of("p", 1));
    }

    @Test
    void nestingUpToFourLevelsResolves() {
        // root -P-> n1 -P-> n2 -P-> n3 -P-> n4：n4 位于第 4 层子域，可行。
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, pri("n1", 1, 1))),
                "n1", List.of(v(1, pri("n2", 1, 1))),
                "n2", List.of(v(1, pri("n3", 1, 1))),
                "n3", List.of(v(1, pri("n4", 1, 1))),
                "n4", List.of(v(1)));

        Map<List<String>, Map<String, ArtifactVersion>> solution =
                ScopedLockResolver.resolve(s, "root", 1);

        assertThat(solution).isNotNull();
        assertThat(versions(solution, List.of("root", "n1", "n2", "n3")))
                .isEqualTo(Map.of("n4", 1));
    }

    @Test
    void nestingBeyondFourLevelsIsInfeasible() {
        // 再深一层（n5 将位于第 5 层子域）越限，无可行解。
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, pri("n1", 1, 1))),
                "n1", List.of(v(1, pri("n2", 1, 1))),
                "n2", List.of(v(1, pri("n3", 1, 1))),
                "n3", List.of(v(1, pri("n4", 1, 1))),
                "n4", List.of(v(1, pri("n5", 1, 1))),
                "n5", List.of(v(1)));

        assertThat(ScopedLockResolver.resolve(s, "root", 1)).isNull();
    }

    @Test
    void multiplePrivateEdgesFromSameParentShareChildScope() {
        // 根的两条私有边 P、Q 共用子域 [root]：两者对 x 的 PUBLIC 约束在同一子域取交集。
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, pri("p", 1, 1), pri("q", 1, 1))),
                "p", List.of(v(1, pub("x", 1, 2))),
                "q", List.of(v(1, pub("x", 2, 2))),
                "x", List.of(v(2), v(1)));

        Map<List<String>, Map<String, ArtifactVersion>> solution =
                ScopedLockResolver.resolve(s, "root", 1);

        assertThat(solution).isNotNull();
        assertThat(versions(solution, List.of("root")))
                .isEqualTo(Map.of("p", 1, "q", 1, "x", 2));
    }

    @Test
    void sharedChildScopeWithDisjointRangesIsInfeasible() {
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, pri("p", 1, 1), pri("q", 1, 1))),
                "p", List.of(v(1, pub("x", 1, 1))),
                "q", List.of(v(1, pub("x", 2, 2))),
                "x", List.of(v(2), v(1)));

        assertThat(ScopedLockResolver.resolve(s, "root", 1)).isNull();
    }

    @Test
    void backtracksAcrossScopeBoundary() {
        // P2 公开依赖 X[3,3]（X3 已撤回）不可行，必须回退到 P1。
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, pri("p", 1, 2))),
                "p", List.of(v(2, pub("x", 3, 3)), v(1, pub("x", 1, 1))),
                "x", List.of(w(3), v(1)));

        Map<List<String>, Map<String, ArtifactVersion>> solution =
                ScopedLockResolver.resolve(s, "root", 1);

        assertThat(solution).isNotNull();
        assertThat(versions(solution, List.of("root"))).isEqualTo(Map.of("p", 1, "x", 1));
    }

    @Test
    void privateTargetDoesNotLeakIntoParentScope() {
        // 子域中的 x@2 不进入父域；父域 x 保持 [1,1] 的解。
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, pub("x", 1, 1), pri("p", 1, 1))),
                "p", List.of(v(1, pub("x", 2, 2))),
                "x", List.of(v(2), v(1)));

        Map<List<String>, Map<String, ArtifactVersion>> solution =
                ScopedLockResolver.resolve(s, "root", 1);

        assertThat(solution).isNotNull();
        assertThat(versions(solution, List.of()).get("x")).isEqualTo(1);
        assertThat(solution.get(List.of())).doesNotContainKey("p");
    }
}
