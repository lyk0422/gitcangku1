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
     * 递归回溯：选出字典序最小的未解析依赖名称，从高到低尝试候选版本。
     */
    private static boolean solve(Map<String, List<ArtifactVersion>> all,
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
            if (solve(all, chosen)) {
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

    // ------------------------------------------------------------------
    // 带阻塞点诊断的求解：与 resolve 使用完全一致的回溯算法，
    // 仅额外记录确定性遍历下最接近完整解（深度最大）的失败点，供重解析报告使用。
    // ------------------------------------------------------------------

    /**
     * 与 {@link #resolve} 同算法求解；不可行时额外返回导致失败的名称与版本诊断。
     */
    public static ResolutionResult resolveWithDiagnosis(RepositorySnapshot snapshot,
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
        if (!satisfiesChosen(root, chosen)) {
            return ResolutionResult.infeasible(new InfeasibleBlocker(
                    rootName, InfeasibleBlocker.RANGE_INTERSECTION_EMPTY, List.of(rootVersion)));
        }
        Diagnosis diagnosis = new Diagnosis();
        if (solveDiagnosed(all, chosen, diagnosis)) {
            TreeMap<String, Integer> result = new TreeMap<>();
            chosen.forEach((name, artifact) -> result.put(name, artifact.version()));
            return ResolutionResult.feasible(result);
        }
        if (diagnosis.name == null) {
            // 理论上不会到达：任何失败分支都会记录诊断点。
            diagnosis = new Diagnosis(rootName, InfeasibleBlocker.VERSIONS_MISSING, List.of(rootVersion));
        }
        return ResolutionResult.infeasible(new InfeasibleBlocker(
                diagnosis.name, diagnosis.reason, List.copyOf(diagnosis.versions)));
    }

    /**
     * 递归回溯（诊断版）：选择与尝试顺序与 {@link #solve} 完全一致，
     * 同时记录深度最大的无候选/区间冲突失败点。
     */
    private static boolean solveDiagnosed(Map<String, List<ArtifactVersion>> all,
                                          TreeMap<String, ArtifactVersion> chosen,
                                          Diagnosis diagnosis) {
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
            return true;
        }
        int depth = chosen.size();
        if (lo > hi) {
            diagnosis.record(depth, next, InfeasibleBlocker.RANGE_INTERSECTION_EMPTY,
                    sortedBounds(lo, hi));
            return false;
        }
        final int rangeLow = lo;
        final int rangeHigh = hi;

        List<ArtifactVersion> candidates = all.getOrDefault(next, List.of());
        List<Integer> inRangeVersions = candidates.stream()
                .map(ArtifactVersion::version)
                .filter(v -> v >= rangeLow && v <= rangeHigh)
                .sorted()
                .toList();
        boolean anyWithdrawnInRange = candidates.stream()
                .anyMatch(c -> c.withdrawn() && c.version() >= rangeLow && c.version() <= rangeHigh);
        List<Integer> attemptedVersions = new ArrayList<>();
        for (ArtifactVersion candidate : candidates) {
            if (candidate.withdrawn() || candidate.version() < rangeLow
                    || candidate.version() > rangeHigh) {
                continue;
            }
            attemptedVersions.add(candidate.version());
            if (!satisfiesChosen(candidate, chosen)) {
                continue;
            }
            chosen.put(next, candidate);
            if (solveDiagnosed(all, chosen, diagnosis)) {
                return true;
            }
            chosen.remove(next);
        }

        if (attemptedVersions.isEmpty()) {
            if (inRangeVersions.isEmpty()) {
                diagnosis.record(depth, next, InfeasibleBlocker.VERSIONS_MISSING, sortedBounds(rangeLow, rangeHigh));
            } else if (anyWithdrawnInRange) {
                diagnosis.record(depth, next, InfeasibleBlocker.VERSIONS_WITHDRAWN, inRangeVersions);
            } else {
                diagnosis.record(depth, next, InfeasibleBlocker.RANGE_INTERSECTION_EMPTY,
                        inRangeVersions);
            }
        } else {
            // 区间内候选均尝试过且全部失败：更深层失败点优先，本节点作为同深度兜底。
            diagnosis.record(depth, next, InfeasibleBlocker.RANGE_INTERSECTION_EMPTY,
                    List.copyOf(attemptedVersions));
        }
        return false;
    }

    /** 区间端点升序去重，用于缺失/交集冲突时指出具体版本。 */
    private static List<Integer> sortedBounds(int lo, int hi) {
        if (lo == hi) {
            return List.of(lo);
        }
        return lo < hi ? List.of(lo, hi) : List.of(hi, lo);
    }

    /** 回溯过程中的最深入失败点（确定性，与遍历顺序绑定）。 */
    private static final class Diagnosis {
        private String name;
        private String reason;
        private List<Integer> versions = List.of();
        private int depth = -1;

        Diagnosis() {
        }

        Diagnosis(String name, String reason, List<Integer> versions) {
            this.name = name;
            this.reason = reason;
            this.versions = versions;
        }

        private void record(int depth, String name, String reason, List<Integer> versions) {
            if (depth > this.depth) {
                this.depth = depth;
                this.name = name;
                this.reason = reason;
                this.versions = versions;
            }
        }
    }
}
