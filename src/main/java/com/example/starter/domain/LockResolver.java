package com.example.starter.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 依赖锁定解析器：在一致性仓库快照上按平台筛选并带回溯地求解精确版本组合。
 *
 * <p>解析分两个阶段：
 * <ol>
 *   <li>必选阶段：根版本固定；其余名称按字典序选择下一个尚未解析的必选依赖，
 *       候选版本从高到低尝试（仅保留支持目标平台或 ANY 的未撤回版本），
 *       遇冲突回退，取首个完整可行结果。必选阶段绝不因可选依赖降级或失败。</li>
 *   <li>可选阶段：按“来源名称、依赖名称”字典序遍历当前已选制品的可选依赖。
 *       目标名称已选且版本满足则记为 included；未选时在不改变已选集合的前提下
 *       尝试加入最高可行版本及其必选闭包，成功继续扫描新增制品的可选依赖，
 *       失败记为 skipped 并保存稳定原因。可选依赖不能覆盖根或更换此前选择。</li>
 * </ol>
 * 已选制品之间必须互相满足必选区间约束，因此必选依赖图存在环时也会被正确校验。
 */
public final class LockResolver {

    private LockResolver() {
    }

    /**
     * 兼容旧行为的求解入口：等价于目标平台为 ANY（无平台约束、无可选依赖处理差异）。
     *
     * @return 名称 -> 精确版本（名称升序）；必选组合不可行时返回 null
     */
    public static Map<String, Integer> resolve(RepositorySnapshot snapshot,
                                               String rootName, int rootVersion) {
        LockResolution resolution = resolve(snapshot, rootName, rootVersion, "ANY");
        return resolution == null ? null : resolution.chosen();
    }

    /**
     * 按目标平台求解锁定组合。
     *
     * @param snapshot       一致性仓库快照（每名称版本按版本号降序）
     * @param rootName       根制品名称
     * @param rootVersion    根制品精确版本（必须存在且未撤回，由调用方保证）
     * @param targetPlatform 目标平台 os/arch，非空
     * @return 完整解析结果；必选依赖无可行解时返回 null。根不支持目标平台时抛出
     *         {@link IllegalArgumentException}，由调用方转换为 422
     */
    public static LockResolution resolve(RepositorySnapshot snapshot,
                                         String rootName, int rootVersion, String targetPlatform) {
        Map<String, List<ArtifactVersion>> all = snapshot.artifacts();
        List<ArtifactVersion> rootCandidates = all.get(rootName);
        if (rootCandidates == null) {
            throw new IllegalArgumentException("根制品不存在: " + rootName);
        }
        ArtifactVersion root = rootCandidates.stream()
                .filter(a -> a.version() == rootVersion && !a.withdrawn())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "根制品版本不存在或已撤回: " + rootName + ":" + rootVersion));
        if (!root.supports(targetPlatform)) {
            throw new IllegalArgumentException(
                    "根制品版本不支持目标平台 " + targetPlatform + ": " + rootName + ":" + rootVersion);
        }

        // ---------------- 必选阶段 ----------------
        TreeMap<String, ArtifactVersion> chosen = new TreeMap<>();
        chosen.put(rootName, root);
        if (!satisfiesMandatoryChosen(root, chosen) || !solveMandatory(all, chosen, targetPlatform)) {
            return null;
        }

        // ---------------- 可选阶段 ----------------
        List<LockResolution.OptionalResolution> optionalResults = resolveOptional(
                all, chosen, targetPlatform);

        TreeMap<String, Integer> result = new TreeMap<>();
        chosen.forEach((name, artifact) -> result.put(name, artifact.version()));
        return new LockResolution(snapshot.repositoryVersion(), result, optionalResults);
    }

    /**
     * 递归回溯：选出字典序最小的未解析必选依赖名称，从高到低尝试候选版本。
     * 只汇总必选依赖的区间约束，候选必须支持目标平台。
     */
    private static boolean solveMandatory(Map<String, List<ArtifactVersion>> all,
                                          TreeMap<String, ArtifactVersion> chosen,
                                          String targetPlatform) {
        String next = null;
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (ArtifactVersion artifact : chosen.values()) {
            for (DependencyRange dep : artifact.dependencies()) {
                if (dep.optional() || chosen.containsKey(dep.name())) {
                    continue;
                }
                if (next == null || dep.name().compareTo(next) < 0) {
                    next = dep.name();
                    lo = dep.minimumVersion();
                    hi = dep.maximumVersion();
                } else if (dep.name().equals(next)) {
                    lo = Math.max(lo, dep.minimumVersion());
                    hi = Math.min(hi, dep.maximumVersion());
                }
            }
        }

        if (next == null) {
            return true;
        }
        if (lo > hi) {
            return false;
        }

        List<ArtifactVersion> candidates = all.getOrDefault(next, List.of());
        for (ArtifactVersion candidate : candidates) {
            if (candidate.withdrawn() || candidate.version() < lo || candidate.version() > hi) {
                continue;
            }
            if (!candidate.supports(targetPlatform)) {
                continue;
            }
            if (!satisfiesMandatoryChosen(candidate, chosen)) {
                continue;
            }
            chosen.put(next, candidate);
            if (solveMandatory(all, chosen, targetPlatform)) {
                return true;
            }
            chosen.remove(next);
        }
        return false;
    }

    /**
     * 可选依赖阶段：按（来源名称、依赖名称）字典序稳定扫描，已选集合只增不改。
     */
    private static List<LockResolution.OptionalResolution> resolveOptional(
            Map<String, List<ArtifactVersion>> all,
            TreeMap<String, ArtifactVersion> chosen,
            String targetPlatform) {

        List<LockResolution.OptionalResolution> results = new ArrayList<>();
        TreeSet<OptionalRef> processed = new TreeSet<>();
        while (true) {
            // 每次加入新制品后重新收集：新增制品的可选依赖也进入稳定扫描序列。
            TreeSet<OptionalRef> pending = new TreeSet<>();
            for (ArtifactVersion artifact : chosen.values()) {
                for (DependencyRange dep : artifact.dependencies()) {
                    if (!dep.optional()) {
                        continue;
                    }
                    OptionalRef ref = new OptionalRef(artifact.name(), dep.name());
                    if (!processed.contains(ref)) {
                        pending.add(new OptionalRef(artifact.name(), dep.name(),
                                dep.minimumVersion(), dep.maximumVersion()));
                    }
                }
            }
            if (pending.isEmpty()) {
                return results;
            }
            OptionalRef ref = pending.first();
            processed.add(new OptionalRef(ref.sourceName(), ref.dependencyName()));
            results.add(resolveOneOptional(all, chosen, targetPlatform, ref));
        }
    }

    /**
     * 处理单条可选依赖，返回 included/skipped 结论；成功时把新版本与必选闭包提交进 chosen。
     */
    private static LockResolution.OptionalResolution resolveOneOptional(
            Map<String, List<ArtifactVersion>> all,
            TreeMap<String, ArtifactVersion> chosen,
            String targetPlatform,
            OptionalRef ref) {

        ArtifactVersion selected = chosen.get(ref.dependencyName());
        if (selected != null) {
            // 目标名称已选：版本满足即 included，否则不能更换已选版本，记 skipped。
            if (selected.version() >= ref.minimumVersion()
                    && selected.version() <= ref.maximumVersion()) {
                return LockResolution.OptionalResolution.included(
                        ref.sourceName(), ref.dependencyName(), selected.version());
            }
            return LockResolution.OptionalResolution.skipped(
                    ref.sourceName(), ref.dependencyName(),
                    LockResolution.SkipReason.SELECTED_VERSION_OUT_OF_RANGE);
        }

        boolean sawCandidate = false;
        List<ArtifactVersion> candidates = all.getOrDefault(ref.dependencyName(), List.of());
        for (ArtifactVersion candidate : candidates) {
            if (candidate.withdrawn()
                    || candidate.version() < ref.minimumVersion()
                    || candidate.version() > ref.maximumVersion()
                    || !candidate.supports(targetPlatform)) {
                continue;
            }
            sawCandidate = true;
            // 在副本上尝试，失败绝不改变已选集合。
            TreeMap<String, ArtifactVersion> trial = new TreeMap<>(chosen);
            if (!satisfiesMandatoryChosen(candidate, trial)) {
                continue;
            }
            trial.put(candidate.name(), candidate);
            if (solveMandatory(all, trial, targetPlatform)) {
                // 成功：提交新增制品（已有的键值不变），随后扫描它们的可选依赖。
                trial.forEach(chosen::putIfAbsent);
                return LockResolution.OptionalResolution.included(
                        ref.sourceName(), ref.dependencyName(), candidate.version());
            }
        }
        LockResolution.SkipReason reason = sawCandidate
                ? LockResolution.SkipReason.CLOSURE_INFEASIBLE
                : LockResolution.SkipReason.NO_COMPATIBLE_CANDIDATE;
        return LockResolution.OptionalResolution.skipped(
                ref.sourceName(), ref.dependencyName(), reason);
    }

    /**
     * 校验候选制品声明的、指向已选名称的必选依赖区间均被已选精确版本满足。
     * 可选依赖不对必选集合构成约束；环依赖在此被校验。
     */
    private static boolean satisfiesMandatoryChosen(ArtifactVersion candidate,
                                                    Map<String, ArtifactVersion> chosen) {
        for (DependencyRange dep : candidate.dependencies()) {
            if (dep.optional()) {
                continue;
            }
            ArtifactVersion selected = chosen.get(dep.name());
            if (selected == null) {
                continue;
            }
            if (selected.version() < dep.minimumVersion() || selected.version() > dep.maximumVersion()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 可选依赖扫描引用，按来源名称、依赖名称字典序排序。
     */
    private record OptionalRef(String sourceName, String dependencyName,
                               int minimumVersion, int maximumVersion)
            implements Comparable<OptionalRef> {

        OptionalRef(String sourceName, String dependencyName) {
            this(sourceName, dependencyName, 0, 0);
        }

        @Override
        public int compareTo(OptionalRef o) {
            int bySource = sourceName.compareTo(o.sourceName);
            return bySource != 0 ? bySource : dependencyName.compareTo(o.dependencyName);
        }
    }
}
