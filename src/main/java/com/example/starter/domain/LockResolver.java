package com.example.starter.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 依赖锁定解析器：在一致性仓库快照上带回溯地求解精确版本组合，支持边级排除。
 *
 * <p>每条到达路径携带继承排除集合（路径上各边声明排除的并集），不同路径相互独立：
 * 边 x→y 被保留，当且仅当存在一条从根到 x 且不经过任何排除 y 的边的到达路径；
 * 任一未排除路径仍要求该名称及其区间约束。解析同一名称仍只选一个版本，
 * 汇合所有保留边的区间；候选按版本降序尝试，取首个完整解；有环或无解返回 null。
 * 可达性按被排除名称分别计算，不使用全局 visited，避免先到的带排除路径
 * 掩盖后来的无排除路径。
 */
public final class LockResolver {

    private LockResolver() {
    }

    /** 求解并仅返回 名称 -> 精确版本（名称升序）；不可行返回 null。 */
    public static Map<String, Integer> resolve(RepositorySnapshot snapshot,
                                               String rootName, int rootVersion) {
        LockSolution solution = solve(snapshot, rootName, rootVersion);
        return solution == null ? null : solution.versions();
    }

    /**
     * 求解完整锁定方案（精确版本、实际保留边、排除处置记录）；不可行返回 null。
     * 根版本必须存在且未撤回，由调用方保证。
     */
    public static LockSolution solve(RepositorySnapshot snapshot,
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
        if (!search(all, chosen, rootName)) {
            return null;
        }
        List<Edge> declared = declaredEdges(chosen);
        List<Edge> retained = retainedEdges(declared, rootName);
        TreeMap<String, Integer> versions = new TreeMap<>();
        chosen.forEach((name, artifact) -> versions.put(name, artifact.version()));
        return new LockSolution(versions, toRetainedEdges(retained),
                exclusionRecords(declared, retained));
    }

    /**
     * 递归回溯：汇合保留边对同一名称的区间约束，已选版本必须落在交集内；
     * 选字典序最小的未解析名称，候选版本从高到低尝试，遇冲突回退。
     */
    private static boolean search(Map<String, List<ArtifactVersion>> all,
                                  TreeMap<String, ArtifactVersion> chosen,
                                  String rootName) {
        TreeMap<String, int[]> merged = new TreeMap<>();
        for (Edge edge : retainedEdges(declaredEdges(chosen), rootName)) {
            merged.compute(edge.toName(), (name, range) -> range == null
                    ? new int[]{edge.minimumVersion(), edge.maximumVersion()}
                    : new int[]{Math.max(range[0], edge.minimumVersion()),
                            Math.min(range[1], edge.maximumVersion())});
        }
        String next = null;
        for (Map.Entry<String, int[]> entry : merged.entrySet()) {
            ArtifactVersion selected = chosen.get(entry.getKey());
            if (selected != null) {
                if (selected.version() < entry.getValue()[0]
                        || selected.version() > entry.getValue()[1]) {
                    return false;
                }
            } else if (next == null) {
                next = entry.getKey();
            }
        }
        if (next == null) {
            return true;
        }
        int[] range = merged.get(next);
        if (range[0] > range[1]) {
            return false;
        }
        // 候选从高到低（快照已按版本号降序），跳过撤回版本和区间外版本。
        for (ArtifactVersion candidate : all.getOrDefault(next, List.of())) {
            if (candidate.withdrawn()
                    || candidate.version() < range[0] || candidate.version() > range[1]) {
                continue;
            }
            chosen.put(next, candidate);
            if (search(all, chosen, rootName)) {
                return true;
            }
            chosen.remove(next);
        }
        return false;
    }

    /** 已选版本声明的全部边。 */
    private static List<Edge> declaredEdges(Map<String, ArtifactVersion> chosen) {
        List<Edge> edges = new ArrayList<>();
        for (ArtifactVersion artifact : chosen.values()) {
            for (DependencyRange dep : artifact.dependencies()) {
                edges.add(new Edge(artifact.name(), artifact.version(), dep.name(),
                        dep.minimumVersion(), dep.maximumVersion(), dep.exclusions()));
            }
        }
        return edges;
    }

    /**
     * 保留边：边 x→y 被保留，当且仅当没有任何边排除 y，
     * 或 x 可由一条不经过排除 y 的边的路径从根到达。
     */
    private static List<Edge> retainedEdges(List<Edge> declared, String rootName) {
        Set<String> excludedNames = new HashSet<>();
        for (Edge edge : declared) {
            excludedNames.addAll(edge.exclusions());
        }
        Map<String, Set<String>> reachableAvoiding = new HashMap<>();
        for (String excluded : excludedNames) {
            reachableAvoiding.put(excluded, reachableAvoiding(declared, rootName, excluded));
        }
        List<Edge> retained = new ArrayList<>();
        for (Edge edge : declared) {
            Set<String> reachable = reachableAvoiding.get(edge.toName());
            if (reachable == null || reachable.contains(edge.fromName())) {
                retained.add(edge);
            }
        }
        return retained;
    }

    /** 从根出发、不经过排除 avoided 的边可达的名称集合（含根本身）。 */
    private static Set<String> reachableAvoiding(List<Edge> edges, String rootName,
                                                 String avoided) {
        Map<String, List<Edge>> bySource = new HashMap<>();
        for (Edge edge : edges) {
            if (!edge.exclusions().contains(avoided)) {
                bySource.computeIfAbsent(edge.fromName(), k -> new ArrayList<>()).add(edge);
            }
        }
        return traverse(bySource, Set.of(rootName));
    }

    /** 从给定起点沿边遍历可达的名称集合（含起点）。 */
    private static Set<String> traverse(Map<String, List<Edge>> bySource, Set<String> starts) {
        Set<String> seen = new HashSet<>(starts);
        Deque<String> queue = new ArrayDeque<>(starts);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (Edge edge : bySource.getOrDefault(current, List.of())) {
                if (seen.add(edge.toName())) {
                    queue.add(edge.toName());
                }
            }
        }
        return seen;
    }

    /** 已声明排除的处置记录：实际忽略了同名边为 APPLIED，否则记录为未命中。 */
    private static List<LockSolution.ExclusionRecord> exclusionRecords(
            List<Edge> declared, List<Edge> retained) {
        Set<Edge> retainedSet = new HashSet<>(retained);
        Map<String, List<Edge>> bySource = new HashMap<>();
        for (Edge edge : declared) {
            bySource.computeIfAbsent(edge.fromName(), k -> new ArrayList<>()).add(edge);
        }
        List<LockSolution.ExclusionRecord> records = new ArrayList<>();
        for (Edge declaring : declared) {
            for (String excluded : new TreeSet<>(declaring.exclusions())) {
                // 经过该边之后可达的整个子图（含该边目标本身）。
                Set<String> subgraph = traverse(bySource, Set.of(declaring.toName()));
                List<String> ignored = new ArrayList<>();
                for (Edge edge : declared) {
                    if (edge.toName().equals(excluded)
                            && subgraph.contains(edge.fromName())
                            && !retainedSet.contains(edge)) {
                        ignored.add(edge.fromName() + "->" + edge.toName());
                    }
                }
                Collections.sort(ignored);
                boolean applied = !ignored.isEmpty();
                records.add(new LockSolution.ExclusionRecord(
                        declaring.fromName(), declaring.fromVersion(), declaring.toName(),
                        excluded, applied ? "APPLIED" : "MISS",
                        applied ? "忽略边: " + String.join(", ", ignored) : "未命中"));
            }
        }
        records.sort(Comparator.comparing(LockSolution.ExclusionRecord::fromName)
                .thenComparing(LockSolution.ExclusionRecord::fromVersion)
                .thenComparing(LockSolution.ExclusionRecord::toName)
                .thenComparing(LockSolution.ExclusionRecord::excludedName));
        return records;
    }

    /** 保留边视图，按 fromName/fromVersion/toName 升序。 */
    private static List<LockSolution.RetainedEdge> toRetainedEdges(List<Edge> retained) {
        return retained.stream()
                .map(e -> new LockSolution.RetainedEdge(e.fromName(), e.fromVersion(),
                        e.toName(), e.minimumVersion(), e.maximumVersion()))
                .sorted(Comparator.comparing(LockSolution.RetainedEdge::fromName)
                        .thenComparing(LockSolution.RetainedEdge::fromVersion)
                        .thenComparing(LockSolution.RetainedEdge::toName))
                .toList();
    }

    /** 已选版本声明的一条依赖边（含其排除名称）。 */
    private record Edge(String fromName, int fromVersion, String toName,
                        int minimumVersion, int maximumVersion, List<String> exclusions) {
    }
}
