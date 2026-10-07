package com.example.starter.domain;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 依赖锁定解析器：在一致性仓库快照上带回溯地求解精确版本组合。
 *
 * <p>规则：根版本固定；其余名称按字典序选择下一个尚未解析的普通依赖，
 * 候选版本从高到低尝试，遇冲突回退，取首个完整可行结果。
 * 已选制品之间必须互相满足普通依赖区间，因此依赖图存在环时也会被正确校验。
 *
 * <p>peer 依赖与普通依赖共同求解但语义不同：
 * <ul>
 *   <li>peer 不建立可达边，不会把宿主名称拉入求解集合；</li>
 *   <li>peer 只约束普通依赖图已经可达（即已在候选解中）的同名制品：
 *       宿主缺失或宿主精确版本落在闭区间外时，整个候选组合不可行，
 *       触发对其他候选版本的回退；全部候选不可行时返回 null；</li>
 *   <li>peer 边不构成普通依赖环，环检测只基于普通依赖。</li>
 * </ul>
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
        if (solve(all, chosen)) {
            TreeMap<String, Integer> result = new TreeMap<>();
            chosen.forEach((name, artifact) -> result.put(name, artifact.version()));
            return result;
        }
        return null;
    }

    /**
     * 递归回溯：选出字典序最小的未解析普通依赖名称，从高到低尝试候选版本。
     */
    private static boolean solve(Map<String, List<ArtifactVersion>> all,
                                 TreeMap<String, ArtifactVersion> chosen) {
        // 汇总所有已选制品对“尚未解析名称”的普通依赖区间约束，取交集。
        // peer 不参与：peer 不建立可达边，不能把宿主名称拉入求解集合。
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
            // 普通依赖图已全部解析为精确版本；peer 约束在此统一终局校验。
            return peersSatisfied(chosen);
        }
        if (lo > hi) {
            // 多个已选制品对该名称的普通依赖区间交集为空。
            return false;
        }

        // 候选从高到低（快照已按版本号降序），跳过撤回版本和区间外版本。
        List<ArtifactVersion> candidates = all.getOrDefault(next, List.of());
        for (ArtifactVersion candidate : candidates) {
            if (candidate.withdrawn() || candidate.version() < lo || candidate.version() > hi) {
                continue;
            }
            // 新候选对所有已选名称（含自身）的普通依赖区间必须成立；环依赖在此被校验。
            if (!satisfiesChosen(candidate, chosen)) {
                continue;
            }
            chosen.put(next, candidate);
            if (solve(all, chosen)) {
                return true;
            }
            chosen.remove(next);
        }
        return false;
    }

    /**
     * 校验候选制品声明的、指向已选名称的普通依赖区间均被已选精确版本满足。
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
     * 终局校验全部已选制品的 peer 声明：
     * peer 仅约束普通依赖图已经可达的同名制品——宿主不在候选解中（缺失）
     * 或宿主精确版本不满足闭区间时，该候选组合不可行。
     */
    private static boolean peersSatisfied(Map<String, ArtifactVersion> chosen) {
        for (ArtifactVersion artifact : chosen.values()) {
            for (DependencyRange peer : artifact.peers()) {
                ArtifactVersion host = chosen.get(peer.name());
                if (host == null) {
                    // 宿主未通过普通依赖图可达：peer 不能自行引入宿主。
                    return false;
                }
                if (host.version() < peer.minimumVersion() || host.version() > peer.maximumVersion()) {
                    return false;
                }
            }
        }
        return true;
    }
}
