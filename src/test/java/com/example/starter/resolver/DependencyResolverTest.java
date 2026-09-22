package com.example.starter.resolver;

import com.example.starter.resolver.DependencyResolver.ArtifactVersion;
import com.example.starter.resolver.DependencyResolver.DependencyRange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 依赖回溯求解器单元测试：覆盖非贪心回退、成环、自依赖与不可行场景。
 */
class DependencyResolverTest {

    private ArtifactVersion artifact(String name, int version, DependencyRange... deps) {
        return new ArtifactVersion(name, version, List.of(deps));
    }

    private DependencyRange dep(String name, int min, int max) {
        return new DependencyRange(name, min, max);
    }

    @Test
    void resolvesSimpleChainPickingHighestCompatible() {
        List<ArtifactVersion> artifacts = List.of(
                artifact("app", 1, dep("lib", 1, 2)),
                artifact("lib", 2),
                artifact("lib", 1));

        Optional<Map<String, Integer>> result = DependencyResolver.resolve("app", 1, artifacts);

        assertThat(result).contains(Map.of("app", 1, "lib", 2));
    }

    @Test
    void backtracksWhenHighestCandidateConflictsLaterRange() {
        // app -> lib[1,2], lib2 依赖 lib[1,1]；lib 最高2 与 lib2 冲突，必须回退选1
        List<ArtifactVersion> artifacts = List.of(
                artifact("app", 1, dep("lib", 1, 2), dep("lib2", 1, 1)),
                artifact("lib", 2),
                artifact("lib", 1),
                artifact("lib2", 1, dep("lib", 1, 1)));

        Optional<Map<String, Integer>> result = DependencyResolver.resolve("app", 1, artifacts);

        assertThat(result).isPresent();
        assertThat(result.get()).containsEntry("lib", 1).containsEntry("lib2", 1);
    }

    @Test
    void cycleVersionsMustSatisfyMutualRangesViaBacktracking() {
        // a <-> b 互依赖，仅 a1+b1 互容；a2 要求 b1 而 b1 要求 a[2,2]，a2 与 b 高版本冲突，回溯得 a1+b1
        List<ArtifactVersion> artifacts = List.of(
                artifact("a", 2, dep("b", 1, 1)),
                artifact("a", 1, dep("b", 1, 2)),
                artifact("b", 2, dep("a", 1, 1)),
                artifact("b", 1, dep("a", 2, 2)));

        Optional<Map<String, Integer>> result = DependencyResolver.resolve("a", 2, artifacts);

        // 根固定为 a2：唯一兼容 b 是 b1，而 b1 要求 a2，成立
        assertThat(result).contains(Map.of("a", 2, "b", 1));
    }

    @Test
    void infeasibleCycleReturnsEmpty() {
        // 根 a1 要求 b[1,1]，b1 要求 a[2,2]，根版本固定为1，互斥无解
        List<ArtifactVersion> artifacts = List.of(
                artifact("a", 1, dep("b", 1, 1)),
                artifact("a", 2),
                artifact("b", 1, dep("a", 2, 2)));

        Optional<Map<String, Integer>> result = DependencyResolver.resolve("a", 1, artifacts);

        assertThat(result).isEmpty();
    }

    @Test
    void selfDependencyRangeExcludingRootVersionFails() {
        List<ArtifactVersion> artifacts = List.of(
                artifact("a", 1, dep("a", 2, 2)),
                artifact("a", 2, dep("a", 2, 2)));

        assertThat(DependencyResolver.resolve("a", 1, artifacts)).isEmpty();
    }

    @Test
    void selfDependencyRangeIncludingRootVersionSucceeds() {
        List<ArtifactVersion> artifacts = List.of(
                artifact("a", 2, dep("a", 1, 2)));

        assertThat(DependencyResolver.resolve("a", 2, artifacts)).contains(Map.of("a", 2));
    }

    @Test
    void missingRootReturnsEmpty() {
        assertThat(DependencyResolver.resolve("ghost", 1, List.of())).isEmpty();
    }

    @Test
    void dependencyNameWithoutAnyVersionMakesResolutionInfeasible() {
        List<ArtifactVersion> artifacts = List.of(
                artifact("app", 1, dep("missing", 1, 1)));

        assertThat(DependencyResolver.resolve("app", 1, artifacts)).isEmpty();
    }

    @Test
    void resultIsSortedByNameAndRootStaysFixed() {
        List<ArtifactVersion> artifacts = List.of(
                artifact("root", 3, dep("zlib", 1, 1), dep("alib", 1, 1)),
                artifact("zlib", 1),
                artifact("alib", 1));

        Optional<Map<String, Integer>> result = DependencyResolver.resolve("root", 3, artifacts);

        assertThat(result).isPresent();
        assertThat(result.get().keySet()).containsExactly("alib", "root", "zlib");
    }
}
