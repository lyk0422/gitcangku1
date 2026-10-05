package com.example.starter.domain;

import com.example.starter.domain.LockResolution.ExclusionRecord;
import com.example.starter.domain.LockResolution.KeptEdge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 依赖锁定解析器：在一致性仓库快照上带回溯地求解精确版本组合，支持边级路径排除。
 *
 * <p>路径排除语义：每条依赖边可声明排除名称，含义是经过该边之后的整个子图中，
 * 同名边被忽略；排除不能移除该边目标本身。每条到达路径携带继承排除集合，
 * 不同路径相互独立，任一未排除路径仍要求该名称及其区间约束。
 *
 * <p>实现要点：对当前已选集合计算“必排除集合”（所有路径排除集的交集）的最大不动点，
 * 避免用全局 visited 让先到的带排除路径掩盖后到的无排除路径；同一名称只选一个版本，
 * 汇合所有未排除区间取交集；候选按版本降序尝试，取首个完整解；环与无解返回 null。
 */
public final class LockResolver {

    private LockResolver() {
    }

    /**
     * 求解锁定组合，仅返回版本视图。
     *
     * @return 名称 -> 精确版本（名称升序）；不可行时返回 null
     */
    public static Map<String, Integer> resolve(RepositorySnapshot snapshot,
                                               String rootName, int rootVersion) {
        LockResolution resolution = resolveDetailed(snapshot, rootName, rootVersion);
        return resolution == null ? null : resolution.versions();
    }

    /**
     * 求解锁定组合，返回精确版本、实际保留边与路径排除命中记录。
     *
     * @param snapshot    一致性仓库快照（每名称版本按版本号降序）
     * @param rootName    根制品名称
     * @param rootVersion 根制品精确版本（必须存在且未撤回，由调用方保证）
     * @return 完整解析结果；全部组合不可行时返回 null
     */
    public static LockResolution resolveDetailed(RepositorySnapshot snapshot,
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
        // 根可能自依赖：固定版本必须落在自身生效边的区间内。
        if (!consistent(all, chosen, rootName)) {
            return null;
        }
        if (!solve(all, chosen, rootName)) {
            return null;
        }
        return buildResolution(all, chosen, rootName);
    }

    /**
     * 递归回溯：汇总已选集合的生效边，取字典序最小的未解析名称，
     * 候选版本从高到低尝试，加入后整体一致性校验失败则回退。
     */
    private static boolean solve(Map<String, List<ArtifactVersion>> all,
                                 TreeMap<String, ArtifactVersion> chosen, String rootName) {
        Map<String, Set<String>> excluded = computeExcluded(all, chosen, rootName);
        String next = null;
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (ArtifactVersion artifact : chosen.values()) {
            Set<String> excludedAt = excluded.get(artifact.name());
            for (DependencyRange dep : artifact.dependencies()) {
                if (chosen.containsKey(dep.name()) || excludedAt.contains(dep.name())) {
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
            chosen.put(next, candidate);
            if (consistent(all, chosen, rootName) && solve(all, chosen, rootName)) {
                return true;
            }
            chosen.remove(next);
        }
        return false;
    }

    /**
     * 校验当前已选集合内部所有生效边（目标未在必排除集合中的边）的区间
     * 均被对端已选精确版本满足；环依赖在此一并校验。
     */
    private static boolean consistent(Map<String, List<ArtifactVersion>> all,
                                      TreeMap<String, ArtifactVersion> chosen, String rootName) {
        Map<String, Set<String>> excluded = computeExcluded(all, chosen, rootName);
        for (ArtifactVersion artifact : chosen.values()) {
            Set<String> excludedAt = excluded.get(artifact.name());
            for (DependencyRange dep : artifact.dependencies()) {
                ArtifactVersion target = chosen.get(dep.name());
                if (target == null || excludedAt.contains(dep.name())) {
                    continue;
                }
                if (target.version() < dep.minimumVersion() || target.version() > dep.maximumVersion()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 计算每个已选节点的“必排除集合”：所有到达路径排除集合的交集。
     * 边的排除集合 = 路径继承集合 ∪ 该边声明的排除；边仅在其目标不在源节点
     * 必排除集合中时生效。从全集出发迭代至最大不动点（集合只缩不增，必然终止）。
     */
    private static Map<String, Set<String>> computeExcluded(Map<String, List<ArtifactVersion>> all,
                                                            TreeMap<String, ArtifactVersion> chosen,
                                                            String rootName) {
        Set<String> universe = new HashSet<>(all.keySet());
        for (List<ArtifactVersion> versions : all.values()) {
            for (ArtifactVersion artifact : versions) {
                for (DependencyRange dep : artifact.dependencies()) {
                    universe.add(dep.name());
                    universe.addAll(dep.exclusions());
                }
            }
        }
        Map<String, Set<String>> excluded = new HashMap<>();
        for (String name : chosen.keySet()) {
            excluded.put(name, new HashSet<>(universe));
        }
        excluded.get(rootName).clear();

        boolean changed = true;
        while (changed) {
            changed = false;
            for (ArtifactVersion artifact : chosen.values()) {
                Set<String> excludedAt = excluded.get(artifact.name());
                for (DependencyRange dep : artifact.dependencies()) {
                    Set<String> target = excluded.get(dep.name());
                    if (target == null || excludedAt.contains(dep.name())) {
                        continue;
                    }
                    Set<String> contribution = new HashSet<>(excludedAt);
                    contribution.addAll(dep.exclusions());
                    if (target.retainAll(contribution)) {
                        changed = true;
                    }
                }
            }
        }
        return excluded;
    }

    /**
     * 基于最终解组装结果：版本表、全部生效边、生效边上声明排除的命中记录。
     */
    private static LockResolution buildResolution(Map<String, List<ArtifactVersion>> all,
                                                  TreeMap<String, ArtifactVersion> chosen,
                                                  String rootName) {
        Map<String, Set<String>> excluded = computeExcluded(all, chosen, rootName);
        TreeMap<String, Integer> versions = new TreeMap<>();
        chosen.forEach((name, artifact) -> versions.put(name, artifact.version()));

        List<KeptEdge> edges = new ArrayList<>();
        List<ExclusionRecord> exclusions = new ArrayList<>();
        for (ArtifactVersion artifact : chosen.values()) {
            Set<String> excludedAt = excluded.get(artifact.name());
            for (DependencyRange dep : artifact.dependencies()) {
                ArtifactVersion target = chosen.get(dep.name());
                if (target == null || excludedAt.contains(dep.name())) {
                    continue;
                }
                edges.add(new KeptEdge(artifact.name(), artifact.version(),
                        dep.name(), target.version()));
                for (String excludedName : dep.exclusions()) {
                    boolean hit = declaresEdgeNamed(
                            reachableFrom(all, chosen, excluded, dep.name()), chosen, excludedName);
                    exclusions.add(new ExclusionRecord(artifact.name(), artifact.version(),
                            dep.name(), excludedName, hit));
                }
            }
        }
        edges.sort(Comparator.comparing(KeptEdge::fromName)
                .thenComparing(KeptEdge::fromVersion)
                .thenComparing(KeptEdge::toName));
        exclusions.sort(Comparator.comparing(ExclusionRecord::fromName)
                .thenComparing(ExclusionRecord::toName)
                .thenComparing(ExclusionRecord::excludedName));
        return new LockResolution(versions, List.copyOf(edges), List.copyOf(exclusions));
    }

    /** 从指定节点出发、沿生效边可达的已选节点名称集合（含起点）。 */
    private static Set<String> reachableFrom(Map<String, List<ArtifactVersion>> all,
                                             TreeMap<String, ArtifactVersion> chosen,
                                             Map<String, Set<String>> excluded, String start) {
        Set<String> reached = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            String name = stack.pop();
            if (!reached.add(name)) {
                continue;
            }
            ArtifactVersion artifact = chosen.get(name);
            if (artifact == null) {
                continue;
            }
            Set<String> excludedAt = excluded.get(name);
            for (DependencyRange dep : artifact.dependencies()) {
                if (chosen.containsKey(dep.name()) && !excludedAt.contains(dep.name())) {
                    stack.push(dep.name());
                }
            }
        }
        return reached;
    }

    /** 可达集合中是否存在声明了指定名称边的节点。 */
    private static boolean declaresEdgeNamed(Set<String> reachable,
                                             TreeMap<String, ArtifactVersion> chosen,
                                             String edgeName) {
        for (String name : reachable) {
            ArtifactVersion artifact = chosen.get(name);
            if (artifact == null) {
                continue;
            }
            for (DependencyRange dep : artifact.dependencies()) {
                if (dep.name().equals(edgeName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
