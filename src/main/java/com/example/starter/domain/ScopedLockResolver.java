package com.example.starter.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 带私有作用域的锁定解析器：在一致性仓库快照上求解“作用域 -> 名称 -> 精确版本”。
 *
 * <p>规则：根版本固定在根作用域（空路径）；PUBLIC 依赖在当前作用域内共享名称唯一版本；
 * 每条 PRIVATE 边把目标引入以“父作用域路径 + 父制品名称”定位的子作用域，同一父制品的
 * 多条 PRIVATE 边共用该子作用域；子作用域内目标及其 PUBLIC 闭包独立解析，不与父域合并。
 *
 * <p>求解顺序：作用域路径字典序、名称字典序、版本降序，取首个完整解。
 * 祖先链（创建当前作用域链的制品精确版本）中重复出现同名同版本视为环，候选被排除；
 * 作用域嵌套深度（路径段数）不得超过 {@link #MAX_SCOPE_DEPTH}，超限候选被排除。
 * 无可行解时返回 null，由调用方映射为 422。
 */
public final class ScopedLockResolver {

    /** 作用域路径最大段数（根作用域深度为 0，最多再嵌套 4 层子作用域）。 */
    public static final int MAX_SCOPE_DEPTH = 4;

    private ScopedLockResolver() {
    }

    /**
     * 求解带作用域的锁定组合。
     *
     * @param snapshot    一致性仓库快照（每名称版本按版本号降序）
     * @param rootName    根制品名称
     * @param rootVersion 根制品精确版本（必须存在且未撤回，由调用方保证）
     * @return 作用域路径（父制品名称链，根为空表）-> 名称 -> 制品版本；不可行返回 null
     */
    public static Map<List<String>, Map<String, ArtifactVersion>> resolve(
            RepositorySnapshot snapshot, String rootName, int rootVersion) {
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

        Map<List<String>, Map<String, ArtifactVersion>> assignments = new LinkedHashMap<>();
        Map<String, ArtifactVersion> rootScope = new TreeMap<>();
        rootScope.put(rootName, root);
        assignments.put(List.of(), rootScope);
        // 根可能自依赖：固定版本也必须落在自身声明的 PUBLIC 区间内。
        if (!satisfiesScope(root, rootScope)) {
            return null;
        }
        return solve(all, assignments);
    }

    /**
     * 递归回溯：按（作用域路径，名称）字典序取下一个未解析约束，候选版本从高到低尝试。
     * 返回的映射与入参不可变隔离；失败返回 null。
     */
    private static Map<List<String>, Map<String, ArtifactVersion>> solve(
            Map<String, List<ArtifactVersion>> all,
            Map<List<String>, Map<String, ArtifactVersion>> assignments) {
        List<String> targetScope = null;
        String targetName = null;
        // 汇总全部作用域中“已选制品指向未解析名称”的约束，取字典序最小者。
        for (List<String> scopePath : sortedScopePaths(assignments)) {
            for (ArtifactVersion artifact : assignments.get(scopePath).values()) {
                for (DependencyRange dep : artifact.dependencies()) {
                    List<String> depScope = dependencyScope(scopePath, artifact, dep);
                    Map<String, ArtifactVersion> depAssignments = assignments.get(depScope);
                    if (depAssignments != null && depAssignments.containsKey(dep.name())) {
                        continue;
                    }
                    if (targetName == null
                            || comparePaths(depScope, targetScope) < 0
                            || (comparePaths(depScope, targetScope) == 0
                                && dep.name().compareTo(targetName) < 0)) {
                        targetScope = depScope;
                        targetName = dep.name();
                    }
                }
            }
        }
        if (targetName == null) {
            // 所有依赖均已解析为精确版本。
            return assignments;
        }

        // 同一（作用域，名称）的多条约束取区间交集。
        int lo = 1;
        int hi = Integer.MAX_VALUE;
        for (Map.Entry<List<String>, Map<String, ArtifactVersion>> scopeEntry : assignments.entrySet()) {
            for (ArtifactVersion artifact : scopeEntry.getValue().values()) {
                for (DependencyRange dep : artifact.dependencies()) {
                    if (!dependencyScope(scopeEntry.getKey(), artifact, dep).equals(targetScope)
                            || !dep.name().equals(targetName)) {
                        continue;
                    }
                    lo = Math.max(lo, dep.minimumVersion());
                    hi = Math.min(hi, dep.maximumVersion());
                }
            }
        }
        if (lo > hi) {
            return null;
        }

        List<ArtifactVersion> ancestors = ancestors(assignments, targetScope);
        Map<String, ArtifactVersion> scopeAssignments =
                assignments.getOrDefault(targetScope, Map.of());
        // 候选从高到低（快照已按版本号降序），跳过撤回与区间外版本。
        for (ArtifactVersion candidate : all.getOrDefault(targetName, List.of())) {
            if (candidate.withdrawn() || candidate.version() < lo || candidate.version() > hi) {
                continue;
            }
            // 环：祖先链中已出现同名同版本，不能靠继续创建子作用域逃避。
            if (containsNameVersion(ancestors, targetName, candidate.version())) {
                continue;
            }
            // 候选对已选名称（含自身）的 PUBLIC 区间必须成立。
            if (!satisfiesScope(candidate, scopeAssignments)) {
                continue;
            }
            // 嵌套上限：候选若声明 PRIVATE 依赖，将再创建一层子作用域。
            if (targetScope.size() >= MAX_SCOPE_DEPTH && hasPrivateDependency(candidate)) {
                continue;
            }
            Map<List<String>, Map<String, ArtifactVersion>> next = copyOf(assignments);
            next.computeIfAbsent(targetScope, k -> new TreeMap<>()).put(targetName, candidate);
            Map<List<String>, Map<String, ArtifactVersion>> solved = solve(all, next);
            if (solved != null) {
                return solved;
            }
        }
        return null;
    }

    /** 依赖目标所在作用域：PUBLIC 为当前作用域，PRIVATE 为“当前路径 + 父制品名称”的子域。 */
    private static List<String> dependencyScope(List<String> scopePath, ArtifactVersion parent,
                                                DependencyRange dep) {
        if (dep.scope() != DependencyScope.PRIVATE) {
            return scopePath;
        }
        List<String> child = new ArrayList<>(scopePath.size() + 1);
        child.addAll(scopePath);
        child.add(parent.name());
        return List.copyOf(child);
    }

    /** 创建当前作用域链的祖先制品精确版本（根作用域无祖先）。 */
    private static List<ArtifactVersion> ancestors(
            Map<List<String>, Map<String, ArtifactVersion>> assignments, List<String> scopePath) {
        List<ArtifactVersion> result = new ArrayList<>();
        List<String> prefix = List.of();
        for (String segment : scopePath) {
            Map<String, ArtifactVersion> prefixAssignments = assignments.get(prefix);
            if (prefixAssignments == null) {
                break;
            }
            ArtifactVersion ancestor = prefixAssignments.get(segment);
            if (ancestor == null) {
                break;
            }
            result.add(ancestor);
            List<String> next = new ArrayList<>(prefix.size() + 1);
            next.addAll(prefix);
            next.add(segment);
            prefix = List.copyOf(next);
        }
        return result;
    }

    private static boolean containsNameVersion(List<ArtifactVersion> artifacts,
                                               String name, int version) {
        for (ArtifactVersion artifact : artifacts) {
            if (artifact.name().equals(name) && artifact.version() == version) {
                return true;
            }
        }
        return false;
    }

    /** 校验候选声明的、指向本作用域已选名称的 PUBLIC 依赖区间均被满足。 */
    private static boolean satisfiesScope(ArtifactVersion candidate,
                                          Map<String, ArtifactVersion> scopeAssignments) {
        for (DependencyRange dep : candidate.dependencies()) {
            if (dep.scope() != DependencyScope.PUBLIC) {
                continue;
            }
            ArtifactVersion selected = scopeAssignments.get(dep.name());
            if (selected == null) {
                continue;
            }
            if (selected.version() < dep.minimumVersion() || selected.version() > dep.maximumVersion()) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasPrivateDependency(ArtifactVersion artifact) {
        for (DependencyRange dep : artifact.dependencies()) {
            if (dep.scope() == DependencyScope.PRIVATE) {
                return true;
            }
        }
        return false;
    }

    /** 深拷贝当前分配（外层保持插入序，内层保持名称升序）。 */
    private static Map<List<String>, Map<String, ArtifactVersion>> copyOf(
            Map<List<String>, Map<String, ArtifactVersion>> assignments) {
        Map<List<String>, Map<String, ArtifactVersion>> copy = new LinkedHashMap<>();
        assignments.forEach((path, scope) -> copy.put(path, new TreeMap<>(scope)));
        return copy;
    }

    /** 作用域路径按段字典序、短路径优先排序，保证求解顺序确定。 */
    private static List<List<String>> sortedScopePaths(
            Map<List<String>, Map<String, ArtifactVersion>> assignments) {
        List<List<String>> paths = new ArrayList<>(assignments.keySet());
        paths.sort(ScopedLockResolver::comparePaths);
        return paths;
    }

    private static int comparePaths(List<String> left, List<String> right) {
        int common = Math.min(left.size(), right.size());
        for (int i = 0; i < common; i++) {
            int cmp = left.get(i).compareTo(right.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(left.size(), right.size());
    }
}
