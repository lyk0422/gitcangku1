package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 替代感知锁解析器单元测试：撤回/平台不可用触发替代、多跳链、候选拒绝原因、
 * 路径冲突、坐标环、约束不相容、同原节点唯一结论与生效时刻。
 */
class SubstitutionLockResolverTest {

    private static final String PLATFORM = "jvm";
    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");

    private final AtomicLong idSequence = new AtomicLong(1);
    private final Map<String, List<TestVersion>> groups = new TreeMap<>();
    private final Map<Long, Set<String>> platforms = new HashMap<>();

    private record TestVersion(long id, int version, boolean withdrawn,
                               List<DependencyRange> deps, Set<String> platforms) {
    }

    private static DependencyRange dep(String name, int min, int max) {
        return new DependencyRange(name, min, max);
    }

    private void put(String name, TestVersion... versions) {
        groups.put(name, List.of(versions));
    }

    private TestVersion v(int version, DependencyRange... deps) {
        long id = idSequence.getAndIncrement();
        return new TestVersion(id, version, false, List.of(deps), Set.of());
    }

    private TestVersion w(int version, DependencyRange... deps) {
        long id = idSequence.getAndIncrement();
        return new TestVersion(id, version, true, List.of(deps), Set.of());
    }

    private TestVersion vPlatforms(int version, Set<String> plats, DependencyRange... deps) {
        long id = idSequence.getAndIncrement();
        platforms.put(id, plats);
        return new TestVersion(id, version, false, List.of(deps), plats);
    }

    private RepositorySnapshot snapshot() {
        Map<String, List<ArtifactVersion>> map = new TreeMap<>();
        groups.forEach((name, versions) -> map.put(name, versions.stream()
                .map(t -> new ArtifactVersion(t.id(), name, t.version(), t.withdrawn(), t.deps()))
                .toList()));
        return new RepositorySnapshot(1L, map);
    }

    private PlatformAvailability availability() {
        return new PlatformAvailability(platforms);
    }

    private static SubstitutionCandidate cand(String coordinate, int priority) {
        return new SubstitutionCandidate(coordinate, priority);
    }

    private static SubstitutionRule rule(String source, String platform,
                                         Instant effectiveAt, String... priorityAndTarget) {
        List<SubstitutionCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < priorityAndTarget.length; i += 2) {
            candidates.add(cand(priorityAndTarget[i + 1], Integer.parseInt(priorityAndTarget[i])));
        }
        return new SubstitutionRule(0L, CoordinatePattern.of(source), platform,
                candidates, effectiveAt);
    }

    private static SubstitutionPolicy policy(SubstitutionRule... rules) {
        return new SubstitutionPolicy(7L, NOW.minusSeconds(3600), List.of(rules));
    }

    private SubstitutionResult resolve(SubstitutionPolicy policy) {
        return SubstitutionLockResolver.resolve(snapshot(), availability(), policy,
                "app", 1, PLATFORM, NOW);
    }

    // ------------------------------------------------------------------
    // 主流程：撤回与平台不可用触发替代
    // ------------------------------------------------------------------

    @Test
    void withdrawnTriggersSubstitutionAndFreezesSingleStep() {
        put("app", v(1, dep("legacy", 1, 1)));
        put("legacy", w(1));
        put("newlib", v(1));
        SubstitutionPolicy policy = policy(rule("legacy", PLATFORM, NOW.minusSeconds(60),
                "1", "newlib"));

        SubstitutionResult result = resolve(policy);

        assertThat(result.versions()).containsEntry("app", 1).containsEntry("newlib", 1)
                .doesNotContainKey("legacy");
        assertThat(result.policyVersion()).isEqualTo(7L);
        assertThat(result.steps()).hasSize(1);
        SubstitutionStep step = result.steps().get(0);
        assertThat(step.originalCoordinate()).isEqualTo("legacy");
        assertThat(step.sourcePattern()).isEqualTo("legacy");
        assertThat(step.platform()).isEqualTo(PLATFORM);
        assertThat(step.finalCoordinate()).isEqualTo("newlib");
        assertThat(step.stepOrder()).isZero();
        assertThat(step.policyVersion()).isEqualTo(7L);
        assertThat(step.rejections()).isEmpty();
    }

    private void stepOrderRejectionsAreEmpty(SubstitutionStep step) {
        assertThat(step.stepOrder()).isZero();
        assertThat(step.policyVersion()).isEqualTo(7L);
        assertThat(step.rejections()).isEmpty();
    }

    @Test
    void platformUnavailabilityTriggersSubstitutionOnlyOnThatPlatform() {
        put("app", v(1, dep("legacy", 1, 1)));
        put("legacy", vPlatforms(1, Set.of("native")));
        put("alt", v(1));
        SubstitutionPolicy policy = policy(rule("legacy", PLATFORM, NOW.minusSeconds(60),
                "1", "alt"));

        SubstitutionResult jvmResult = resolve(policy);
        assertThat(jvmResult.versions()).containsEntry("alt", 1).doesNotContainKey("legacy");
        assertThat(jvmResult.steps()).hasSize(1);

        SubstitutionResult nativeResult = SubstitutionLockResolver.resolve(
                snapshot(), availability(), policy, "app", 1, "native", NOW);
        assertThat(nativeResult.versions()).containsEntry("legacy", 1);
        assertThat(nativeResult.steps()).isEmpty();
    }

    @Test
    void availableDirectVersionIsChosenWithoutSubstitution() {
        put("app", v(1, dep("legacy", 1, 2)));
        put("legacy", v(1), w(2));
        put("alt", v(1));
        SubstitutionPolicy policy = policy(rule("legacy", PLATFORM, NOW.minusSeconds(60),
                "1", "alt"));

        SubstitutionResult result = resolve(policy);
        assertThat(result.versions()).containsEntry("legacy", 1).doesNotContainKey("alt");
        assertThat(result.steps()).isEmpty();
    }

    @Test
    void notYetEffectiveRuleIsIgnoredAndResolutionFails() {
        put("app", v(1, dep("legacy", 1, 1)));
        put("legacy", w(1));
        put("alt", v(1));
        SubstitutionPolicy policy = policy(rule("legacy", PLATFORM, NOW.plusSeconds(60),
                "1", "alt"));

        assertThat(resolve(policy)).isNull();
    }

    @Test
    void wildcardSourcePatternMatchesCoordinate() {
        put("app", v(1, dep("com.old.legacy", 1, 1)));
        put("com.old.legacy", w(1));
        put("relocated", v(1));
        SubstitutionPolicy policy = policy(rule("com.old.*", PLATFORM, NOW.minusSeconds(60),
                "1", "relocated"));

        SubstitutionResult result = resolve(policy);
        assertThat(result.versions()).containsEntry("relocated", 1);
        assertThat(result.steps().get(0).originalCoordinate()).isEqualTo("com.old.legacy");
        assertThat(result.steps().get(0).sourcePattern()).isEqualTo("com.old.*");
    }

    // ------------------------------------------------------------------
    // 多跳替代与候选拒绝原因
    // ------------------------------------------------------------------

    @Test
    void multiHopSubstitutionReResolvesTransitiveDependencies() {
        // app -> a[1,1]；a:1 撤回，规则 a->b；b:1 撤回，规则 b->c；c:1 有效且依赖 util。
        put("app", v(1, dep("a", 1, 1)));
        put("a", w(1));
        put("b", w(1));
        put("c", v(1, dep("util", 1, 1)));
        put("util", v(1));
        SubstitutionPolicy policy = policy(
                rule("a", PLATFORM, NOW.minusSeconds(60), "1", "b"),
                rule("b", PLATFORM, NOW.minusSeconds(60), "1", "c"));

        SubstitutionResult result = resolve(policy);

        assertThat(result.versions())
                .containsEntry("app", 1).containsEntry("c", 1).containsEntry("util", 1)
                .doesNotContainKey("a").doesNotContainKey("b");
        assertThat(result.steps()).hasSize(2);
        SubstitutionStep aStep = result.steps().get(0);
        assertThat(aStep.stepOrder()).isZero();
        assertThat(aStep.originalCoordinate()).isEqualTo("a");
        assertThat(aStep.finalCoordinate()).isEqualTo("c");
        // b 是成功链上的中间节点（继续展开），不是被拒绝候选。
        assertThat(aStep.rejections()).isEmpty();

        SubstitutionStep bStep = result.steps().get(1);
        assertThat(bStep.originalCoordinate()).isEqualTo("b");
        assertThat(bStep.finalCoordinate()).isEqualTo("c");
        assertThat(bStep.rejections()).isEmpty();
    }

    @Test
    void rejectedCandidatesAreFrozenWithReasonsInPriorityOrder() {
        put("app", v(1, dep("legacy", 1, 1)));
        put("legacy", w(1));
        put("withdrawn-only", w(1));
        put("good", v(1));
        SubstitutionPolicy policy = policy(rule("legacy", PLATFORM, NOW.minusSeconds(60),
                "1", "ghost", "2", "withdrawn-only", "3", "good"));

        SubstitutionResult result = resolve(policy);
        assertThat(result.versions()).containsEntry("good", 1);
        SubstitutionStep step = result.steps().get(0);
        assertThat(step.rejections()).hasSize(2);
        assertThat(step.rejections().get(0).coordinate()).isEqualTo("ghost");
        assertThat(step.rejections().get(0).priority()).isEqualTo(1);
        assertThat(step.rejections().get(0).reason())
                .isEqualTo(SubstitutionLockResolver.REASON_NOT_FOUND);
        assertThat(step.rejections().get(1).coordinate()).isEqualTo("withdrawn-only");
        assertThat(step.rejections().get(1).reason())
                .isEqualTo(SubstitutionLockResolver.REASON_WITHDRAWN);
    }

    @Test
    void allCandidatesRejectedIsDeterministic422() {
        put("app", v(1, dep("legacy", 1, 1)));
        put("legacy", w(1));
        put("alt", w(1));
        SubstitutionPolicy policy = policy(rule("legacy", PLATFORM, NOW.minusSeconds(60),
                "1", "alt"));

        assertThatThrownBy(() -> resolve(policy))
                .isInstanceOf(SubstitutionFailureException.class)
                .hasMessageContaining("legacy");
    }

    // ------------------------------------------------------------------
    // 环与冲突
    // ------------------------------------------------------------------

    @Test
    void substitutionCycleIsDeterministic422() {
        put("app", v(1, dep("a", 1, 1)));
        put("a", w(1));
        put("b", w(1));
        SubstitutionPolicy policy = policy(
                rule("a", PLATFORM, NOW.minusSeconds(60), "1", "b"),
                rule("b", PLATFORM, NOW.minusSeconds(60), "1", "a"));

        assertThatThrownBy(() -> resolve(policy))
                .isInstanceOf(SubstitutionFailureException.class);
    }

    @Test
    void selfLoopCandidateIsRejectedWithCycleReason() {
        put("app", v(1, dep("a", 1, 1)));
        put("a", w(1));
        put("good", v(1));
        // 解析器不依赖发布期静态校验：候选 a 指向自身构成坐标环，记录 CYCLE 后采用下一候选。
        SubstitutionPolicy policy = policy(rule("a", PLATFORM, NOW.minusSeconds(60),
                "1", "a", "2", "good"));

        SubstitutionResult result = resolve(policy);
        assertThat(result.versions()).containsEntry("good", 1);
        assertThat(result.steps().get(0).rejections())
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.coordinate()).isEqualTo("a");
                    assertThat(r.reason()).isEqualTo(SubstitutionLockResolver.REASON_CYCLE);
                });
    }

    @Test
    void pathsProposingDifferentOutcomesForSameCoordinateAre422() {
        // x:1 -> shared[1,1]（shared1 撤回，尝试替代到 alt:1）；y:1 -> shared[2,2]，
        // shared2 也撤回且终点 alt:1 不满足 y 的区间：替代候选经全图重解全部被拒，整次 422。
        put("app", v(1, dep("x", 1, 1), dep("y", 1, 1)));
        put("x", v(1, dep("shared", 1, 1)));
        put("y", v(1, dep("shared", 2, 2)));
        put("shared", w(2), w(1));
        put("alt", v(1));
        SubstitutionPolicy policy = policy(rule("shared", PLATFORM, NOW.minusSeconds(60),
                "1", "alt"));

        assertThatThrownBy(() -> resolve(policy))
                .isInstanceOf(SubstitutionFailureException.class)
                .hasMessageContaining("shared");
    }

    @Test
    void laterArrivingConflictingRangeOnSubstitutedBindingIsHard422() {
        // app:1 -> first[1,1] 与 mid[1,1]；first:1 -> legacy[1,1]；legacy 撤回替代为 alt:1；
        // mid:1 -> legacy[2,2]：替代结论落定后，mid 的候选因依赖已替代原坐标且区间不相容
        // 被过滤，mid 无法解析，替代候选经全图重解全部被拒，确定性 422。
        put("app", v(1, dep("first", 1, 1), dep("mid", 1, 1)));
        put("first", v(1, dep("legacy", 1, 1)));
        put("mid", v(1, dep("legacy", 2, 2)));
        put("legacy", w(1));
        put("alt", v(1));
        SubstitutionPolicy policy = policy(rule("legacy", PLATFORM, NOW.minusSeconds(60),
                "1", "alt"));

        assertThatThrownBy(() -> resolve(policy))
                .isInstanceOf(SubstitutionFailureException.class);
    }

    @Test
    void sameOriginalFromTwoPathsSharesSingleSubstitution() {
        put("app", v(1, dep("x", 1, 1), dep("y", 1, 1)));
        put("x", v(1, dep("legacy", 1, 1)));
        put("y", v(1, dep("legacy", 1, 1)));
        put("legacy", w(1));
        put("newlib", v(1));
        SubstitutionPolicy policy = policy(rule("legacy", PLATFORM, NOW.minusSeconds(60),
                "1", "newlib"));

        SubstitutionResult result = resolve(policy);
        assertThat(result.versions()).containsEntry("newlib", 1);
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).originalCoordinate()).isEqualTo("legacy");
    }

    @Test
    void endpointVersionIncompatibleWithFixedRootRangeIs422() {
        // 替代终点 alt:1 要求根 app:2，但根固定为 1：约束不相容，整次 422。
        put("app", v(1, dep("legacy", 1, 1)), v(2));
        put("legacy", w(1));
        put("alt", v(1, dep("app", 2, 2)));
        SubstitutionPolicy policy = policy(rule("legacy", PLATFORM, NOW.minusSeconds(60),
                "1", "alt"));

        assertThatThrownBy(() -> resolve(policy))
                .isInstanceOf(SubstitutionFailureException.class);
    }

    @Test
    void conflictingRulesMatchingSameCoordinateAre422() {
        // 两条同平台规则同时命中同一坐标（发布期本应拦截，解析器同样防御）。
        put("app", v(1, dep("legacy", 1, 1)));
        put("legacy", w(1));
        put("alt", v(1));
        SubstitutionPolicy policy = policy(
                rule("legacy", PLATFORM, NOW.minusSeconds(60), "1", "alt"),
                rule("lega*", PLATFORM, NOW.minusSeconds(60), "1", "alt"));

        assertThatThrownBy(() -> resolve(policy))
                .isInstanceOf(SubstitutionFailureException.class)
                .hasMessageContaining("冲突");
    }

    @Test
    void nullPolicyVersionMeansNoPolicyWasEverPublished() {
        put("app", v(1));
        SubstitutionResult result = SubstitutionLockResolver.resolve(
                snapshot(), availability(), null, "app", 1, PLATFORM, NOW);
        assertThat(result.versions()).containsEntry("app", 1);
        assertThat(result.policyVersion()).isNull();
        assertThat(result.steps()).isEmpty();
    }

    @Test
    void ruleForOtherPlatformIsIgnoredAndResolutionFails() {
        put("app", v(1, dep("legacy", 1, 1)));
        put("legacy", vPlatforms(1, Set.of("native")));
        put("alt", v(1));
        SubstitutionPolicy policy = policy(rule("legacy", "native", NOW.minusSeconds(60),
                "1", "alt"));

        // jvm 平台无生效规则，legacy 不可用属于普通不可行，返回 null。
        assertThat(resolve(policy)).isNull();
    }
}
