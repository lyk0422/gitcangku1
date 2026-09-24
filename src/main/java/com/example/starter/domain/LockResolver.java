package com.example.starter.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
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

    /** 阻塞原因：依赖区间内的版本在仓库中缺失。 */
    public static final String REASON_MISSING_VERSION = "MISSING_VERSION";
    /** 阻塞原因：依赖区间内的版本已撤回。 */
    public static final String REASON_VERSION_WITHDRAWN = "VERSION_WITHDRAWN";
    /** 阻塞原因：多个已选制品对该名称的区间交集为空。 */
    public static final String REASON_RANGE_UNSATISFIABLE = "RANGE_UNSATISFIABLE";
    /** 阻塞原因：区间内有候选但均因约束冲突或深层失败而不可行。 */
    public static final String REASON_NO_FEASIBLE_CANDIDATE = "NO_FEASIBLE_CANDIDATE";

    /**
     * 不可行阻塞项：导致解析失败的名称及其区间与缺失/已撤回版本。
     *
     * @param versions 缺失或已撤回的版本（升序）；区间过大无法枚举时为空列表
     */
    public record Blocker(String name, String reason,
                          Integer minimumVersion, Integer maximumVersion,
                          List<Integer> versions) {
    }

    /**
     * 解析结果：可行时 solution 非空（名称升序）；不可行时 solution 为 null 且 blockers 非空。
     */
    public record Resolution(Map<String, Integer> solution, List<Blocker> blockers) {

        /** 是否存在可行解。 */
        public boolean feasible() {
            return solution != null;
        }
    }

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
        return resolveDetailed(snapshot, rootName, rootVersion).solution();
    }

    /**
     * 求解并携带不可行说明（与 {@link #resolve} 同一回溯算法）。
     *
     * @return 可行时 solution 与 {@link #resolve} 完全一致；不可行时 blockers 按名称、原因排序
     */
    public static Resolution resolveDetailed(RepositorySnapshot snapshot,
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

        List<Blocker> blockers = new ArrayList<>();
        TreeMap<String, ArtifactVersion> chosen = new TreeMap<>();
        chosen.put(rootName, root);
        // 根可能自依赖：固定版本也必须落在自身声明的区间内。
        for (DependencyRange dep : root.dependencies()) {
            if (dep.name().equals(rootName)
                    && (rootVersion < dep.minimumVersion() || rootVersion > dep.maximumVersion())) {
                blockers.add(new Blocker(rootName, REASON_RANGE_UNSATISFIABLE,
                        dep.minimumVersion(), dep.maximumVersion(), List.of(rootVersion)));
                return new Resolution(null, List.copyOf(blockers));
            }
        }
        if (solve(all, chosen, blockers)) {
            TreeMap<String, Integer> result = new TreeMap<>();
            chosen.forEach((name, artifact) -> result.put(name, artifact.version()));
            return new Resolution(result, List.of());
        }
        return new Resolution(null, dedupAndSort(blockers));
    }

    /** 阻塞项去重（记录全字段相等）并按名称、原因稳定排序。 */
    private static List<Blocker> dedupAndSort(List<Blocker> blockers) {
        List<Blocker> unique = new ArrayList<>(new LinkedHashSet<>(blockers));
        unique.sort(Comparator.comparing(Blocker::name).thenComparing(Blocker::reason));
        return List.copyOf(unique);
    }

    /**
     * 递归回溯：选出字典序最小的未解析依赖名称，从高到低尝试候选版本。
     * blockers 为 null 时只判可行；否则在失败点记录阻塞明细。
     */
    private static boolean solve(Map<String, List<ArtifactVersion>> all,
                                 TreeMap<String, ArtifactVersion> chosen,
                                 List<Blocker> blockers) {
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
            if (blockers != null) {
                blockers.add(new Blocker(next, REASON_RANGE_UNSATISFIABLE, lo, hi, List.of()));
            }
            return false;
        }

        // 候选从高到低（快照已按版本号降序），跳过撤回版本和区间外版本。
        List<ArtifactVersion> candidates = all.getOrDefault(next, List.of());
        int blockersBefore = blockers == null ? 0 : blockers.size();
        for (ArtifactVersion candidate : candidates) {
            if (candidate.withdrawn() || candidate.version() < lo || candidate.version() > hi) {
                continue;
            }
            // 新候选对所有已选名称（含自身）的区间必须成立；环依赖在此被校验。
            if (!satisfiesChosen(candidate, chosen)) {
                continue;
            }
            chosen.put(next, candidate);
            if (solve(all, chosen, blockers)) {
                return true;
            }
            chosen.remove(next);
        }
        if (blockers != null) {
            boolean deeperBlockersAdded = blockers.size() > blockersBefore;
            recordCandidateBlockers(next, lo, hi, candidates, deeperBlockersAdded, blockers);
        }
        return false;
    }

    /** 区间内版本枚举上限：区间过大时不逐一枚举缺失版本，避免报告膨胀。 */
    private static final int MAX_ENUMERATED_RANGE = 50;

    /**
     * 候选全部耗尽后，为该名称记录缺失/已撤回/无可行候选的阻塞明细。
     * 深层失败已记录阻塞时，不再为上层名称追加 NO_FEASIBLE_CANDIDATE，避免噪声。
     */
    private static void recordCandidateBlockers(String name, int lo, int hi,
                                                List<ArtifactVersion> candidates,
                                                boolean deeperBlockersAdded,
                                                List<Blocker> blockers) {
        List<Integer> existing = candidates.stream()
                .map(ArtifactVersion::version)
                .filter(v -> v >= lo && v <= hi)
                .sorted()
                .toList();
        List<Integer> withdrawn = candidates.stream()
                .filter(ArtifactVersion::withdrawn)
                .map(ArtifactVersion::version)
                .filter(v -> v >= lo && v <= hi)
                .sorted()
                .toList();

        boolean missingRecorded = false;
        if ((long) hi - lo <= MAX_ENUMERATED_RANGE) {
            List<Integer> missing = new ArrayList<>();
            for (int v = lo; v <= hi; v++) {
                if (!existing.contains(v)) {
                    missing.add(v);
                }
            }
            if (!missing.isEmpty()) {
                blockers.add(new Blocker(name, REASON_MISSING_VERSION, lo, hi,
                        List.copyOf(missing)));
                missingRecorded = true;
            }
        } else if (existing.isEmpty()) {
            // 区间过大且无任何已登记版本：整体视为缺失，不逐一枚举。
            blockers.add(new Blocker(name, REASON_MISSING_VERSION, lo, hi, List.of()));
            missingRecorded = true;
        }

        if (!withdrawn.isEmpty()) {
            blockers.add(new Blocker(name, REASON_VERSION_WITHDRAWN, lo, hi, withdrawn));
        }
        if (!missingRecorded && withdrawn.isEmpty() && !existing.isEmpty() && !deeperBlockersAdded) {
            // 有未撤回候选，但均因与已选制品冲突而不可行（如环依赖区间冲突）。
            blockers.add(new Blocker(name, REASON_NO_FEASIBLE_CANDIDATE, lo, hi, existing));
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
