package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定解析器单元测试：回溯、环依赖、撤回版本过滤与字典序解析顺序。
 */
class LockResolverTest {

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
                            v.withdrawn(), v.deps(), v.platforms()))
                    .toList();
            map.put(name, list);
        }
        return new RepositorySnapshot(version, map);
    }

    private record TestVersion(int version, boolean withdrawn, List<DependencyRange> deps,
                               List<String> platforms) {
    }

    private static TestVersion v(int version, DependencyRange... deps) {
        return new TestVersion(version, false, List.of(deps), List.of());
    }

    private static TestVersion w(int version, DependencyRange... deps) {
        return new TestVersion(version, true, List.of(deps), List.of());
    }

    private static TestVersion vp(List<String> platforms, int version, DependencyRange... deps) {
        return new TestVersion(version, false, List.of(deps), platforms);
    }

    private static DependencyRange dep(String name, int min, int max) {
        return new DependencyRange(name, min, max, false);
    }

    private static DependencyRange opt(String name, int min, int max) {
        return new DependencyRange(name, min, max, true);
    }

    private static final String LINUX = "linux/x64";
    private static final String DARWIN = "darwin/arm64";

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
    // 目标平台筛选
    // ------------------------------------------------------------------

    @Test
    void platformFiltersCandidatesAndBacktracksToLowerCompatibleVersion() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(vp(List.of(LINUX), 1, dep("lib", 1, 2))),
                "lib", List.of(vp(List.of(DARWIN), 2), vp(List.of(LINUX), 1)));
        LockResolution resolution = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(resolution).isNotNull();
        assertThat(resolution.chosen()).containsEntry("lib", 1);
    }

    @Test
    void anyPlatformCandidatesAlwaysParticipate() {
        // 根无平台数据（迁移为 ANY），候选显式 ANY：任意目标平台均可锁定。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("lib", 1, 1))),
                "lib", List.of(vp(List.of("ANY"), 1)));
        assertThat(LockResolver.resolve(s, "app", 1, LINUX).chosen())
                .containsEntry("lib", 1);
        assertThat(LockResolver.resolve(s, "app", 1, "solaris/sparc").chosen())
                .containsEntry("lib", 1);
    }

    @Test
    void rootWithoutPlatformDataSupportsEveryPlatform() {
        RepositorySnapshot s = snapshot(0, "app", List.of(v(1)));
        assertThat(LockResolver.resolve(s, "app", 1, LINUX).chosen())
                .isEqualTo(Map.of("app", 1));
    }

    @Test
    void incompatibleRootThrowsAndMandatoryIncompatibilityReturnsNull() {
        RepositorySnapshot rootOnly = snapshot(0,
                "app", List.of(vp(List.of(DARWIN), 1)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> LockResolver.resolve(rootOnly, "app", 1, LINUX));

        // 根兼容，但唯一必选候选只支持其他平台 → 必选无解。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(vp(List.of(LINUX), 1, dep("lib", 1, 1))),
                "lib", List.of(vp(List.of(DARWIN), 1)));
        assertThat(LockResolver.resolve(s, "app", 1, LINUX)).isNull();
    }

    // ------------------------------------------------------------------
    // 可选依赖
    // ------------------------------------------------------------------

    @Test
    void mandatoryPhaseIgnoresOptionalDependencies() {
        // app 只可选 lib[1,1]：lib 不参与必选求解（必选集合仅 app 即可成功），
        // 随后可选阶段把 lib1 纳入，产生一条 included 评估。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, opt("lib", 1, 1))),
                "lib", List.of(v(1)));
        LockResolution resolution = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(resolution.chosen()).containsExactlyInAnyOrderEntriesOf(
                Map.of("app", 1, "lib", 1));
        assertThat(resolution.optionalResults()).singleElement().satisfies(o -> {
            assertThat(o.status()).isEqualTo(LockResolution.OptionalStatus.INCLUDED);
            assertThat(o.selectedVersion()).isEqualTo(1);
        });
    }

    @Test
    void optionalInclusionAddsMandatoryClosureOfNewArtifact() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, opt("plugin", 1, 1))),
                "plugin", List.of(v(1, dep("helper", 1, 1))),
                "helper", List.of(v(1)));
        LockResolution resolution = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(resolution.chosen()).containsExactlyInAnyOrderEntriesOf(
                Map.of("app", 1, "plugin", 1, "helper", 1));
        assertThat(resolution.optionalResults()).singleElement()
                .extracting("status", "selectedVersion")
                .containsExactly(LockResolution.OptionalStatus.INCLUDED, 1);
    }

    @Test
    void optionalClosureConflictIsSkippedWithoutDowngradingMandatoryChoice() {
        // 必选取 lib2（app -> lib[1,2]）；plugin1 必选 lib[1,1] 与 lib2 冲突 → skipped，lib 保持 2。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("lib", 1, 2), opt("plugin", 1, 1))),
                "lib", List.of(v(2), v(1)),
                "plugin", List.of(v(1, dep("lib", 1, 1))));
        LockResolution resolution = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(resolution.chosen()).containsEntry("lib", 2).doesNotContainKey("plugin");
        assertThat(resolution.optionalResults()).singleElement().satisfies(o -> {
            assertThat(o.status()).isEqualTo(LockResolution.OptionalStatus.SKIPPED);
            assertThat(o.reason()).isEqualTo(LockResolution.SkipReason.CLOSURE_INFEASIBLE);
        });
    }

    @Test
    void optionalMissingTargetIsSkippedWithNoCompatibleCandidate() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, opt("ghost", 1, 1))));
        LockResolution resolution = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(resolution.optionalResults()).singleElement().satisfies(o -> {
            assertThat(o.status()).isEqualTo(LockResolution.OptionalStatus.SKIPPED);
            assertThat(o.reason()).isEqualTo(LockResolution.SkipReason.NO_COMPATIBLE_CANDIDATE);
        });
    }

    @Test
    void optionalTargetSelectedOutOfRangeIsSkippedAndSelectionUntouched() {
        // 必选固定 lib2；可选 lib[1,1] 不满足。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("lib", 2, 2), opt("lib", 1, 1))),
                "lib", List.of(v(2), v(1)));
        LockResolution resolution = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(resolution.chosen()).containsEntry("lib", 2);
        assertThat(resolution.optionalResults()).singleElement().satisfies(o -> {
            assertThat(o.status()).isEqualTo(LockResolution.OptionalStatus.SKIPPED);
            assertThat(o.reason()).isEqualTo(LockResolution.SkipReason.SELECTED_VERSION_OUT_OF_RANGE);
        });
    }

    @Test
    void optionalDependenciesAreScannedInSourceThenDependencyOrderIncludingNewArtifacts() {
        // app 必选 zeta；zeta 可选 zhelper（存在）；app 可选 ahelper（存在）。
        // 顺序应为 (app,ahelper) -> (zeta,zhelper)，且新增制品的可选依赖继续被扫描。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("zeta", 1, 1), opt("ahelper", 1, 1))),
                "zeta", List.of(v(1, opt("zhelper", 1, 1))),
                "ahelper", List.of(v(1, opt("deep", 1, 1))),
                "zhelper", List.of(v(1)),
                "deep", List.of(v(1)));
        LockResolution resolution = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(resolution.optionalResults()).hasSize(3);
        // (app,ahelper) 最先；纳入 ahelper 后其可选 (ahelper,deep) 按字典序插入；
        // (zeta,zhelper) 最后。
        assertThat(resolution.optionalResults().get(0).sourceName()).isEqualTo("app");
        assertThat(resolution.optionalResults().get(0).dependencyName()).isEqualTo("ahelper");
        assertThat(resolution.optionalResults().get(1).sourceName()).isEqualTo("ahelper");
        assertThat(resolution.optionalResults().get(1).dependencyName()).isEqualTo("deep");
        assertThat(resolution.optionalResults().get(2).sourceName()).isEqualTo("zeta");
        assertThat(resolution.optionalResults().get(2).dependencyName()).isEqualTo("zhelper");
        assertThat(resolution.chosen()).containsKeys("app", "zeta", "ahelper", "zhelper", "deep");
    }

    @Test
    void optionalPointingAtRootOutOfRangeIsSkippedAndRootNeverReplaced() {
        // 根固定 app:1；core 可选 app[2,2]：根已选且版本不满足，记 skipped，根保持 1。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(2), v(1, dep("core", 1, 1))),
                "core", List.of(v(1, opt("app", 2, 2))));
        LockResolution resolution = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(resolution.chosen()).containsEntry("app", 1).doesNotContainKey("app2");
        assertThat(resolution.optionalResults()).singleElement().satisfies(o -> {
            assertThat(o.sourceName()).isEqualTo("core");
            assertThat(o.dependencyName()).isEqualTo("app");
            assertThat(o.status()).isEqualTo(LockResolution.OptionalStatus.SKIPPED);
            assertThat(o.reason()).isEqualTo(LockResolution.SkipReason.SELECTED_VERSION_OUT_OF_RANGE);
        });
    }

    @Test
    void optionalCandidateIncompatibleByPlatformIsSkipped() {        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, opt("plugin", 1, 1))),
                "plugin", List.of(vp(List.of(DARWIN), 1)));
        LockResolution resolution = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(resolution.optionalResults()).singleElement().satisfies(o -> {
            assertThat(o.status()).isEqualTo(LockResolution.OptionalStatus.SKIPPED);
            assertThat(o.reason()).isEqualTo(LockResolution.SkipReason.NO_COMPATIBLE_CANDIDATE);
        });
        assertThat(resolution.chosen()).doesNotContainKey("plugin");
    }

    @Test
    void optionalFailureDoesNotAffectMandatoryBacktrackingResult() {
        // 必选回溯场景保持原有选择；可选依赖不可行仅附加 skipped。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("b", 1, 2), dep("c", 1, 1), opt("extra", 1, 1))),
                "b", List.of(v(2, dep("c", 2, 2)), v(1)),
                "c", List.of(v(1)));
        LockResolution resolution = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(resolution.chosen()).containsEntry("b", 1).containsEntry("c", 1);
        assertThat(resolution.optionalResults()).singleElement()
                .extracting("status").isEqualTo(LockResolution.OptionalStatus.SKIPPED);
    }
}
