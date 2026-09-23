package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目标平台筛选与可选依赖两阶段解析的单元测试。
 *
 * <p>覆盖：平台过滤（ANY 与精确 os/arch）、不兼容精确根返回 null、
 * 必选回退不受可选依赖影响、可选依赖 included/skipped、新增制品的可选依赖继续扫描、
 * 可选不能覆盖根或更换已选、稳定遍历顺序与稳定原因、环。
 */
class PlatformOptionalLockResolverTest {

    private static final String LINUX = "linux/amd64";
    private static final String DARWIN = "darwin/arm64";

    /** 单个版本的构造输入：版本号、撤回、平台、依赖。 */
    private record TestVersion(int version, boolean withdrawn, List<String> platforms,
                               List<DependencyRange> deps) {
    }

    /** 快照组：名称 -> 版本输入列表。 */
    private record Group(String name, List<TestVersion> versions) {
    }

    private static RepositorySnapshot snapshot(long repoVersion, Group... groups) {
        Map<String, List<ArtifactVersion>> map = new TreeMap<>();
        AtomicLong id = new AtomicLong(1);
        for (Group group : groups) {
            List<ArtifactVersion> list = new ArrayList<>();
            for (TestVersion tv : group.versions()) {
                list.add(new ArtifactVersion(id.getAndIncrement(), group.name(), tv.version(),
                        tv.withdrawn(), tv.platforms(), tv.deps()));
            }
            map.put(group.name(), list);
        }
        return new RepositorySnapshot(repoVersion, map);
    }

    private static Group group(String name, TestVersion... versions) {
        return new Group(name, List.of(versions));
    }

    private static TestVersion v(int version, List<String> platforms, DependencyRange... deps) {
        return new TestVersion(version, false, platforms, List.of(deps));
    }

    private static TestVersion any(int version, DependencyRange... deps) {
        return v(version, List.of(Platforms.ANY), deps);
    }

    private static TestVersion w(int version, List<String> platforms, DependencyRange... deps) {
        return new TestVersion(version, true, platforms, List.of(deps));
    }

    private static DependencyRange dep(String name, int min, int max) {
        return new DependencyRange(name, min, max, false);
    }

    private static DependencyRange opt(String name, int min, int max) {
        return new DependencyRange(name, min, max, true);
    }

    // ------------------------------------------------------------------
    // 平台筛选
    // ------------------------------------------------------------------

    @Test
    void anyArtifactMatchesEveryTargetPlatform() {
        RepositorySnapshot s = snapshot(0,
                group("app", any(1, dep("lib", 1, 1))),
                group("lib", any(1)));
        ResolutionResult result = LockResolver.resolve(s, "app", 1, DARWIN);
        assertThat(result).isNotNull();
        assertThat(result.entries()).containsEntry("app", 1).containsEntry("lib", 1);
    }

    @Test
    void filtersCandidatesByExactPlatformAndPicksLowerCompatibleVersion() {
        // lib 只在 lib2 声明 linux，lib1 声明 darwin；linux 目标必须取 lib2 之外不可用时回退。
        // 这里 lib3 linux、lib2 darwin、lib1 linux，区间 [1,3]：linux 下最高可用是 lib3。
        RepositorySnapshot s = snapshot(0,
                group("app", any(1, dep("lib", 1, 3))),
                group("lib",
                        v(3, List.of(LINUX)),
                        v(2, List.of(DARWIN)),
                        v(1, List.of(LINUX))));
        ResolutionResult linuxResult = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(linuxResult.entries()).containsEntry("lib", 3);

        // darwin 下只有 lib2 兼容，必须跨平台回退到 lib2。
        ResolutionResult darwinResult = LockResolver.resolve(s, "app", 1, DARWIN);
        assertThat(darwinResult.entries()).containsEntry("lib", 2);
    }

    @Test
    void incompatiblePreciseRootReturnsNull() {
        // app:1 仅支持 darwin，对 linux 锁定应判定为不兼容根（null，调用方返回 422）。
        RepositorySnapshot s = snapshot(0,
                group("app", v(1, List.of(DARWIN))));
        assertThat(LockResolver.resolve(s, "app", 1, LINUX)).isNull();
    }

    @Test
    void mandatoryResolutionFailsWhenOnlyIncompatiblePlatformCandidatesExist() {
        // app(linux) 必选 lib[1,1]，但唯一 lib1 只支持 darwin。
        RepositorySnapshot s = snapshot(0,
                group("app", v(1, List.of(LINUX), dep("lib", 1, 1))),
                group("lib", v(1, List.of(DARWIN))));
        assertThat(LockResolver.resolve(s, "app", 1, LINUX)).isNull();
    }

    @Test
    void mandatoryBacktrackingIgnoresPlatformIncompatibleHighVersion() {
        // app -> b[1,2]、c[1,1]；linux 下 b2 存在但平台不兼容，应直接取 b1。
        RepositorySnapshot s = snapshot(0,
                group("app", any(1, dep("b", 1, 2), dep("c", 1, 1))),
                group("b",
                        v(2, List.of(DARWIN)),
                        v(1, List.of(LINUX))),
                group("c", any(1)));
        ResolutionResult result = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(result.entries()).containsEntry("b", 1).containsEntry("c", 1);
    }

    // ------------------------------------------------------------------
    // 必选阶段不受可选依赖影响
    // ------------------------------------------------------------------

    @Test
    void mandatoryPhaseDoesNotDegradeBecauseOfOptionalDependency() {
        // 必选：app -> lib[1,2]，取 lib2。
        // lib2 声明一个无解的可选依赖 ghost（根本不存在）；可选失败不得影响必选结果。
        RepositorySnapshot s = snapshot(0,
                group("app", any(1, dep("lib", 1, 2))),
                group("lib",
                        any(2, opt("ghost", 1, 1)),
                        any(1)));
        ResolutionResult result = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(result).isNotNull();
        assertThat(result.entries()).containsEntry("lib", 2).doesNotContainKey("ghost");
        assertThat(result.optionalDecisions()).singleElement()
                .satisfies(d -> {
                    assertThat(d.included()).isFalse();
                    assertThat(d.sourceName()).isEqualTo("lib");
                    assertThat(d.dependencyName()).isEqualTo("ghost");
                    assertThat(d.reason()).startsWith("no-compatible-candidate");
                });
    }

    // ------------------------------------------------------------------
    // 可选依赖：加入
    // ------------------------------------------------------------------

    @Test
    void optionalDependencyIsIncludedWithHighestFeasibleVersionAndMandatoryClosure() {
        // app 可选 plugin[1,2]；plugin2 必选 core[2,2]，plugin1 必选 core[1,1]。
        // 期望加入最高可行 plugin2 + core2。
        RepositorySnapshot s = snapshot(0,
                group("app", any(1, opt("plugin", 1, 2))),
                group("plugin",
                        any(2, dep("core", 2, 2)),
                        any(1, dep("core", 1, 1))),
                group("core", any(2), any(1)));
        ResolutionResult result = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(result.entries())
                .containsEntry("app", 1).containsEntry("plugin", 2).containsEntry("core", 2);
        assertThat(result.optionalDecisions()).singleElement()
                .satisfies(d -> {
                    assertThat(d.included()).isTrue();
                    assertThat(d.selectedVersion()).isEqualTo(2);
                    assertThat(d.reason()).isNull();
                });
    }

    @Test
    void optionalTargetAlreadySelectedAndSatisfyingIsMarkedIncluded() {
        // 必选把 lib 固定为 1；另一已选制品可选同 lib 且区间满足，记 included 不重复加入。
        RepositorySnapshot s = snapshot(0,
                group("app", any(1, dep("lib", 1, 1), opt("lib", 1, 2))),
                group("lib", any(1)));
        ResolutionResult result = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(result.entries()).hasSize(2).containsEntry("lib", 1);
        assertThat(result.optionalDecisions()).singleElement()
                .satisfies(d -> {
                    assertThat(d.included()).isTrue();
                    assertThat(d.selectedVersion()).isEqualTo(1);
                });
    }

    @Test
    void optionalTargetAlreadySelectedButOutOfRangeIsSkippedAndNotReplaced() {
        // 必选固定 lib1；可选声明 lib[2,2]，已选版本不满足：skipped，绝不升级到 lib2。
        RepositorySnapshot s = snapshot(0,
                group("app", any(1, dep("lib", 1, 1), opt("lib", 2, 2))),
                group("lib", any(2), any(1)));
        ResolutionResult result = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(result.entries()).containsEntry("lib", 1);
        assertThat(result.optionalDecisions()).singleElement()
                .satisfies(d -> {
                    assertThat(d.included()).isFalse();
                    assertThat(d.selectedVersion()).isNull();
                    assertThat(d.reason()).startsWith("selected-version-out-of-range");
                });
    }

    @Test
    void optionalDependencyCanNeverOverrideRoot() {
        // 根 app:1 固定；app 可选 app[2,2]：目标名称是根，已选且区间不满足，只能 skipped。
        RepositorySnapshot s = snapshot(0,
                group("app", any(2), any(1, opt("app", 2, 2))));
        ResolutionResult result = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(result.entries()).containsEntry("app", 1);
        assertThat(result.optionalDecisions()).singleElement()
                .satisfies(d -> {
                    assertThat(d.included()).isFalse();
                    assertThat(d.reason()).startsWith("selected-version-out-of-range");
                });
    }

    @Test
    void skippedWhenHighestVersionClosureInfeasibleButLockStillSucceeds() {
        // app 可选 plug[1,1]；plug1 必选 need[2,2]，但仅有 need1：闭包不可行，
        // 可选记 skipped，锁定本身仍成功。
        RepositorySnapshot s = snapshot(0,
                group("app", any(1, opt("plug", 1, 1))),
                group("plug", any(1, dep("need", 2, 2))),
                group("need", any(1)));
        ResolutionResult result = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(result).isNotNull();
        assertThat(result.entries()).containsOnlyKeys("app");
        assertThat(result.optionalDecisions()).singleElement()
                .satisfies(d -> {
                    assertThat(d.included()).isFalse();
                    assertThat(d.reason()).startsWith("infeasible-mandatory-closure");
                });
    }

    @Test
    void optionalInclusionMustNotChangeExistingMandatorySelections() {
        // 必选：app -> base[1,1]；opt:1 必选 base[2,2]，与已选 base1 冲突。
        // 加入 opt 不能把 base 换成 2，必须 skipped。
        RepositorySnapshot s = snapshot(0,
                group("app", any(1, dep("base", 1, 1), opt("opt", 1, 1))),
                group("base", any(2), any(1)),
                group("opt", any(1, dep("base", 2, 2))));
        ResolutionResult result = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(result.entries()).containsEntry("base", 1).doesNotContainKey("opt");
        assertThat(result.optionalDecisions()).singleElement()
                .satisfies(d -> {
                    assertThat(d.included()).isFalse();
                    assertThat(d.reason()).startsWith("infeasible-mandatory-closure");
                });
    }

    @Test
    void scanningContinuesIntoOptionalDependenciesOfNewlyAddedArtifacts() {
        // app 可选 a[1,1]；加入 a 后，a 的可选 b[1,1] 也应被扫描并加入；b 再可选 c[1,1]。
        RepositorySnapshot s = snapshot(0,
                group("app", any(1, opt("a", 1, 1))),
                group("a", any(1, opt("b", 1, 1))),
                group("b", any(1, opt("c", 1, 1))),
                group("c", any(1)));
        ResolutionResult result = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(result.entries()).containsKeys("a", "b", "c");
        assertThat(result.optionalDecisions()).hasSize(3);
    }

    @Test
    void optionalPlatformFilteringSkipsIncompatibleCandidate() {
        // app 可选 plug[1,1]；唯一 plug1 仅支持 darwin，linux 下记 no-compatible-candidate。
        RepositorySnapshot s = snapshot(0,
                group("app", any(1, opt("plug", 1, 1))),
                group("plug", v(1, List.of(DARWIN))));
        ResolutionResult result = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(result.entries()).containsOnlyKeys("app");
        assertThat(result.optionalDecisions()).singleElement()
                .satisfies(d -> {
                    assertThat(d.included()).isFalse();
                    assertThat(d.reason()).startsWith("no-compatible-candidate");
                });
    }

    @Test
    void optionalClosureFiltersNewMandatoryArtifactsByPlatform() {
        // app(any) 可选 plug[1,1](any)；plug 必选 helper[1,1]，helper1 仅 darwin。
        // linux 下闭包不可行 -> skipped（infeasible-mandatory-closure）。
        RepositorySnapshot s = snapshot(0,
                group("app", any(1, opt("plug", 1, 1))),
                group("plug", any(1, dep("helper", 1, 1))),
                group("helper", v(1, List.of(DARWIN))));
        ResolutionResult result = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(result.entries()).containsOnlyKeys("app");
        assertThat(result.optionalDecisions()).singleElement()
                .satisfies(d -> assertThat(d.reason()).startsWith("infeasible-mandatory-closure"));
    }

    // ------------------------------------------------------------------
    // 遍历顺序与环
    // ------------------------------------------------------------------

    @Test
    void decisionsAreOrderedBySourceThenDependencyName() {
        // app 声明 zeta、alpha 两个可选；mid 被必选引入后也带一个可选。
        // 期望顺序：(app,alpha)、(app,zeta)、(mid,extra)（新增 mid 后继续扫描）。
        RepositorySnapshot s = snapshot(0,
                group("app", any(1,
                        dep("mid", 1, 1),
                        opt("zeta", 1, 1),
                        opt("alpha", 1, 1))),
                group("mid", any(1, opt("extra", 1, 1))),
                group("alpha", any(1)),
                group("zeta", any(1)),
                group("extra", any(1)));
        ResolutionResult result = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(result.optionalDecisions())
                .extracting(OptionalDecision::sourceName, OptionalDecision::dependencyName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("app", "alpha"),
                        org.assertj.core.groups.Tuple.tuple("app", "zeta"),
                        org.assertj.core.groups.Tuple.tuple("mid", "extra"));
    }

    @Test
    void newlyAddedArtifactWithLexicographicallyEarlierEdgeIsScannedBeforeLaterOnes() {
        // app 只有可选 zzz；加入 zzz 后 zzz 可选 aaa。
        // 处理 (app,zzz) 加入 zzz；下一轮最小边是 (zzz,aaa)，应继续被处理。
        RepositorySnapshot s = snapshot(0,
                group("app", any(1, opt("zzz", 1, 1))),
                group("zzz", any(1, opt("aaa", 1, 1))),
                group("aaa", any(1)));
        ResolutionResult result = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(result.entries()).containsKeys("zzz", "aaa");
        assertThat(result.optionalDecisions())
                .extracting(OptionalDecision::sourceName, OptionalDecision::dependencyName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("app", "zzz"),
                        org.assertj.core.groups.Tuple.tuple("zzz", "aaa"));
    }

    @Test
    void optionalCycleDoesNotBreakResolution() {
        // 必选 app:1；app 可选 a[1,1]，a 可选 app[1,1]（根，已选且满足 -> included），形成环。
        RepositorySnapshot s = snapshot(0,
                group("app", any(1, opt("a", 1, 1))),
                group("a", any(1, opt("app", 1, 1))));
        ResolutionResult result = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(result.entries()).containsEntry("app", 1).containsEntry("a", 1);
        assertThat(result.optionalDecisions()).hasSize(2);
        assertThat(result.optionalDecisions()).allMatch(OptionalDecision::included);
    }
}
