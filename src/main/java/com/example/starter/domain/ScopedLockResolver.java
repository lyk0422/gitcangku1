package com.example.starter.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 私有作用域锁定解析器：在一致性仓库快照上求解带作用域的精确版本组合。
 *
 * <p>规则：
 * <ul>
 *   <li>根制品精确版本固定，不回退；</li>
 *   <li>PUBLIC 依赖在当前作用域内共享，同一名称只允许一个精确版本；</li>
 *   <li>每条 PRIVATE 边把目标及其 PUBLIC 闭包隔离到以（父作用域, 父制品名称）定位的
 *       子作用域；同一父制品的多条 PRIVATE 边共用同一个子作用域；</li>
 *   <li>每个作用域按名称字典序、候选版本降序取首个完整解，子作用域不可行时回溯父作用域；</li>
 *   <li>相同 name+version 出现在祖先链（PRIVATE 边路径）上视为环，该候选不可用，
 *       无法通过不断创建子作用域逃避环；</li>
 *   <li>作用域嵌套最深 {@value #MAX_SCOPE_DEPTH} 层（根为第 1 层），越限抛
 *       {@link ScopeDepthExceededException}。</li>
 * </ul>
 */
public final class ScopedLockResolver {

    /** 作用域最大层数：根作用域为第 1 层，每条 PRIVATE 边深入一层。 */
    public static final int MAX_SCOPE_DEPTH = 4;

    private ScopedLockResolver() {
    }

    /** 作用域嵌套超过最大层数。 */
    public static final class ScopeDepthExceededException extends RuntimeException {
        public ScopeDepthExceededException(String message) {
            super(message);
        }
    }

    /**
     * 求解作用域锁定组合。
     *
     * @param snapshot    一致性仓库快照（每名称版本按版本号降序）
     * @param rootName    根制品名称
     * @param rootVersion 根制品精确版本（必须存在且未撤回，由调用方保证）
     * @return 完整解；全部组合不可行（含不可避免的环）时返回 null
     * @throws ScopeDepthExceededException 作用域嵌套超过 {@value #MAX_SCOPE_DEPTH} 层
     */
    public static ScopedLockSolution resolve(RepositorySnapshot snapshot,
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

        ScopeState rootScope = new ScopeState(null, 1, List.of(), List.of());
        rootScope.chosen.put(rootName, root);
        // 根可能自依赖：固定版本必须落在自身声明的 PUBLIC 区间内。
        if (!satisfiesChosen(root, rootScope.chosen)) {
            return null;
        }
        if (!solveScope(all, rootScope)) {
            return null;
        }
        return toSolution(rootScope);
    }

    /** 解析中的作用域状态。 */
    private static final class ScopeState {
        final ScopeState parent;
        final String parentArtifactName;
        final int depth;
        /** 祖先链：从根到本作用域父制品的 PRIVATE 边路径上的制品。 */
        final List<ArtifactVersion> chain;
        /** 子作用域的种子需求（父制品的全部 PRIVATE 依赖）。 */
        final List<DependencyRange> seeds;
        final TreeMap<String, ArtifactVersion> chosen = new TreeMap<>();
        List<ScopeState> children = List.of();

        ScopeState(ScopeState parent, int depth, List<ArtifactVersion> chain, List<DependencyRange> seeds) {
            this.parent = parent;
            this.parentArtifactName = parent == null ? null
                    : chain.isEmpty() ? null : chain.get(chain.size() - 1).name();
            this.depth = depth;
            this.chain = chain;
            this.seeds = seeds;
        }
    }

    /**
     * 递归回溯解析单个作用域：选字典序最小的未解析名称，候选版本从高到低尝试；
     * 作用域内名称全部解析后，按父制品名称字典序解析各子作用域，失败则回溯。
     */
    private static boolean solveScope(Map<String, List<ArtifactVersion>> all, ScopeState scope) {
        String next = null;
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        // 种子需求（父制品的 PRIVATE 依赖，仅子作用域有）。
        for (DependencyRange dep : scope.seeds) {
            if (scope.chosen.containsKey(dep.name())) {
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
        // 已选制品的 PUBLIC 依赖在本作用域内解析。
        for (ArtifactVersion artifact : scope.chosen.values()) {
            for (DependencyRange dep : artifact.dependencies()) {
                if (dep.scope() != DependencyScope.PUBLIC || scope.chosen.containsKey(dep.name())) {
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
            // 本作用域已完成：构建并解析子作用域（按父制品名称字典序）。
            List<ScopeState> children = buildChildren(scope);
            for (ScopeState child : children) {
                if (!solveScope(all, child)) {
                    return false;
                }
            }
            scope.children = children;
            return true;
        }
        if (lo > hi) {
            return false;
        }

        for (ArtifactVersion candidate : all.getOrDefault(next, List.of())) {
            if (candidate.withdrawn() || candidate.version() < lo || candidate.version() > hi) {
                continue;
            }
            // 环守卫：祖先链上已出现相同 name+version，不能靠子作用域重复引入。
            if (onAncestorChain(scope.chain, candidate)) {
                continue;
            }
            if (!satisfiesChosen(candidate, scope.chosen)) {
                continue;
            }
            scope.chosen.put(next, candidate);
            if (solveScope(all, scope)) {
                return true;
            }
            scope.chosen.remove(next);
        }
        return false;
    }

    /**
     * 为本作用域内声明了 PRIVATE 依赖的每个制品构建子作用域；
     * 同一父制品的多条 PRIVATE 边合并为同一子作用域的种子需求。
     */
    private static List<ScopeState> buildChildren(ScopeState scope) {
        TreeMap<String, ScopeState> byParentName = new TreeMap<>();
        for (ArtifactVersion artifact : scope.chosen.values()) {
            List<DependencyRange> privates = new ArrayList<>();
            for (DependencyRange dep : artifact.dependencies()) {
                if (dep.scope() == DependencyScope.PRIVATE) {
                    privates.add(dep);
                }
            }
            if (privates.isEmpty()) {
                continue;
            }
            if (scope.depth + 1 > MAX_SCOPE_DEPTH) {
                throw new ScopeDepthExceededException(
                        "作用域嵌套超过最大层数 " + MAX_SCOPE_DEPTH + "：制品 "
                                + artifact.name() + ":" + artifact.version() + " 声明了私有依赖");
            }
            List<ArtifactVersion> chain = new ArrayList<>(scope.chain);
            chain.add(artifact);
            byParentName.put(artifact.name(), new ScopeState(
                    scope, scope.depth + 1, List.copyOf(chain), List.copyOf(privates)));
        }
        return List.copyOf(byParentName.values());
    }

    /** 候选制品声明的、指向本作用域已选名称的 PUBLIC 区间均被满足。 */
    private static boolean satisfiesChosen(ArtifactVersion candidate,
                                           Map<String, ArtifactVersion> chosen) {
        for (DependencyRange dep : candidate.dependencies()) {
            if (dep.scope() != DependencyScope.PUBLIC) {
                continue;
            }
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

    /** 候选是否与祖先链上的制品同名同版本（环）。 */
    private static boolean onAncestorChain(List<ArtifactVersion> chain, ArtifactVersion candidate) {
        for (ArtifactVersion ancestor : chain) {
            if (ancestor.name().equals(candidate.name()) && ancestor.version() == candidate.version()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把解析成功的作用域树转换为结构化解：父作用域索引恒小于子作用域，
     * 路径为父制品名称列表；边按来源作用域、来源名称、目标名称、作用域排序。
     */
    private static ScopedLockSolution toSolution(ScopeState root) {
        List<ScopedLockSolution.ResolvedScope> scopes = new ArrayList<>();
        List<ScopedLockSolution.ResolvedEdge> edges = new ArrayList<>();
        collect(root, -1, List.of(), scopes, edges);
        edges.sort(Comparator.comparingInt(ScopedLockSolution.ResolvedEdge::fromScopeIndex)
                .thenComparing(ScopedLockSolution.ResolvedEdge::fromName)
                .thenComparing(ScopedLockSolution.ResolvedEdge::toName)
                .thenComparing(e -> e.scope().name()));
        return new ScopedLockSolution(List.copyOf(scopes), List.copyOf(edges));
    }

    private static int collect(ScopeState scope, int parentIndex, List<String> parentPath,
                               List<ScopedLockSolution.ResolvedScope> scopes,
                               List<ScopedLockSolution.ResolvedEdge> edges) {
        int index = scopes.size();
        List<String> path = parentPath;
        if (scope.parentArtifactName != null) {
            path = new ArrayList<>(parentPath);
            path.add(scope.parentArtifactName);
        }
        TreeMap<String, Integer> entries = new TreeMap<>();
        scope.chosen.forEach((name, artifact) -> entries.put(name, artifact.version()));
        scopes.add(new ScopedLockSolution.ResolvedScope(index, parentIndex, scope.parentArtifactName,
                scope.depth, List.copyOf(path), java.util.Collections.unmodifiableMap(entries)));

        Map<String, ScopeState> childByParentName = new HashMap<>();
        Map<String, Integer> childIndexByParentName = new HashMap<>();
        for (ScopeState child : scope.children) {
            childByParentName.put(child.parentArtifactName, child);
            childIndexByParentName.put(child.parentArtifactName,
                    collect(child, index, path, scopes, edges));
        }

        for (ArtifactVersion artifact : scope.chosen.values()) {
            for (DependencyRange dep : artifact.dependencies()) {
                if (dep.scope() == DependencyScope.PUBLIC) {
                    ArtifactVersion target = scope.chosen.get(dep.name());
                    edges.add(new ScopedLockSolution.ResolvedEdge(index, artifact.name(), dep.name(),
                            target.version(), DependencyScope.PUBLIC, index));
                } else {
                    ScopeState child = childByParentName.get(artifact.name());
                    ArtifactVersion target = child.chosen.get(dep.name());
                    edges.add(new ScopedLockSolution.ResolvedEdge(index, artifact.name(), dep.name(),
                            target.version(), DependencyScope.PRIVATE,
                            childIndexByParentName.get(artifact.name())));
                }
            }
        }
        return index;
    }
}
