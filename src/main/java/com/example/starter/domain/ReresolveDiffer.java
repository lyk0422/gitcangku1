package com.example.starter.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 锁文件重解析差异分析：对比原锁定集合与新解析集合，逐名称给出变化类型与原因。
 *
 * <p>变化类型：{@code ADDED} 新增名称、{@code REMOVED} 移除名称、
 * {@code VERSION_CHANGED} 同名版本变化；原因区分原候选被撤回（ORIGINAL_WITHDRAWN）、
 * 被更高版本取代（SUPERSEDED_BY_HIGHER）与依赖区间不再满足（RANGE_NO_LONGER_SATISFIED）。
 * 判定基于当前一致性快照中各制品版本的撤回标记与依赖区间声明。
 */
public final class ReresolveDiffer {

    /** 变化类型。 */
    public static final String ADDED = "ADDED";
    public static final String REMOVED = "REMOVED";
    public static final String VERSION_CHANGED = "VERSION_CHANGED";

    /** 差异原因。 */
    public static final String ORIGINAL_WITHDRAWN = "ORIGINAL_WITHDRAWN";
    public static final String SUPERSEDED_BY_HIGHER = "SUPERSEDED_BY_HIGHER";
    public static final String RANGE_NO_LONGER_SATISFIED = "RANGE_NO_LONGER_SATISFIED";

    private ReresolveDiffer() {
    }

    /**
     * 单条差异（名称升序由调用结果顺序保证）。
     */
    public record Diff(String name, String changeType, Integer originalVersion,
                       Integer newVersion, String reason, String detail) {
    }

    /**
     * 计算逐名称差异。
     *
     * @param original 原锁定集合（名称 -> 版本）
     * @param resolved 新解析集合（名称 -> 版本）
     * @param snapshot 重解析读取的一致性仓库快照
     * @return 差异列表，按名称升序；两个集合完全相同时返回空列表
     */
    public static List<Diff> diff(Map<String, Integer> original,
                                  Map<String, Integer> resolved,
                                  RepositorySnapshot snapshot) {
        TreeMap<String, Integer> allNames = new TreeMap<>(original);
        resolved.forEach(allNames::putIfAbsent);
        List<Diff> diffs = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : allNames.entrySet()) {
            String name = entry.getKey();
            Integer oldVersion = original.get(name);
            Integer newVersion = resolved.get(name);
            if (oldVersion != null && newVersion != null) {
                if (!oldVersion.equals(newVersion)) {
                    diffs.add(versionChanged(name, oldVersion, newVersion, resolved, snapshot));
                }
            } else if (oldVersion == null) {
                diffs.add(new Diff(name, ADDED, null, newVersion, SUPERSEDED_BY_HIGHER,
                        "新解析集合引入了原锁文件中不存在的名称 " + name + ":" + newVersion
                                + "，由当前依赖闭包新增的区间要求带入"));
            } else {
                diffs.add(removed(name, oldVersion, resolved, snapshot));
            }
        }
        return List.copyOf(diffs);
    }

    /**
     * 同名版本变化原因：原版本已撤回优先；新版本更高且原版本仍满足当前全部区间则为被更高版本
     * 取代；否则（新版本更低，或原版本已不满足某已选制品的当前区间声明）为区间不再满足。
     */
    private static Diff versionChanged(String name, int oldVersion, int newVersion,
                                       Map<String, Integer> resolved,
                                       RepositorySnapshot snapshot) {
        if (isWithdrawn(snapshot, name, oldVersion)) {
            return new Diff(name, VERSION_CHANGED, oldVersion, newVersion, ORIGINAL_WITHDRAWN,
                    "原锁定版本 " + name + ":" + oldVersion + " 已撤回，回溯解析改选 "
                            + name + ":" + newVersion);
        }
        if (newVersion > oldVersion && stillSatisfiesAll(snapshot, name, oldVersion, resolved)) {
            return new Diff(name, VERSION_CHANGED, oldVersion, newVersion, SUPERSEDED_BY_HIGHER,
                    "原锁定版本 " + name + ":" + oldVersion + " 仍可用，按候选从高到低的回溯规则"
                            + "改选更高版本 " + name + ":" + newVersion);
        }
        String brokenBy = firstUnsatisfiedRequirement(snapshot, name, oldVersion, resolved);
        return new Diff(name, VERSION_CHANGED, oldVersion, newVersion, RANGE_NO_LONGER_SATISFIED,
                "原锁定版本 " + name + ":" + oldVersion + " 不再满足当前依赖区间"
                        + (brokenBy == null ? "" : "：" + brokenBy)
                        + "，回溯解析改选 " + name + ":" + newVersion);
    }

    /**
     * 名称被移除的原因：原版本已撤回优先；否则是该名称在当前依赖闭包中不再被任何制品要求，
     * 归类为依赖区间不再满足（区间要求消失）。
     */
    private static Diff removed(String name, int oldVersion,
                                Map<String, Integer> resolved,
                                RepositorySnapshot snapshot) {
        if (isWithdrawn(snapshot, name, oldVersion)) {
            return new Diff(name, REMOVED, oldVersion, null, ORIGINAL_WITHDRAWN,
                    "原锁定版本 " + name + ":" + oldVersion + " 已撤回，且当前可行解不再包含名称 "
                            + name);
        }
        return new Diff(name, REMOVED, oldVersion, null, RANGE_NO_LONGER_SATISFIED,
                "当前依赖闭包不再要求名称 " + name + "（原锁定版本 " + name + ":" + oldVersion
                        + " 随区间要求消失而移除）");
    }

    private static boolean isWithdrawn(RepositorySnapshot snapshot, String name, int version) {
        return snapshot.artifacts().getOrDefault(name, List.of()).stream()
                .filter(a -> a.version() == version)
                .findFirst()
                .map(ArtifactVersion::withdrawn)
                .orElse(false);
    }

    /** 假设仍选 oldVersion，它声明的依赖区间是否全部被新解析集合满足。 */
    private static boolean stillSatisfiesAll(RepositorySnapshot snapshot, String name,
                                             int oldVersion, Map<String, Integer> resolved) {
        return firstUnsatisfiedRequirement(snapshot, name, oldVersion, resolved) == null;
    }

    /** 返回首个不满足的区间说明（指向新集合中存在的名称）；全部满足返回 null。 */
    private static String firstUnsatisfiedRequirement(RepositorySnapshot snapshot, String name,
                                                      int oldVersion, Map<String, Integer> resolved) {
        ArtifactVersion artifact = snapshot.artifacts().getOrDefault(name, List.of()).stream()
                .filter(a -> a.version() == oldVersion)
                .findFirst()
                .orElse(null);
        if (artifact == null) {
            return null;
        }
        TreeMap<String, int[]> merged = new TreeMap<>();
        for (DependencyRange dep : artifact.dependencies()) {
            int[] range = merged.computeIfAbsent(dep.name(), k -> new int[]{
                    dep.minimumVersion(), dep.maximumVersion()});
            range[0] = Math.max(range[0], dep.minimumVersion());
            range[1] = Math.min(range[1], dep.maximumVersion());
        }
        for (Map.Entry<String, int[]> requirement : merged.entrySet()) {
            Integer chosen = resolved.get(requirement.getKey());
            int[] range = requirement.getValue();
            if (chosen == null || chosen < range[0] || chosen > range[1]) {
                return name + ":" + oldVersion + " 要求 " + requirement.getKey()
                        + " 处于 [" + range[0] + "," + range[1] + "]，新解析集合中为 "
                        + (chosen == null ? "不包含该名称" : requirement.getKey() + ":" + chosen);
            }
        }
        return null;
    }
}
