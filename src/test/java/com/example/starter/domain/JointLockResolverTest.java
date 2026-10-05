package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多根联合锁定解析器单元测试：区间汇合、根互为依赖、根不可回退与回溯。
 */
class JointLockResolverTest {

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

    private static DependencyRange dep(String name, int min, int max) {
        return new DependencyRange(name, min, max);
    }

    @Test
    void intersectsRangesFromAllRoots() {
        // 题干合成场景：根 a 需 x[1,3]，根 b 需 x[1,2]，x3、x2 均在，联合锁应选 x2。
        RepositorySnapshot s = snapshot(0,
                "a", List.of(v(1, dep("x", 1, 3))),
                "b", List.of(v(1, dep("x", 1, 2))),
                "x", List.of(v(3), v(2)));

        Map<String, Integer> result = LockResolver.resolveJoint(s, Map.of("a", 1, "b", 1));
        assertThat(result).isNotNull();
        assertThat(result).containsEntry("a", 1).containsEntry("b", 1).containsEntry("x", 2);
    }

    @Test
    void rootOrderDoesNotChangeResult() {
        RepositorySnapshot s = snapshot(0,
                "a", List.of(v(1, dep("x", 1, 3))),
                "b", List.of(v(1, dep("x", 1, 2))),
                "x", List.of(v(3), v(2), v(1)));

        Map<String, Integer> ab = LockResolver.resolveJoint(s, Map.of("a", 1, "b", 1));
        Map<String, Integer> ba = LockResolver.resolveJoint(s,
                new TreeMap<>(Map.of("b", 1, "a", 1)).descendingMap());
        assertThat(ab).isNotNull();
        assertThat(ba).isEqualTo(ab);
    }

    @Test
    void failsWhenWithdrawnCandidateLeavesNoCommonVersion() {
        // 题干合成场景：x2 撤回且无 x1，交集 [1,2] 内无可用版本，整单失败。
        RepositorySnapshot s = snapshot(0,
                "a", List.of(v(1, dep("x", 1, 3))),
                "b", List.of(v(1, dep("x", 1, 2))),
                "x", List.of(v(3), w(2)));

        assertThat(LockResolver.resolveJoint(s, Map.of("a", 1, "b", 1))).isNull();
    }

    @Test
    void laterRootConstraintsAreNotIgnored() {
        // 单独锁 a 会取 x3；根 b 的区间把 x 约束到 [2,2]，必须联合求解而非取各自最高。
        RepositorySnapshot s = snapshot(0,
                "a", List.of(v(1, dep("x", 1, 3))),
                "b", List.of(v(1, dep("x", 2, 2))),
                "x", List.of(v(3), v(2), v(1)));

        assertThat(LockResolver.resolve(s, "a", 1))
                .containsEntry("x", 3);
        assertThat(LockResolver.resolveJoint(s, Map.of("a", 1, "b", 1)))
                .containsEntry("x", 2);
    }

    @Test
    void rootAsDependencyOfAnotherRootMustSatisfyInterval() {
        // 根 a:1 依赖 b[1,1]；联合根取 b:1 可行，取 b:2 违反 a 的区间，不可行。
        RepositorySnapshot s = snapshot(0,
                "a", List.of(v(1, dep("b", 1, 1))),
                "b", List.of(v(2), v(1)));

        assertThat(LockResolver.resolveJoint(s, Map.of("a", 1, "b", 1)))
                .isEqualTo(Map.of("a", 1, "b", 1));
        assertThat(LockResolver.resolveJoint(s, Map.of("a", 1, "b", 2))).isNull();
    }

    @Test
    void rootVersionsAreFixedAndNeverBacktracked() {
        // 根 b 固定为 1，即使 b2 存在且更“新”也不替换；b1 的依赖约束生效。
        RepositorySnapshot s = snapshot(0,
                "a", List.of(v(1, dep("b", 1, 2))),
                "b", List.of(v(2, dep("x", 2, 2)), v(1, dep("x", 1, 1))),
                "x", List.of(v(2), v(1)));

        assertThat(LockResolver.resolveJoint(s, Map.of("a", 1, "b", 1)))
                .isEqualTo(Map.of("a", 1, "b", 1, "x", 1));
    }

    @Test
    void sharedDependencyBacktracksAcrossRoots() {
        // a -> x[1,2]；b -> x[1,2] 且 y[1,1]；x2 -> y[2,2]（与 y1 冲突），须回退到 x1。
        RepositorySnapshot s = snapshot(0,
                "a", List.of(v(1, dep("x", 1, 2))),
                "b", List.of(v(1, dep("x", 1, 2), dep("y", 1, 1))),
                "x", List.of(v(2, dep("y", 2, 2)), v(1)),
                "y", List.of(v(1)));

        assertThat(LockResolver.resolveJoint(s, Map.of("a", 1, "b", 1)))
                .isEqualTo(Map.of("a", 1, "b", 1, "x", 1, "y", 1));
    }

    @Test
    void emptyRangeIntersectionAcrossRootsIsInfeasible() {
        // a 需 x[1,2]，b 需 x[3,4]，交集为空。
        RepositorySnapshot s = snapshot(0,
                "a", List.of(v(1, dep("x", 1, 2))),
                "b", List.of(v(1, dep("x", 3, 4))),
                "x", List.of(v(4), v(3), v(2), v(1)));

        assertThat(LockResolver.resolveJoint(s, Map.of("a", 1, "b", 1))).isNull();
    }

    @Test
    void missingRootThrows() {
        RepositorySnapshot s = snapshot(0, "a", List.of(v(1)));
        assertThatThrownBy(() -> LockResolver.resolveJoint(s, Map.of("a", 1, "ghost", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withdrawnRootThrows() {
        RepositorySnapshot s = snapshot(0,
                "a", List.of(v(1)),
                "b", List.of(w(1)));
        assertThatThrownBy(() -> LockResolver.resolveJoint(s, Map.of("a", 1, "b", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyRootSetThrows() {
        RepositorySnapshot s = snapshot(0, "a", List.of(v(1)));
        assertThatThrownBy(() -> LockResolver.resolveJoint(s, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void threeRootsShareTransitiveDependency() {
        // 三根联合：a/b/c 共同约束共享依赖 x，且 x 自身依赖 y。
        RepositorySnapshot s = snapshot(0,
                "a", List.of(v(1, dep("x", 1, 3))),
                "b", List.of(v(1, dep("x", 2, 3))),
                "c", List.of(v(1, dep("x", 2, 2))),
                "x", List.of(v(3), v(2, dep("y", 1, 1)), v(1)),
                "y", List.of(v(1)));

        assertThat(LockResolver.resolveJoint(s, Map.of("a", 1, "b", 1, "c", 1)))
                .isEqualTo(Map.of("a", 1, "b", 1, "c", 1, "x", 2, "y", 1));
    }
}
