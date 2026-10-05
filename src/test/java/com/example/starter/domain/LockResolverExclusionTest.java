package com.example.starter.domain;

import com.example.starter.domain.LockResolution.ExclusionRecord;
import com.example.starter.domain.LockResolution.KeptEdge;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定解析器的边级路径排除单元测试：子图剪除、继承传播、独立路径汇合、
 * 命中/未命中记录与环场景；验证不使用全局 visited 掩盖后续无排除路径。
 */
class LockResolverExclusionTest {

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

    private static DependencyRange dep(String name, int min, int max, String... exclusions) {
        return new DependencyRange(name, min, max, List.of(exclusions));
    }

    @Test
    void exclusionPrunesEntireSubgraph() {
        // root -> a（排除 c）；a -> c。经过 root->a 后 c 边被忽略，c 不进入解。
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, dep("a", 1, 1, "c"))),
                "a", List.of(v(1, dep("c", 1, 1))),
                "c", List.of(v(1)));

        LockResolution result = LockResolver.resolveDetailed(s, "root", 1);
        assertThat(result).isNotNull();
        assertThat(result.versions()).isEqualTo(Map.of("root", 1, "a", 1));
        assertThat(result.edges()).containsExactly(new KeptEdge("root", 1, "a", 1));
        assertThat(result.exclusions()).containsExactly(
                new ExclusionRecord("root", 1, "a", "c", true));
    }

    @Test
    void exclusionCannotRemoveEdgeTargetItself() {
        // root -> a（排除 a）：排除作用于经过该边之后的子图，不能移除目标 a 本身。
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, dep("a", 1, 1, "a"))),
                "a", List.of(v(1)));

        LockResolution result = LockResolver.resolveDetailed(s, "root", 1);
        assertThat(result).isNotNull();
        assertThat(result.versions()).isEqualTo(Map.of("root", 1, "a", 1));
        // 子图中没有其它 a 边，排除未命中。
        assertThat(result.exclusions()).containsExactly(
                new ExclusionRecord("root", 1, "a", "a", false));
    }

    @Test
    void independentPathsKeepNameRequiredByOtherPath() {
        // 合成场景：root 同时依赖 a 和 b；a 路径排除 c，b 路径仍需要 c，c 必须保留。
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, dep("a", 1, 1, "c"), dep("b", 1, 1))),
                "a", List.of(v(1, dep("c", 1, 1))),
                "b", List.of(v(1, dep("c", 1, 1))),
                "c", List.of(v(1)));

        LockResolution result = LockResolver.resolveDetailed(s, "root", 1);
        assertThat(result).isNotNull();
        assertThat(result.versions())
                .isEqualTo(Map.of("root", 1, "a", 1, "b", 1, "c", 1));
        // a -> c 被排除不保留；b -> c 保留。
        assertThat(result.edges()).containsExactly(
                new KeptEdge("b", 1, "c", 1),
                new KeptEdge("root", 1, "a", 1),
                new KeptEdge("root", 1, "b", 1));
        assertThat(result.exclusions()).containsExactly(
                new ExclusionRecord("root", 1, "a", "c", true));
    }

    @Test
    void firstExcludedPathDoesNotMaskLaterRequiredPath() {
        // 排除路径在字典序上先被遍历（a 先于 d），不允许全局 visited 让 c 被掩盖。
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, dep("a", 1, 1, "c"), dep("d", 1, 1))),
                "a", List.of(v(1, dep("b", 1, 1))),
                "b", List.of(v(1, dep("c", 1, 1))),
                "d", List.of(v(1, dep("c", 1, 1))),
                "c", List.of(v(1)));

        assertThat(LockResolver.resolve(s, "root", 1))
                .isEqualTo(Map.of("root", 1, "a", 1, "b", 1, "c", 1, "d", 1));
    }

    @Test
    void inheritedExclusionsPropagateDeeper() {
        // root -> a（排除 d）；a -> b；b -> d。继承排除使 b -> d 被忽略。
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, dep("a", 1, 1, "d"))),
                "a", List.of(v(1, dep("b", 1, 1))),
                "b", List.of(v(1, dep("d", 1, 1))),
                "d", List.of(v(1)));

        LockResolution result = LockResolver.resolveDetailed(s, "root", 1);
        assertThat(result).isNotNull();
        assertThat(result.versions()).isEqualTo(Map.of("root", 1, "a", 1, "b", 1));
        assertThat(result.exclusions()).containsExactly(
                new ExclusionRecord("root", 1, "a", "d", true));
    }

    @Test
    void onlyNonExcludedRangesAreMerged() {
        // a 路径排除 c（区间 [1,1] 被忽略），b 路径要求 c[2,2]，解取 c2。
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, dep("a", 1, 1, "c"), dep("b", 1, 1))),
                "a", List.of(v(1, dep("c", 1, 1))),
                "b", List.of(v(1, dep("c", 2, 2))),
                "c", List.of(v(2), v(1)));

        assertThat(LockResolver.resolve(s, "root", 1))
                .isEqualTo(Map.of("root", 1, "a", 1, "b", 1, "c", 2));
    }

    @Test
    void unknownExclusionNameIsAllowedAndRecordedAsMiss() {
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, dep("a", 1, 1, "ghost"))),
                "a", List.of(v(1)));

        LockResolution result = LockResolver.resolveDetailed(s, "root", 1);
        assertThat(result).isNotNull();
        assertThat(result.versions()).isEqualTo(Map.of("root", 1, "a", 1));
        assertThat(result.exclusions()).containsExactly(
                new ExclusionRecord("root", 1, "a", "ghost", false));
    }

    @Test
    void excludedConstraintMakesOtherwiseInfeasibleGraphSolvable() {
        // a -> c[2,2] 但只有 c1；排除 c 后该约束不再生效，可解。
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, dep("a", 1, 1, "c"))),
                "a", List.of(v(1, dep("c", 2, 2))),
                "c", List.of(v(1)));

        assertThat(LockResolver.resolve(s, "root", 1))
                .isEqualTo(Map.of("root", 1, "a", 1));
    }

    @Test
    void cycleEdgeCanBeExcluded() {
        // a -> b（排除 c）；b -> c；c -> a。排除 c 后环被剪断，c 不进入解。
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, dep("a", 1, 1))),
                "a", List.of(v(1, dep("b", 1, 1, "c"))),
                "b", List.of(v(1, dep("c", 1, 1))),
                "c", List.of(v(1, dep("a", 1, 1))));

        LockResolution result = LockResolver.resolveDetailed(s, "root", 1);
        assertThat(result).isNotNull();
        assertThat(result.versions()).isEqualTo(Map.of("root", 1, "a", 1, "b", 1));
    }

    @Test
    void consistentCycleWithoutExclusionStillResolved() {
        // 对照：无排除时环上全部名称都进入解且区间互相满足。
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, dep("a", 1, 1))),
                "a", List.of(v(1, dep("b", 1, 1))),
                "b", List.of(v(1, dep("a", 1, 1))));

        assertThat(LockResolver.resolve(s, "root", 1))
                .isEqualTo(Map.of("root", 1, "a", 1, "b", 1));
    }

    @Test
    void exclusionOnOneEdgeDoesNotAffectDirectEdgeToSameName() {
        // root -> a（排除 c）且 root -> c 直接依赖：root 自己的 c 边不受 a 路径排除影响。
        RepositorySnapshot s = snapshot(0,
                "root", List.of(v(1, dep("a", 1, 1, "c"), dep("c", 1, 1))),
                "a", List.of(v(1, dep("c", 1, 1))),
                "c", List.of(v(1)));

        LockResolution result = LockResolver.resolveDetailed(s, "root", 1);
        assertThat(result).isNotNull();
        assertThat(result.versions()).isEqualTo(Map.of("root", 1, "a", 1, "c", 1));
        assertThat(result.edges()).containsExactly(
                new KeptEdge("root", 1, "a", 1),
                new KeptEdge("root", 1, "c", 1));
    }
}
