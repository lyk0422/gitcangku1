package com.example.starter.domain;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 依赖锁定解析器：在一致性仓库快照上带回溯地求解精确版本组合。
 *
 * <p>两阶段：
 * <ol>
 *   <li>必选阶段：根版本固定；只有支持目标平台（或声明 ANY）的未撤回版本可参与，
 *       其余名称按字典序选择下一个尚未解析的必选依赖，候选版本从高到低尝试，
 *       遇冲突回退，取首个完整可行结果。必选阶段完全忽略可选依赖，
 *       既不因可选依赖降级，也不因可选依赖失败。</li>
 *   <li>可选阶段：必选集合确定后，按“来源名称、依赖名称”字典序遍历当前已选制品的
 *       可选依赖。目标名称已选且版本满足则记 included；未选则在不改变已选集合的前提下
 *       尝试加入其最高可行版本及必选闭包，成功后继续扫描新增制品的可选依赖，
 *       失败记 skipped 并保存稳定原因。可选依赖不能覆盖根或更换此前选择。</li>
 * </ol>
 * 已选制品之间必须互相满足必选区间约束，因此依赖图存在环时也会被正确校验。
 */
public final class LockResolver {

    private LockResolver() {
    }

    /**
     * 旧签名兼容：不做平台过滤（等价于所有制品均支持目标平台），仅返回必选精确集合。
     *
     * @return 名称 -> 精确版本（名称升序）；全部组合不可行时返回 null
     */
    public static Map<String, Integer> resolve(RepositorySnapshot snapshot,
                                               String rootName, int rootVersion) {
        TreeMap<String, ArtifactVersion> chosen = solveMandatory(snapshot, rootName, rootVersion, null);
        if (chosen == null) {
            return null;
        }
        return toVersionMap(chosen);
    }

    /**
     * 按目标平台执行两阶段解析。
     *
     * @param snapshot       一致性仓库快照（每名称版本按版本号降序）
     * @param rootName       根制品名称
     * @param rootVersion    根制品精确版本（必须存在且未撤回，由调用方保证）
     * @param targetPlatform 目标平台 os/arch，仅支持该平台或 ANY 的制品可参与
     * @return 完整解析结果；必选依赖整体无解时返回 null
     * @throws PlatformUnsupportedException 精确根不支持目标平台
     */
    public static LockResolution resolve(RepositorySnapshot snapshot,
                                         String rootName, int rootVersion,
                                         String targetPlatform) {
        TreeMap<String, ArtifactVersion> chosen =
                solveMandatory(snapshot, rootName, rootVersion, targetPlatform);
        if (chosen == null) {
            return null;
        }
        List<OptionalDependencyOutcome> outcomes = resolveOptional(snapshot, chosen, targetPlatform);
        return new LockResolution(snapshot.repositoryVersion(), targetPlatform,
                toVersionMap(chosen), List.copyOf(outcomes));
    }

    /**
     * 必选阶段：固定根并回溯求解全部必选依赖。
     *
     * @param targetPlatform null 表示不做平台过滤（旧签名兼容）
     * @return 已选制品映射；不可行返回 null
     */
    private static TreeMap<String, ArtifactVersion> solveMandatory(
            RepositorySnapshot snapshot, String rootName, int rootVersion, String targetPlatform) {
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

        if (targetPlatform != null && !root.supports(targetPlatform)) {
            throw new PlatformUnsupportedException(
                    "根制品版本不支持目标平台 " + targetPlatform + ": "
                            + rootName + ":" + rootVersion);
        }

        TreeMap<String, ArtifactVersion> chosen = new TreeMap<>();
        chosen.put(rootName, root);
        // 根可能自依赖：固定版本也必须落在自身声明的必选区间内。
        if (!satisfiesChosenMandatory(root, chosen)) {
            return null;
        }
        if (solve(all, chosen, targetPlatform)) {
            return chosen;
        }
        return null;
    }

    /**
     * 可选阶段：按“来源名称、依赖名称”字典序反复扫描当前已选制品的未处理可选依赖，
     * 成功加入的制品会带来新的可选依赖，直至全部评估完毕。
     */
    private static List<OptionalDependencyOutcome> resolveOptional(
            RepositorySnapshot snapshot, TreeMap<String, ArtifactVersion> chosen,
            String targetPlatform) {
        Map<String, List<ArtifactVersion>> all = snapshot.artifacts();
        List<OptionalDependencyOutcome> outcomes = new java.util.ArrayList<>();
        java.util.Set<String> processed = new java.util.HashSet<>();

        while (true) {
            // 每次取当前已选集合中字典序最小的未处理（来源, 依赖）对。
            String nextKey = null;
            ArtifactVersion nextSource = null;
            DependencyRange nextDep = null;
            for (ArtifactVersion source : chosen.values()) {
                for (DependencyRange dep : source.dependencies()) {
                    if (!dep.optional()) {
                        continue;
                    }
                    String key = source.name() + "\u0000" + dep.name();
                    if (processed.contains(key)) {
                        continue;
                    }
                    if (nextKey == null || key.compareTo(nextKey) < 0) {
                        nextKey = key;
                        nextSource = source;
                        nextDep = dep;
                    }
                }
            }
            if (nextKey == null) {
                break;
            }
            processed.add(nextKey);
            outcomes.add(evaluateOptional(all, chosen, nextSource, nextDep, targetPlatform));
        }
        return outcomes;
    }

    /**
     * 评估单条可选依赖：已选则核对区间；未选则尝试高版本优先地加入其必选闭包，
     * 全程不修改已选集合（在副本上尝试，成功才整体提交）。
     */
    private static OptionalDependencyOutcome evaluateOptional(
            Map<String, List<ArtifactVersion>> all, TreeMap<String, ArtifactVersion> chosen,
            ArtifactVersion source, DependencyRange dep, String targetPlatform) {
        ArtifactVersion selected = chosen.get(dep.name());
        if (selected != null) {
            if (selected.version() >= dep.minimumVersion() && selected.version() <= dep.maximumVersion()) {
                return OptionalDependencyOutcome.included(source.name(), dep.name(), selected.version());
            }
            return OptionalDependencyOutcome.skipped(source.name(), dep.name(),
                    "已选版本 " + selected.version() + " 不满足依赖区间 ["
                            + dep.minimumVersion() + "," + dep.maximumVersion() + "]，且可选依赖不得更换已选版本");
        }

        List<ArtifactVersion> candidates = all.getOrDefault(dep.name(), List.of());
        List<Integer> triedVersions = new java.util.ArrayList<>();
        for (ArtifactVersion candidate : candidates) {
            if (candidate.withdrawn() || candidate.version() < dep.minimumVersion()
                    || candidate.version() > dep.maximumVersion()) {
                continue;
            }
            if (targetPlatform != null && !candidate.supports(targetPlatform)) {
                continue;
            }
            triedVersions.add(candidate.version());

            // 在副本上加入候选并求解其必选闭包；已选键固定，绝不变更或移除。
            TreeMap<String, ArtifactVersion> working = new TreeMap<>(chosen);
            if (!consistentWithExistingMandatory(candidate, working, dep.name())) {
                continue;
            }
            working.put(dep.name(), candidate);
            if (solve(all, working, targetPlatform)) {
                // 成功：整体提交新增制品（含必选闭包），其可选依赖随后续扫描处理。
                chosen.clear();
                chosen.putAll(working);
                return OptionalDependencyOutcome.included(source.name(), dep.name(), candidate.version());
            }
        }

        String reason;
        if (triedVersions.isEmpty()) {
            reason = "仓库中不存在支持平台 " + targetPlatform + "、未撤回且落入区间 ["
                    + dep.minimumVersion() + "," + dep.maximumVersion() + "] 的 " + dep.name() + " 版本";
        } else {
            reason = "候选版本 " + triedVersions + " 均无法在保持已选集合不变的前提下完成必选闭包";
        }
        return OptionalDependencyOutcome.skipped(source.name(), dep.name(), reason);
    }

    /**
     * 双向校验候选与既有已选集合之间的必选区间：
     * 候选指向已选名称的依赖须满足；已选制品指向候选名称的必选依赖交集也须满足。
     */
    private static boolean consistentWithExistingMandatory(
            ArtifactVersion candidate, Map<String, ArtifactVersion> existing, String candidateName) {
        if (!satisfiesChosenMandatory(candidate, existing)) {
            return false;
        }
        for (ArtifactVersion chosen : existing.values()) {
            for (DependencyRange dep : chosen.dependencies()) {
                if (dep.optional() || !dep.name().equals(candidateName)) {
                    continue;
                }
                if (candidate.version() < dep.minimumVersion()
                        || candidate.version() > dep.maximumVersion()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 递归回溯：选出字典序最小的未解析必选依赖名称，从高到低尝试候选版本。
     * 仅会新增/回退本层加入的键，调用前已存在的键始终固定。
     */
    private static boolean solve(Map<String, List<ArtifactVersion>> all,
                                 TreeMap<String, ArtifactVersion> chosen, String targetPlatform) {
        // 汇总所有已选制品对“尚未解析名称”的必选区间约束，取交集。
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
            // 所有必选依赖均已解析为精确版本。
            return true;
        }
        if (lo > hi) {
            // 多个已选制品对该名称的区间交集为空。
            return false;
        }

        // 候选从高到低（快照已按版本号降序），跳过撤回、平台不兼容和区间外版本。
        List<ArtifactVersion> candidates = all.getOrDefault(next, List.of());
        for (ArtifactVersion candidate : candidates) {
            if (candidate.withdrawn() || candidate.version() < lo || candidate.version() > hi) {
                continue;
            }
            if (targetPlatform != null && !candidate.supports(targetPlatform)) {
                continue;
            }
            // 新候选对所有已选名称（含自身）的必选区间必须成立；环依赖在此被校验。
            if (!satisfiesChosenMandatory(candidate, chosen)) {
                continue;
            }
            chosen.put(next, candidate);
            if (solve(all, chosen, targetPlatform)) {
                return true;
            }
            chosen.remove(next);
        }
        return false;
    }

    /**
     * 校验候选制品声明的、指向已选名称的必选依赖区间均被已选精确版本满足。
     * 可选依赖在必选阶段不参与约束。
     */
    private static boolean satisfiesChosenMandatory(ArtifactVersion candidate,
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

    private static TreeMap<String, Integer> toVersionMap(Map<String, ArtifactVersion> chosen) {
        TreeMap<String, Integer> result = new TreeMap<>();
        chosen.forEach((name, artifact) -> result.put(name, artifact.version()));
        return result;
    }
}
