package com.example.starter.domain;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 依赖锁定解析器：在一致性仓库快照上带回溯地求解精确版本组合。
 *
 * <p>规则：根版本固定；其余名称按字典序选择下一个尚未解析的依赖，
 * 候选版本从高到低尝试，遇冲突或体积超预算回退，取首个完整可行结果。
 * 已选制品之间必须互相满足区间约束，因此依赖图存在环时也会被正确校验。
 *
 * <p>体积预算：根计入总和；同名选中制品只计一次（每个名称仅解析一次，
 * 多个父依赖同一名称时天然不重复计）。累加采用 {@code size > budget - total}
 * 判定，不变式 total <= budget <= Long.MAX_VALUE，精确总和超过 long
 * 最大值时必然先超预算，不会溢出变负。
 */
public final class LockResolver {

    private LockResolver() {
    }

    /**
     * 不限预算地求解锁定组合（等价于预算为 Long.MAX_VALUE）。
     *
     * @param snapshot    一致性仓库快照（每名称版本按版本号降序）
     * @param rootName    根制品名称
     * @param rootVersion 根制品精确版本（必须存在且未撤回，由调用方保证）
     * @return 名称 -> 精确版本（名称升序）；全部组合不可行时返回 null
     */
    public static Map<String, Integer> resolve(RepositorySnapshot snapshot,
                                               String rootName, int rootVersion) {
        return resolve(snapshot, rootName, rootVersion, Long.MAX_VALUE);
    }

    /**
     * 在体积预算内求解锁定组合。
     *
     * @param snapshot    一致性仓库快照（每名称版本按版本号降序）
     * @param rootName    根制品名称
     * @param rootVersion 根制品精确版本（必须存在且未撤回，由调用方保证）
     * @param budgetBytes 体积预算，单位字节，非负；选中制品（含根）体积总和不得超过
     * @return 名称 -> 精确版本（名称升序）；满足依赖与预算的组合不存在时返回 null
     */
    public static Map<String, Integer> resolve(RepositorySnapshot snapshot,
                                               String rootName, int rootVersion,
                                               long budgetBytes) {
        if (budgetBytes < 0) {
            throw new IllegalArgumentException("预算不能为负: " + budgetBytes);
        }
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

        // 根本身超预算时无可行组合（根版本固定，无候选可回退）。
        if (root.sizeBytes() > budgetBytes) {
            return null;
        }
        TreeMap<String, ArtifactVersion> chosen = new TreeMap<>();
        chosen.put(rootName, root);
        // 根可能自依赖：固定版本也必须落在自身声明的区间内。
        if (!satisfiesChosen(root, chosen)) {
            return null;
        }
        if (solve(all, chosen, root.sizeBytes(), budgetBytes)) {
            TreeMap<String, Integer> result = new TreeMap<>();
            chosen.forEach((name, artifact) -> result.put(name, artifact.version()));
            return result;
        }
        return null;
    }

    /**
     * 递归回溯：选出字典序最小的未解析依赖名称，从高到低尝试候选版本。
     * total 为已选制品体积总和，不变式 total <= budget。
     */
    private static boolean solve(Map<String, List<ArtifactVersion>> all,
                                 TreeMap<String, ArtifactVersion> chosen,
                                 long total, long budget) {
        // 汇总所有已选制品对“尚未解析名称”的区间约束，取交集。
        String next = null;
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (ArtifactVersion artifact : chosen.values()) {
            for (DependencyRange dep : artifact.dependencies()) {
                if (chosen.containsKey(dep.name())) {
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
            // 所有依赖均已解析为精确版本。
            return true;
        }
        if (lo > hi) {
            // 多个已选制品对该名称的区间交集为空。
            return false;
        }

        // 候选从高到低（快照已按版本号降序），跳过撤回版本和区间外版本。
        List<ArtifactVersion> candidates = all.getOrDefault(next, List.of());
        for (ArtifactVersion candidate : candidates) {
            if (candidate.withdrawn() || candidate.version() < lo || candidate.version() > hi) {
                continue;
            }
            // 新候选对所有已选名称（含自身）的区间必须成立；环依赖在此被校验。
            if (!satisfiesChosen(candidate, chosen)) {
                continue;
            }
            // 预算剪枝：等价于 total + size > budget，但不做可能溢出的加法。
            if (candidate.sizeBytes() > budget - total) {
                continue;
            }
            chosen.put(next, candidate);
            if (solve(all, chosen, total + candidate.sizeBytes(), budget)) {
                return true;
            }
            chosen.remove(next);
        }
        return false;
    }

    /**
     * 校验候选制品声明的、指向已选名称的依赖区间均被已选精确版本满足。
     */
    private static boolean satisfiesChosen(ArtifactVersion candidate,
                                           Map<String, ArtifactVersion> chosen) {
        for (DependencyRange dep : candidate.dependencies()) {
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
}
