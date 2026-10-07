package com.example.starter.domain;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 依赖锁定解析器：在一致性仓库快照上带回溯地求解精确版本组合。
 *
 * <p>规则：根版本固定；其余名称按字典序选择下一个尚未解析的依赖，
 * 候选版本从高到低尝试，遇依赖冲突、环冲突或体积超预算均回退，
 * 取首个满足全部依赖、无环且总体积不超过预算的完整可行结果。
 * 同名制品即使被多个父制品依赖也只计算一次体积，根制品同样计入。
 */
public final class LockResolver {

    private LockResolver() {
    }

    /**
     * 求解锁定组合（不限体积预算，仅校验依赖与环）。
     *
 * @param snapshot    一致性仓库快照（每名称版本按版本号降序）
 * @param rootName    根制品名称
 * @param rootVersion 根制品精确版本（必须存在且未撤回，由调用方保证）
 * @return 名称 -> 精确版本（名称升序）；全部组合不可行时返回 null
 */
    public static Map<String, Integer> resolve(RepositorySnapshot snapshot,
                                               String rootName, int rootVersion) {
        LockSolution solution = resolveWithBudget(snapshot, rootName, rootVersion, null);
        return solution == null ? null : solution.versions();
    }

    /**
     * 求解锁定组合并校验总体积预算。
     *
     * @param budgetBytes 体积预算（字节，非负）；null 表示不限预算
     * @return 选中版本与精确总字节数；无满足依赖/无环/预算的完整组合时返回 null
     */
    public static LockSolution resolveWithBudget(RepositorySnapshot snapshot,
                                                 String rootName, int rootVersion,
                                                 Long budgetBytes) {
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

        TreeMap<String, ArtifactVersion> chosen = new TreeMap<>();
        chosen.put(rootName, root);
        // 根可能自依赖：固定版本也必须落在自身声明的区间内。
        if (!satisfiesChosen(root, chosen)) {
            return null;
        }
        // 根自身大小先计入预算（根也计入；根只出现一次）。
        if (budgetBytes != null && root.sizeBytes() > budgetBytes) {
            return null;
        }
        if (solve(all, chosen, root.sizeBytes(), budgetBytes)) {
            TreeMap<String, Integer> versions = new TreeMap<>();
            long[] total = {0L};
            chosen.forEach((name, artifact) -> {
                versions.put(name, artifact.version());
                total[0] += artifact.sizeBytes();
            });
            return new LockSolution(Map.copyOf(versions), total[0]);
        }
        return null;
    }

    /**
     * 递归回溯：选出字典序最小的未解析依赖名称，从高到低尝试候选版本。
     *
     * @param accumulatedSize 已选制品去重后的累计字节数；budget 为 null 时不使用
     * @param budget          体积预算，null 表示不限
     */
    private static boolean solve(Map<String, List<ArtifactVersion>> all,
                                 TreeMap<String, ArtifactVersion> chosen,
                                 long accumulatedSize, Long budget) {
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
            // 体积预算：精确求和溢出 long 时视为预算不足，禁止溢出变负后误判可行。
            if (budget != null && !fitsBudget(accumulatedSize, candidate.sizeBytes(), budget)) {
                continue;
            }
            chosen.put(next, candidate);
            long newSize = budget == null ? accumulatedSize : accumulatedSize + candidate.sizeBytes();
            if (solve(all, chosen, newSize, budget)) {
                return true;
            }
            chosen.remove(next);
        }
        return false;
    }

    /**
     * 判断累加体积与新制品体积之和是否不超过预算；使用 {@link Math#addExact} 防止溢出。
     */
    private static boolean fitsBudget(long accumulatedSize, long candidateSize, long budget) {
        try {
            return Math.addExact(accumulatedSize, candidateSize) <= budget;
        } catch (ArithmeticException overflow) {
            return false;
        }
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
