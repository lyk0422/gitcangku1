package com.example.starter.domain;

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
 * <p>协调组约束：锁图中包含某组至少 2 名成员时，已选成员版本必须能投影到
 * 该组的同一允许元组，否则回退原候选版本继续尝试；约束只裁剪候选，
 * 不会把组内其余不可达成员拉入锁图。
 */
public final class LockResolver {

    private LockResolver() {
    }

    /**
     * 求解锁定组合。
     *
     * @param snapshot    一致性仓库快照（每名称版本按版本号降序）
     * @param rootName    根制品名称
     * @param rootVersion 根制品精确版本（必须存在且未撤回，由调用方保证）
     * @return 名称 -> 精确版本（名称升序）；全部组合不可行时返回 null
     */
    public static Map<String, Integer> resolve(RepositorySnapshot snapshot,
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

        TreeMap<String, ArtifactVersion> chosen = new TreeMap<>();
        chosen.put(rootName, root);
        // 根可能自依赖：固定版本也必须落在自身声明的区间内。
        if (!satisfiesChosen(root, chosen)) {
            return null;
        }
        if (solve(all, snapshot.coordinationGroups(), chosen)) {
            TreeMap<String, Integer> result = new TreeMap<>();
            chosen.forEach((name, artifact) -> result.put(name, artifact.version()));
            return result;
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
            // 协调组投影不满足时回退该候选，继续尝试其余版本而非直接失败。
            if (coordinationFeasible(groups, chosen) && solve(all, groups, chosen)) {
                return true;
            }
            chosen.remove(next);
        }
        return false;
    }

    /**
     * 协调组可行性：任一组内已选成员达到 2 名时，已选版本必须能投影到同一允许元组。
     */
    private static boolean coordinationFeasible(List<CoordinationGroup> groups,
                                                Map<String, ArtifactVersion> chosen) {
        for (CoordinationGroup group : groups) {
            Map<String, Integer> chosenInGroup = new TreeMap<>();
            for (String member : group.members()) {
                ArtifactVersion selected = chosen.get(member);
                if (selected != null) {
                    chosenInGroup.put(member, selected.version());
                }
            }
            if (chosenInGroup.size() >= 2 && matchedTuple(group, chosenInGroup) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * 在组的允许元组中查找与给定成员版本投影一致的元组，无匹配返回 null。
     *
     * @param group          协调组
     * @param memberVersions 成员名 -> 已选版本（只需覆盖部分成员，按投影匹配）
     * @return 首个匹配元组（成员名 -> 版本，覆盖全部成员）
     */
    public static Map<String, Integer> matchedTuple(CoordinationGroup group,
                                                    Map<String, Integer> memberVersions) {
        for (Map<String, Integer> tuple : group.allowedTuples()) {
            boolean matches = true;
            for (Map.Entry<String, Integer> chosen : memberVersions.entrySet()) {
                Integer allowed = tuple.get(chosen.getKey());
                if (allowed == null || !allowed.equals(chosen.getValue())) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return tuple;
            }
        }
        return null;
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
