package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 替代解析器单元测试：多跳替代、候选拒绝原因、同优先级规则冲突、
 * 替代链环、替代后约束不相容、平台可用性与生效时刻、最终坐标同名异版冲突。
 */
class SubstitutionResolverTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String PLATFORM = "linux-x86_64";

    /** 测试用快照构造器：name -> 版本（版本号，撤回，平台，依赖）。 */
    private static RepositorySnapshot snapshot(Object... groups) {
        Map<String, List<ArtifactVersion>> map = new TreeMap<>();
        AtomicLong id = new AtomicLong(1);
        for (int i = 0; i < groups.length; i += 2) {
            String name = (String) groups[i];
            @SuppressWarnings("unchecked")
            List<TestVersion> versions = (List<TestVersion>) groups[i + 1];
            List<ArtifactVersion> list = versions.stream()
                    .map(v -> new ArtifactVersion(id.getAndIncrement(), name, v.version(),
                            v.withdrawn(), Arrays.asList(v.deps()), Set.copyOf(v.platforms())))
                    .toList();
            map.put(name, list);
        }
        return new RepositorySnapshot(0, map);
    }

    private record TestVersion(int version, boolean withdrawn, DependencyRange[] deps,
                               Set<String> platforms) {
    }

    private static TestVersion v(int version, DependencyRange... deps) {
        return new TestVersion(version, false, deps, Set.of());
    }

    private static TestVersion w(int version, DependencyRange... deps) {
        return new TestVersion(version, true, deps, Set.of());
    }

    /** 声明仅限其他平台，因此在测试平台上不可用。 */
    private static TestVersion otherPlatform(int version, DependencyRange... deps) {
        return new TestVersion(version, false, deps, Set.of("other-platform"));
    }

    private static DependencyRange dep(String name, int min, int max) {
        return new DependencyRange(name, min, max);
    }

    private static SubstitutionRule rule(long id, int index, String pattern,
                                         Coordinate... alternatives) {
        return new SubstitutionRule(id, index, pattern, PLATFORM,
                Instant.parse("2025-01-01T00:00:00Z"), List.of(alternatives));
    }

    private static SubstitutionPolicy policy(SubstitutionRule... rules) {
        return new SubstitutionPolicy(7L, "key", NOW, List.of(rules));
    }

    private static ResolutionResult resolve(RepositorySnapshot snapshot,
                                            String root, int rootVersion,
                                            SubstitutionPolicy policy) {
        return SubstitutionResolver.resolve(snapshot, root, rootVersion,
                policy, PLATFORM, NOW);
    }

    @Test
    void multiHopSubstitutionReResolvesTransitiveDependencies() {
        // app:1 -> old[1,1]；old:1 撤回；old:1 -> mid:1（仅其他平台）；mid:1 -> new:1。
        // new:1 -> util[1,1]，util:1 存在。最终图：app、new、util。
        RepositorySnapshot s = snapshot(
                "app", List.of(v(1, dep("old", 1, 1))),
                "old", List.of(w(1)),
                "mid", List.of(otherPlatform(1)),
                "new", List.of(v(1, dep("util", 1, 1))),
                "util", List.of(v(1)));
        SubstitutionPolicy p = policy(
                rule(1, 0, "old:1", new Coordinate("mid", 1)),
                rule(2, 1, "mid:1", new Coordinate("new", 1)));

        ResolutionResult result = resolve(s, "app", 1, p);
        assertThat(result).isNotNull();
        assertThat(result.solution())
                .containsEntry("app", 1)
                .containsEntry("new", 1)
                .containsEntry("util", 1)
                .doesNotContainKey("old")
                .doesNotContainKey("mid");
        assertThat(result.policyVersion()).isEqualTo(7L);
        // 两跳替代均冻结在解释中，按原坐标名称排序：mid 在 old 前。
        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(0).original()).isEqualTo(new Coordinate("mid", 1));
        assertThat(result.steps().get(0).finalCoordinate()).isEqualTo(new Coordinate("new", 1));
        assertThat(result.steps().get(1).original()).isEqualTo(new Coordinate("old", 1));
        assertThat(result.steps().get(1).finalCoordinate()).isEqualTo(new Coordinate("new", 1));
    }

    @Test
    void recordsRejectionReasonsForSkippedCandidatesInPriorityOrder() {
        // old:1 候选依次为：缺失坐标、与原坐标相同、最终可用 good:1。
        RepositorySnapshot s = snapshot(
                "app", List.of(v(1, dep("old", 1, 1))),
                "old", List.of(w(1)),
                "good", List.of(v(1)));
        SubstitutionPolicy p = policy(rule(1, 0, "old:1",
                new Coordinate("ghost", 1),
                new Coordinate("old", 1),
                new Coordinate("good", 1)));

        ResolutionResult result = resolve(s, "app", 1, p);
        assertThat(result).isNotNull();
        SubstitutionStep step = result.steps().get(0);
        assertThat(step.finalCoordinate()).isEqualTo(new Coordinate("good", 1));
        assertThat(step.rejected()).hasSize(2);
        assertThat(step.rejected().get(0).name()).isEqualTo("ghost");
        assertThat(step.rejected().get(0).reason()).contains("不存在");
        assertThat(step.rejected().get(1).name()).isEqualTo("old");
        assertThat(step.rejected().get(1).reason()).contains("相同");
    }

    @Test
    void samePriorityMultipleRulesConflictIs422() {
        RepositorySnapshot s = snapshot(
                "app", List.of(v(1, dep("old", 1, 1))),
                "old", List.of(w(1)),
                "good", List.of(v(1)),
                "alt", List.of(v(1)));
        SubstitutionPolicy p = policy(
                rule(1, 0, "old:1", new Coordinate("good", 1)),
                rule(2, 1, "old:*", new Coordinate("alt", 1)));

        assertThatThrownBy(() -> resolve(s, "app", 1, p))
                .isInstanceOf(SubstitutionResolver.SubstitutionConflictException.class)
                .hasMessageContaining("同优先级");
    }

    @Test
    void substitutionChainCycleIsRejected() {
        // old:1 -> new:1（撤回）；new:1 -> old:1，形成 old→new→old 坐标环。
        RepositorySnapshot s = snapshot(
                "app", List.of(v(1, dep("old", 1, 1))),
                "old", List.of(w(1)),
                "new", List.of(w(1)));
        SubstitutionPolicy p = policy(
                rule(1, 0, "old:1", new Coordinate("new", 1)),
                rule(2, 1, "new:1", new Coordinate("old", 1)));

        assertThatThrownBy(() -> resolve(s, "app", 1, p))
                .isInstanceOf(SubstitutionResolver.SubstitutionConflictException.class)
                .hasMessageContaining("环");
    }

    @Test
    void substitutionFinalNameClashingWithDirectDependencyIsInfeasible() {
        // app 直接依赖 good[1,1]，同时依赖 old[1,1]；old:1 撤回后替代为 good:2，
        // 与已直接选定的 good:1 同名异版，版本约束不相容 → 无解（上层转 422）。
        RepositorySnapshot s = snapshot(
                "app", List.of(v(1, dep("good", 1, 1), dep("old", 1, 1))),
                "old", List.of(w(1)),
                "good", List.of(v(2), v(1)));
        SubstitutionPolicy p = policy(rule(1, 0, "old:1", new Coordinate("good", 2)));

        assertThat(resolve(s, "app", 1, p)).isNull();
    }

    @Test
    void substitutedTransitiveDependencyIncompatibleIsInfeasible() {
        // old:1 -> good:1；good:1 依赖 util[5,5]，仅有 util:1。
        RepositorySnapshot s = snapshot(
                "app", List.of(v(1, dep("old", 1, 1))),
                "old", List.of(w(1)),
                "good", List.of(v(1, dep("util", 5, 5))),
                "util", List.of(v(1)));
        SubstitutionPolicy p = policy(rule(1, 0, "old:1", new Coordinate("good", 1)));

        assertThat(resolve(s, "app", 1, p)).isNull();
    }

    @Test
    void twoPathsProposingDifferentSubstitutionForSameNameIsInfeasible() {
        // app -> left/right；left 依赖 old[1,1]（old:1 撤回 → shared:1），
        // right 依赖 old[2,2]（old:2 撤回 → shared:2）。同名 old 两个路径替代结果不同，
        // 全部求解分支不相容，返回无解（服务层整体转 422，不出部分锁）。
        RepositorySnapshot s = snapshot(
                "app", List.of(v(1, dep("left", 1, 1), dep("right", 1, 1))),
                "left", List.of(v(1, dep("old", 1, 1))),
                "right", List.of(v(1, dep("old", 2, 2))),
                "old", List.of(w(2), w(1)),
                "shared", List.of(v(2), v(1)));
        SubstitutionPolicy p = policy(
                rule(1, 0, "old:1", new Coordinate("shared", 1)),
                rule(2, 1, "old:2", new Coordinate("shared", 2)));

        assertThat(resolve(s, "app", 1, p)).isNull();
    }

    @Test
    void twoOriginalNodesSubstitutedToSameNameDifferentVersionsIsInfeasible() {
        // app 同时依赖 old 与 legacy；二者分别替代为 shared:1 与 shared:2，同名异版无解（上层转 422）。
        RepositorySnapshot s = snapshot(
                "app", List.of(v(1, dep("old", 1, 1), dep("legacy", 1, 1))),
                "old", List.of(w(1)),
                "legacy", List.of(w(1)),
                "shared", List.of(v(2), v(1)));
        SubstitutionPolicy p = policy(
                rule(1, 0, "old:1", new Coordinate("shared", 1)),
                rule(2, 1, "legacy:1", new Coordinate("shared", 2)));

        assertThat(resolve(s, "app", 1, p)).isNull();
    }

    @Test
    void unavailableWithoutRuleIsInfeasible() {
        RepositorySnapshot s = snapshot(
                "app", List.of(v(1, dep("old", 1, 1))),
                "old", List.of(w(1)));
        assertThat(resolve(s, "app", 1, policy())).isNull();
    }

    @Test
    void notYetEffectiveRuleIsIgnored() {
        RepositorySnapshot s = snapshot(
                "app", List.of(v(1, dep("old", 1, 1))),
                "old", List.of(w(1)),
                "good", List.of(v(1)));
        SubstitutionRule future = new SubstitutionRule(1, 0, "old:1", PLATFORM,
                Instant.parse("2030-01-01T00:00:00Z"), List.of(new Coordinate("good", 1)));
        SubstitutionPolicy p = new SubstitutionPolicy(7L, "key", NOW, List.of(future));

        assertThat(resolve(s, "app", 1, p)).isNull();
    }

    @Test
    void platformSpecificVersionIsUnavailableAndGetsSubstituted() {
        // old:1 未撤回但仅发布到其他平台，测试平台不可用，触发替代。
        RepositorySnapshot s = snapshot(
                "app", List.of(v(1, dep("old", 1, 1))),
                "old", List.of(otherPlatform(1)),
                "good", List.of(v(1)));
        SubstitutionPolicy p = policy(rule(1, 0, "old:1", new Coordinate("good", 1)));

        ResolutionResult result = resolve(s, "app", 1, p);
        assertThat(result).isNotNull();
        assertThat(result.solution()).containsEntry("good", 1);
    }

    @Test
    void sameOriginalNodeReusedByTwoPathsProducesSingleSubstitution() {
        // app -> left:1/right:1，二者都依赖 old:1；全图只能有一个 old 替代结果。
        RepositorySnapshot s = snapshot(
                "app", List.of(v(1, dep("left", 1, 1), dep("right", 1, 1))),
                "left", List.of(v(1, dep("old", 1, 1))),
                "right", List.of(v(1, dep("old", 1, 1))),
                "old", List.of(w(1)),
                "good", List.of(v(1)));
        SubstitutionPolicy p = policy(rule(1, 0, "old:1", new Coordinate("good", 1)));

        ResolutionResult result = resolve(s, "app", 1, p);
        assertThat(result).isNotNull();
        assertThat(result.solution())
                .containsEntry("left", 1).containsEntry("right", 1)
                .containsEntry("good", 1).doesNotContainKey("old");
        assertThat(result.steps()).hasSize(1);
    }

    @Test
    void ruleForOtherPlatformNeverMatches() {
        RepositorySnapshot s = snapshot(
                "app", List.of(v(1, dep("old", 1, 1))),
                "old", List.of(w(1)),
                "good", List.of(v(1)));
        SubstitutionRule other = new SubstitutionRule(1, 0, "old:1", "other-platform",
                Instant.parse("2025-01-01T00:00:00Z"), List.of(new Coordinate("good", 1)));
        SubstitutionPolicy p = new SubstitutionPolicy(7L, "key", NOW, List.of(other));

        assertThat(resolve(s, "app", 1, p)).isNull();
    }
}
