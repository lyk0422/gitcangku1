package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 目标平台与可选依赖两阶段解析的单元测试：
 * 平台筛选、必选回退不被可选依赖影响、可选依赖加入/跳过/已选命中、环依赖与稳定顺序。
 */
class PlatformOptionalLockResolverTest {

    private static final String LINUX = "linux/amd64";
    private static final String DARWIN = "darwin/arm64";

    /** 测试用快照：按 name -> 版本（平台，版本号，是否撤回，依赖列表）组织，版本降序。 */
    private static RepositorySnapshot snapshot(long version, Object... groups) {
        Map<String, List<ArtifactVersion>> map = new TreeMap<>();
        AtomicLong id = new AtomicLong(1);
        for (int i = 0; i < groups.length; i += 2) {
            String name = (String) groups[i];
            @SuppressWarnings("unchecked")
            List<TestVersion> versions = (List<TestVersion>) groups[i + 1];
            List<ArtifactVersion> list = versions.stream()
                    .map(vx -> new ArtifactVersion(id.getAndIncrement(), name, vx.version(),
                            vx.withdrawn(), vx.platforms(), vx.deps()))
                    .toList();
            map.put(name, list);
        }
        return new RepositorySnapshot(version, map);
    }

    private record TestVersion(List<String> platforms, int version, boolean withdrawn,
                               List<DependencyRange> deps) {
    }

    private static TestVersion v(int version, DependencyRange... deps) {
        return new TestVersion(List.of(Platforms.ANY), version, false, List.of(deps));
    }

    private static TestVersion v(List<String> platforms, int version, DependencyRange... deps) {
        return new TestVersion(platforms, version, false, List.of(deps));
    }

    private static TestVersion w(int version, DependencyRange... deps) {
        return new TestVersion(List.of(Platforms.ANY), version, true, List.of(deps));
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
    void anyArtifactSupportsEveryPlatform() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("lib", 1, 1))),
                "lib", List.of(v(1)));
        LockResolution r = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(r.chosen()).containsEntry("app", 1).containsEntry("lib", 1);
        assertThat(r.targetPlatform()).isEqualTo(LINUX);
    }

    @Test
    void mandatoryCandidateOnWrongPlatformIsSkippedForLowerCompatibleOne() {
        // lib2 仅支持 darwin，lib1 支持 linux；linux 平台必须回退到 lib1。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("lib", 1, 2))),
                "lib", List.of(
                        v(List.of(DARWIN), 2),
                        v(List.of(LINUX), 1)));
        LockResolution r = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(r.chosen()).containsEntry("lib", 1);
    }

    @Test
    void noMandatoryCandidateForPlatformMakesResolutionNull() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("lib", 1, 2))),
                "lib", List.of(v(List.of(DARWIN), 2), v(List.of(DARWIN), 1)));
        assertThat(LockResolver.resolve(s, "app", 1, LINUX)).isNull();
    }

    @Test
    void exactRootOnWrongPlatformRaises422Semantics() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(List.of(DARWIN), 1)));
        assertThatThrownBy(() -> LockResolver.resolve(s, "app", 1, LINUX))
                .isInstanceOf(PlatformUnsupportedException.class);
    }

    @Test
    void withdrawnPlatformSpecificVersionIsSkipped() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("lib", 1, 2))),
                "lib", List.of(
                        new TestVersion(List.of(LINUX), 2, true, List.of()),
                        v(List.of(LINUX), 1)));
        LockResolution r = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(r.chosen()).containsEntry("lib", 1);
    }

    // ------------------------------------------------------------------
    // 必选阶段不受可选依赖影响
    // ------------------------------------------------------------------

    @Test
    void mandatoryPhaseIgnoresOptionalRanges() {
        // app 对 lib 只有可选依赖 [2,2]，lib 仅有 1 且无必选路径：必选集合只有 app，锁定成功。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, opt("lib", 2, 2))),
                "lib", List.of(v(1)));
        LockResolution r = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(r.chosen()).isEqualTo(Map.of("app", 1));
        assertThat(r.optionalOutcomes()).hasSize(1);
        assertThat(r.optionalOutcomes().get(0).included()).isFalse();
    }

    @Test
    void optionalDependencyNeverDowngradesMandatoryChoice() {
        // app -> 必选 lib[1,2]，取 lib2；lib2 可选依赖 ghost[1,1]（ghost 不存在）。
        // 必选结果必须保持 lib2，可选仅记 skipped。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("lib", 1, 2))),
                "lib", List.of(v(2, opt("ghost", 1, 1)), v(1)));
        LockResolution r = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(r.chosen()).containsEntry("lib", 2).doesNotContainKey("ghost");
        assertThat(r.optionalOutcomes()).singleElement()
                .satisfies(o -> {
                    assertThat(o.included()).isFalse();
                    assertThat(o.sourceName()).isEqualTo("lib");
                    assertThat(o.dependencyName()).isEqualTo("ghost");
                    assertThat(o.reason()).contains("ghost");
                });
    }

    // ------------------------------------------------------------------
    // 可选依赖：加入、已选命中、跳过
    // ------------------------------------------------------------------

    @Test
    void optionalDependencyNotSelectedIsIncludedWithMandatoryClosure() {
        // app 可选 plugin[1,1]；plugin1 -> 必选 util[1,1]。二者一并加入。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, opt("plugin", 1, 1))),
                "plugin", List.of(v(1, dep("util", 1, 1)), v(2)),
                "util", List.of(v(1)));
        LockResolution r = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(r.chosen())
                .containsEntry("app", 1)
                .containsEntry("plugin", 1)
                .containsEntry("util", 1);
        assertThat(r.optionalOutcomes()).singleElement()
                .satisfies(o -> {
                    assertThat(o.included()).isTrue();
                    assertThat(o.targetVersion()).isEqualTo(1);
                });
    }

    @Test
    void optionalDependencyPicksHighestFeasibleVersion() {
        // plugin2 的必选闭包不可行（需要不存在的 util2），必须降到 plugin1。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, opt("plugin", 1, 2))),
                "plugin", List.of(
                        v(2, dep("util", 2, 2)),
                        v(1, dep("util", 1, 1))),
                "util", List.of(v(1)));
        LockResolution r = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(r.chosen()).containsEntry("plugin", 1).containsEntry("util", 1);
        assertThat(r.optionalOutcomes().get(0).targetVersion()).isEqualTo(1);
    }

    @Test
    void optionalCandidateOnWrongPlatformIsSkipped() {
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, opt("plugin", 1, 1))),
                "plugin", List.of(v(List.of(DARWIN), 1)));
        LockResolution r = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(r.chosen()).doesNotContainKey("plugin");
        assertThat(r.optionalOutcomes().get(0).included()).isFalse();
        assertThat(r.optionalOutcomes().get(0).reason()).contains(LINUX);
    }

    @Test
    void alreadySelectedMatchingVersionIsMarkedIncluded() {
        // app 必选 lib[1,1]，同时可选 lib[1,1]：已选且满足，直接 included 不重复选择。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("lib", 1, 1), opt("lib", 1, 1))),
                "lib", List.of(v(1)));
        LockResolution r = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(r.chosen()).hasSize(2).containsEntry("lib", 1);
        assertThat(r.optionalOutcomes()).singleElement()
                .satisfies(o -> {
                    assertThat(o.included()).isTrue();
                    assertThat(o.targetVersion()).isEqualTo(1);
                });
    }

    @Test
    void alreadySelectedOutOfRangeVersionIsSkippedAndNeverReplaced() {
        // 必选取 lib2；可选声明 lib[1,1]：已选版本不满足，跳过且不能回退到 lib1。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("lib", 1, 2), opt("lib", 1, 1))),
                "lib", List.of(v(2), v(1)));
        LockResolution r = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(r.chosen()).containsEntry("lib", 2);
        assertThat(r.optionalOutcomes()).singleElement()
                .satisfies(o -> {
                    assertThat(o.included()).isFalse();
                    assertThat(o.reason()).contains("不得更换已选版本");
                });
    }

    @Test
    void scanningContinuesIntoNewlyIncludedArtifacts() {
        // app 可选 plugin；plugin 可选 extra。加入 plugin 后必须继续扫描到 extra 并加入。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, opt("plugin", 1, 1))),
                "plugin", List.of(v(1, opt("extra", 1, 1))),
                "extra", List.of(v(1)));
        LockResolution r = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(r.chosen()).containsEntry("plugin", 1).containsEntry("extra", 1);
        assertThat(r.optionalOutcomes())
                .extracting(OptionalDependencyOutcome::sourceName,
                        OptionalDependencyOutcome::dependencyName,
                        OptionalDependencyOutcome::included)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("app", "plugin", true),
                        org.assertj.core.groups.Tuple.tuple("plugin", "extra", true));
    }

    @Test
    void optionalOutcomesAreOrderedBySourceThenDependencyName() {
        // app 声明两条可选依赖 zeta、alpha；顺序必须为 (app,alpha) 先于 (app,zeta)。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, opt("zeta", 1, 1), opt("alpha", 1, 1))),
                "zeta", List.of(v(1)),
                "alpha", List.of(v(1)));
        LockResolution r = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(r.optionalOutcomes())
                .extracting(OptionalDependencyOutcome::sourceName,
                        OptionalDependencyOutcome::dependencyName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("app", "alpha"),
                        org.assertj.core.groups.Tuple.tuple("app", "zeta"));
    }

    @Test
    void optionalClosureCannotChangeExistingMandatorySelection() {
        // 必选 lib2（app -> lib[2,2]）。plugin1 必选 lib[1,1] 与已选 lib2 冲突：
        // 加入 plugin 必须失败，lib2 保持不变。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("lib", 2, 2), opt("plugin", 1, 1))),
                "lib", List.of(v(2), v(1)),
                "plugin", List.of(v(1, dep("lib", 1, 1))));
        LockResolution r = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(r.chosen()).containsEntry("lib", 2).doesNotContainKey("plugin");
        assertThat(r.optionalOutcomes().get(0).included()).isFalse();
        assertThat(r.optionalOutcomes().get(0).reason()).contains("必选闭包");
    }

    @Test
    void consistentCycleThroughOptionalClosureIsIncluded() {
        // app 可选 a1；a1 -> 必选 app[1,1]（根固定 1，环一致），加入成功。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, opt("a", 1, 1)), v(2)),
                "a", List.of(v(1, dep("app", 1, 1))));
        LockResolution r = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(r.chosen()).containsEntry("a", 1);
        assertThat(r.optionalOutcomes().get(0).included()).isTrue();
    }

    @Test
    void conflictingCycleInOptionalClosureIsSkipped() {
        // app:1 可选 a1；a1 要求 app[2,2]，根固定 1 不可更换：跳过 a。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, opt("a", 1, 1)), v(2)),
                "a", List.of(v(1, dep("app", 2, 2))));
        LockResolution r = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(r.chosen()).doesNotContainKey("a");
        assertThat(r.optionalOutcomes().get(0).included()).isFalse();
    }

    @Test
    void optionalFailureNeverFailsWholeResolution() {
        // 必选可行但唯一可选依赖无法加入：整体仍返回成功结果并带 skipped 明细。
        RepositorySnapshot s = snapshot(0,
                "app", List.of(v(1, dep("core", 1, 1), opt("ghost", 1, 1))),
                "core", List.of(v(1)));
        LockResolution r = LockResolver.resolve(s, "app", 1, LINUX);
        assertThat(r.chosen()).containsEntry("core", 1);
        assertThat(r.optionalOutcomes()).hasSize(1);
        assertThat(r.optionalOutcomes().get(0).included()).isFalse();
    }
}
