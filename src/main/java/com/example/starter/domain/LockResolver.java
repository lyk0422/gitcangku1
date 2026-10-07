package com.example.starter.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 依赖锁定解析器：在一致性仓库快照上带回溯地求解精确版本组合。
 *
 * <p>规则：根版本固定；其余名称按字典序选择下一个尚未解析的依赖，
 * 候选版本从高到低尝试，遇冲突回退，取首个完整可行结果。
 * 已选制品之间必须互相满足区间约束，因此依赖图存在环时也会被正确校验。
 *
 * <p>协调组约束：锁图包含组中至少 2 名成员时，已选成员版本必须同时落在
 * 同一条允许元组上（即为该元组的投影）；不满足时剪枝回退原候选版本，
 * 而非直接失败。组约束只过滤候选，不会把组内未达成员引入锁图。
 */
public final class LockResolver {

    private LockResolver() {
    }

    /**
     * 求解锁定组合（不含命中依据，兼容旧调用方）。
     *
     * @param snapshot    一致性仓库快照（每名称版本按版本号降序）
     * @param rootName    根制品名称
     * @param rootVersion 根制品精确版本（必须存在且未撤回，由调用方保证）
     * @return 名称 -> 精确版本（名称升序）；全部组合不可行时返回 null
     */
    public static Map<String, Integer> resolve(RepositorySnapshot snapshot,
                                               String rootName, int rootVersion) {
        LockSolution solution = resolveSolution(snapshot, rootName, rootVersion);
        return solution == null ? null : solution.versions();
    }

    /**
     * 求解锁定组合并给出协调组命中依据。
     *
     * @return 求解结果；全部组合不可行时返回 null
     */
    public static LockSolution resolveSolution(RepositorySnapshot snapshot,
                                               String rootName, int rootVersion) {
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

        List<CoordinationGroup> groups = snapshot.coordinationGroups();
        TreeMap<String, ArtifactVersion> chosen = new TreeMap<>();
        chosen.put(rootName, root);
        // 根可能自依赖：固定版本也必须落在自身声明的区间内。
        if (!satisfiesChosen(root, chosen) || !satisfiesGroups(groups, chosen)) {
            return null;
        }
        if (solve(all, groups, chosen)) {
            TreeMap<String, Integer> result = new TreeMap<>();
            chosen.forEach((name, artifact) -> result.put(name, artifact.version()));
            return new LockSolution(result, collectHits(groups, chosen));
        }
        return null;
    }

    /**
     * 递归回溯：选出字典序最小的未解析依赖名称，从高到低尝试候选版本。
     */
    private static boolean solve(Map<String, List<ArtifactVersion>> all,
                                 List<CoordinationGroup> groups,
                                 TreeMap<String, ArtifactVersion> chosen) {
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
            chosen.put(next, candidate);
            // 协调组投影约束不满足时回退该候选，继续尝试其余版本。
            if (satisfiesGroups(groups, chosen) && solve(all, groups, chosen)) {
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

    /**
     * 协调组投影约束：对每个已选成员数达到 2 的组，必须存在一条允许元组，
     * 使所有已选成员的版本都与该元组一致（部分解阶段同样适用，用于剪枝）。
     */
    private static boolean satisfiesGroups(List<CoordinationGroup> groups,
                                           Map<String, ArtifactVersion> chosen) {
        for (CoordinationGroup group : groups) {
            if (countChosenMembers(group, chosen) < 2) {
                continue;
            }
            boolean anyMatch = false;
            for (Map<String, Integer> tuple : group.tuples()) {
                if (matchesProjection(group, tuple, chosen)) {
                    anyMatch = true;
                    break;
                }
            }
            if (!anyMatch) {
                return false;
            }
        }
        return true;
    }

    /** 已选名称中属于该组成员的数量。 */
    private static int countChosenMembers(CoordinationGroup group,
                                          Map<String, ArtifactVersion> chosen) {
        int count = 0;
        for (String member : group.members()) {
            if (chosen.containsKey(member)) {
                count++;
            }
        }
        return count;
    }

    /** 已选成员版本是否为该元组的投影（未选成员不参与判定）。 */
    private static boolean matchesProjection(CoordinationGroup group, Map<String, Integer> tuple,
                                             Map<String, ArtifactVersion> chosen) {
        for (String member : group.members()) {
            ArtifactVersion selected = chosen.get(member);
            if (selected == null) {
                continue;
            }
            Integer allowed = tuple.get(member);
            if (allowed == null || allowed != selected.version()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 汇总完整解的命中依据：每个被触发组（已选成员数 ≥ 2）记录其命中的允许元组。
     * 完整解必满足约束，因此每组必存在命中元组。
     */
    private static List<LockSolution.CoordinationHit> collectHits(
            List<CoordinationGroup> groups, Map<String, ArtifactVersion> chosen) {
        List<LockSolution.CoordinationHit> hits = new ArrayList<>();
        for (CoordinationGroup group : groups) {
            if (countChosenMembers(group, chosen) < 2) {
                continue;
            }
            for (Map<String, Integer> tuple : group.tuples()) {
                if (matchesProjection(group, tuple, chosen)) {
                    hits.add(new LockSolution.CoordinationHit(
                            group.groupId(), new TreeMap<>(tuple)));
                    break;
                }
            }
        }
        return List.copyOf(hits);
    }
}
