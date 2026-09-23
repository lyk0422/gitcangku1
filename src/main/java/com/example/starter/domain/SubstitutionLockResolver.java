package com.example.starter.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 替代感知的锁图解析器。
 *
 * <p>在一致性快照（制品、撤回状态、平台可用性、唯一 policyVersion）上工作：
 * <ol>
 *   <li>先按既有区间规则选版本（区间交集、高版本优先、带回溯），撤回版本与目标平台不可用版本不可选；</li>
 *   <li>仅当某原坐标在路径区间内没有任何可直接使用的版本（选中版本已撤回或平台不可用）时，
 *       才按当前生效策略、候选优先级依次尝试替代；</li>
 *   <li>候选终点自身无可用版本且命中生效规则时多跳展开；链落定后重新求解整张图的完整传递依赖；</li>
 *   <li>相同原坐标在整张图中只有一个结论；两个路径结论不同、规则同优先级冲突、
 *       替代后版本约束不相容或产生坐标环时，确定性失败（整次 422）。</li>
 * </ol>
 */
public final class SubstitutionLockResolver {

    /** 候选拒绝原因：仓库中不存在该坐标的任何版本。 */
    public static final String REASON_NOT_FOUND = "NOT_FOUND";
    /** 候选拒绝原因：该坐标存在版本但全部已撤回。 */
    public static final String REASON_WITHDRAWN = "WITHDRAWN";
    /** 候选拒绝原因：存在未撤回版本但在目标平台全部不可用。 */
    public static final String REASON_UNAVAILABLE_PLATFORM = "UNAVAILABLE_PLATFORM";
    /** 候选拒绝原因：候选终点版本与路径上的版本约束不相容。 */
    public static final String REASON_CONSTRAINT_INCOMPATIBLE = "CONSTRAINT_INCOMPATIBLE";
    /** 候选拒绝原因：展开替代环。 */
    public static final String REASON_CYCLE = "CYCLE";
    /** 候选拒绝原因：区间内无可用版本或替代链终点无可用节点。 */
    public static final String REASON_NO_USABLE_VERSION = "NO_USABLE_VERSION";

    private SubstitutionLockResolver() {
    }

    /**
     * 解析锁图。
     *
     * @return 可行组合与冻结解释；不存在与替代无关的可行组合时返回 null
     * @throws SubstitutionFailureException 替代冲突、环、约束不相容等确定性 422
     */
    public static SubstitutionResult resolve(RepositorySnapshot snapshot,
                                             PlatformAvailability availability,
                                             SubstitutionPolicy policy,
                                             String rootName, int rootVersion,
                                             String platform, Instant now) {
        State state = new State(snapshot, availability, policy, platform, now);
        ArtifactVersion root = state.findRoot(rootName, rootVersion);
        if (root == null || root.withdrawn()) {
            throw new IllegalArgumentException("根制品版本不存在或已撤回: " + rootName + ":" + rootVersion);
        }
        if (!availability.available(root, platform)) {
            throw new SubstitutionFailureException(
                    "根制品版本在目标平台不可用: " + rootName + ":" + rootVersion + " / " + platform);
        }
        state.chosen.put(rootName, root);
        state.bindings.put(rootName, Binding.direct(rootVersion));

        if (!state.solve()) {
            return null;
        }

        TreeMap<String, Integer> versions = new TreeMap<>();
        state.chosen.forEach((coordinate, artifact) -> versions.put(coordinate, artifact.version()));
        List<SubstitutionStep> steps = state.buildSteps();
        Long policyVersion = policy == null ? null : policy.version();
        return new SubstitutionResult(versions, steps, policyVersion);
    }

    // ------------------------------------------------------------------
    // 解析状态
    // ------------------------------------------------------------------

    private static final class State {
        private final RepositorySnapshot snapshot;
        private final PlatformAvailability availability;
        private final SubstitutionPolicy policy;
        private final String platform;
        private final Instant now;

        /** 最终坐标 -> 选中的精确制品版本（直接坐标或替代链终点）。 */
        private final TreeMap<String, ArtifactVersion> chosen = new TreeMap<>();
        /** 图中出现过的每个原坐标的唯一结论，按首次结论顺序排列。 */
        private final LinkedHashMap<String, Binding> bindings = new LinkedHashMap<>();

        private State(RepositorySnapshot snapshot, PlatformAvailability availability,
                      SubstitutionPolicy policy, String platform, Instant now) {
            this.snapshot = snapshot;
            this.availability = availability;
            this.policy = policy;
            this.platform = platform;
            this.now = now;
        }

        private ArtifactVersion findRoot(String name, int version) {
            return snapshot.artifacts().getOrDefault(name, List.of()).stream()
                    .filter(a -> a.version() == version)
                    .findFirst()
                    .orElse(null);
        }

        private SnapshotMemento snapshot() {
            return new SnapshotMemento(new TreeMap<>(chosen), new LinkedHashMap<>(bindings));
        }

        private void restore(SnapshotMemento memento) {
            chosen.clear();
            chosen.putAll(memento.chosen);
            bindings.clear();
            bindings.putAll(memento.bindings);
        }

        /**
         * 求解主循环：汇总已选节点对未决坐标的区间交集，字典序选一个坐标继续。
         */
        private boolean solve() {
            String next = null;
            int lo = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;

            for (ArtifactVersion artifact : chosen.values()) {
                for (DependencyRange dep : artifact.dependencies()) {
                    Binding binding = bindings.get(dep.name());
                    if (binding == null) {
                        if (next == null || dep.name().compareTo(next) < 0) {
                            next = dep.name();
                            lo = dep.minimumVersion();
                            hi = dep.maximumVersion();
                        } else if (dep.name().equals(next)) {
                            lo = Math.max(lo, dep.minimumVersion());
                            hi = Math.min(hi, dep.maximumVersion());
                        }
                    } else if (binding.substituted) {
                        // 另一条路径指向已替代原坐标：终点版本不落在路径区间属于题干规定的
                        // “替代后版本约束不相容”，替代结论不可回退，整次确定性 422。
                        ArtifactVersion endpoint = chosen.get(binding.endpoint);
                        if (endpoint != null
                                && (endpoint.version() < dep.minimumVersion()
                                || endpoint.version() > dep.maximumVersion())) {
                            throw new SubstitutionFailureException(
                                    "替代后版本约束不相容：原坐标 " + dep.name()
                                            + " 的终点 " + binding.endpoint + ":" + endpoint.version()
                                            + " 不满足路径区间 [" + dep.minimumVersion()
                                            + "," + dep.maximumVersion() + "]");
                        }
                    } else {
                        ArtifactVersion selected = chosen.get(dep.name());
                        if (selected != null
                                && (selected.version() < dep.minimumVersion()
                                || selected.version() > dep.maximumVersion())) {
                            return false;
                        }
                    }
                }
            }

            if (next == null) {
                return true;
            }
            if (lo > hi) {
                return false;
            }
            return resolveCoordinate(next, lo, hi);
        }

        /**
         * 解析一个未决原坐标：先尝试直接版本；区间内全部撤回/不可用时才按策略替代。
         */
        private boolean resolveCoordinate(String coordinate, int lo, int hi) {
            for (ArtifactVersion candidate : directCandidates(coordinate, lo, hi)) {
                SnapshotMemento memento = snapshot();
                chosen.put(coordinate, candidate);
                bindings.put(coordinate, Binding.direct(candidate.version()));
                if (solve()) {
                    return true;
                }
                restore(memento);
            }

            SubstitutionRule rule = activeRule(coordinate);
            if (rule == null) {
                return false;
            }
            SnapshotMemento beforeSubstitution = snapshot();
            boolean ok = attemptChain(coordinate, rule, Set.of(coordinate), lo, hi, new ArrayList<>());
            if (ok) {
                return true;
            }
            restore(beforeSubstitution);
            throw new SubstitutionFailureException(
                    "坐标 " + coordinate + " 在平台 " + platform
                            + " 上无可用版本，且全部替代候选均被拒绝，无法锁定");
        }

        /**
         * 在一跳上按优先级尝试候选，并把外层已构建的跳前缀一起落定后重新求解全图。
         *
         * <p>成功时保留状态并返回 true；全部候选失败时恢复到入口状态并返回 false。
         *
         * @param original 当前跳的原坐标
         * @param rule     当前跳命中的规则
         * @param ancestors 替代链上已遍历的原坐标（环检测）
         * @param lo       路径对整条链终点的版本下界（含）
         * @param hi       路径对整条链终点的版本上界（含）
         * @param prefix   外层已构建的跳（当前跳成功后位于它们之后）
         */
        private boolean attemptChain(String original, SubstitutionRule rule,
                                     Set<String> ancestors, int lo, int hi,
                                     List<Hop> prefix) {
            List<SubstitutionCandidate> ordered = rule.candidates().stream()
                    .sorted(java.util.Comparator.comparingInt(SubstitutionCandidate::priority))
                    .toList();
            List<CandidateRejection> levelRejections = new ArrayList<>();
            SnapshotMemento entry = snapshot();

            for (SubstitutionCandidate candidate : ordered) {
                String target = candidate.coordinate();
                Hop hop = new Hop(original, rule, target);

                if (formsCycle(original, target, ancestors)) {
                    levelRejections.add(new CandidateRejection(
                            target, candidate.priority(), REASON_CYCLE));
                    continue;
                }

                Binding targetBinding = bindings.get(target);
                if (targetBinding != null) {
                    if (tryCommittedTarget(original, hop, target, targetBinding,
                            lo, hi, prefix, levelRejections, candidate.priority())) {
                        return true;
                    }
                    continue;
                }

                List<ArtifactVersion> versions = endpointCandidates(target, lo, hi);
                if (!versions.isEmpty()) {
                    if (tryFreshEndpoint(original, hop, target, versions,
                            lo, hi, prefix, levelRejections, candidate.priority())) {
                        return true;
                    }
                    continue;
                }

                SubstitutionRule nextRule = activeRule(target);
                if (nextRule != null) {
                    // 多跳：候选坐标自身无可用版本，按其命中规则继续展开。
                    hop.rejections.addAll(levelRejections);
                    SnapshotMemento beforeInner = snapshot();
                    List<Hop> innerPrefix = new ArrayList<>(prefix);
                    innerPrefix.add(hop);
                    Set<String> nextAncestors = new java.util.HashSet<>(ancestors);
                    nextAncestors.add(target);
                    if (attemptChain(target, nextRule, nextAncestors, lo, hi, innerPrefix)) {
                        return true;
                    }
                    restore(beforeInner);
                    levelRejections.add(new CandidateRejection(
                            target, candidate.priority(), REASON_NO_USABLE_VERSION));
                    continue;
                }

                levelRejections.add(new CandidateRejection(
                        target, candidate.priority(), classifyUnusable(target)));
            }

            restore(entry);
            return false;
        }

        /**
         * 候选坐标已有结论：复用其终点，落定整条跳链后重新求解全图。
         */
        private boolean tryCommittedTarget(String original, Hop hop, String target,
                                           Binding targetBinding, int lo, int hi,
                                           List<Hop> prefix,
                                           List<CandidateRejection> levelRejections,
                                           int priority) {
            String endpoint = targetBinding.substituted ? targetBinding.endpoint : target;
            ArtifactVersion endpointVersion = chosen.get(endpoint);
            if (endpointVersion == null) {
                levelRejections.add(new CandidateRejection(
                        target, priority, REASON_NO_USABLE_VERSION));
                return false;
            }
            hop.rejections.addAll(levelRejections);
            return applyAndSolve(prefix, hop, null, endpoint, endpointVersion,
                    levelRejections, target, priority);
        }

        /**
         * 候选坐标尚无结论：按高版本优先尝试终点版本并重新求解全图。
         */
        private boolean tryFreshEndpoint(String original, Hop hop, String target,
                                         List<ArtifactVersion> versions, int lo, int hi,
                                         List<Hop> prefix,
                                         List<CandidateRejection> levelRejections,
                                         int priority) {
            for (ArtifactVersion endpointVersion : versions) {
                hop.rejections.addAll(levelRejections);
                if (applyAndSolve(prefix, hop, target, target, endpointVersion,
                        levelRejections, target, priority)) {
                    return true;
                }
                hop.rejections.clear();
            }
            levelRejections.add(new CandidateRejection(
                    target, priority, REASON_CONSTRAINT_INCOMPATIBLE));
            return false;
        }

        /**
         * 落定跳链与终点选择：先做纯校验，通过后写入选中版本与每跳原坐标的唯一替代结论，
         * 再重新求解全图；失败恢复并返回 false。替代结论冲突属于确定性 422，直接向上抛出。
         *
         * @param newChosenCoordinate 新选入图的坐标（终点已在图中时为 null）
         */
        private boolean applyAndSolve(List<Hop> prefix, Hop hop, String newChosenCoordinate,
                                      String endpoint, ArtifactVersion endpointVersion,
                                      List<CandidateRejection> levelRejections,
                                      String rejectedCoordinate, int rejectedPriority) {
            List<Hop> chain = new ArrayList<>(prefix);
            chain.add(hop);
            for (Hop committed : chain) {
                Binding previous = bindings.get(committed.original);
                if (previous != null && previous.substituted
                        && !previous.endpoint.equals(endpoint)) {
                    throw new SubstitutionFailureException(
                            "同一原坐标在不同路径上得到不同替代结果: " + committed.original
                                    + " -> " + previous.endpoint + " / " + endpoint);
                }
            }
            if (!chainRangesSatisfied(chain, endpointVersion)) {
                levelRejections.add(new CandidateRejection(
                        rejectedCoordinate, rejectedPriority, REASON_CONSTRAINT_INCOMPATIBLE));
                return false;
            }

            SnapshotMemento memento = snapshot();
            if (newChosenCoordinate != null) {
                chosen.put(newChosenCoordinate, endpointVersion);
                bindings.put(newChosenCoordinate, Binding.direct(endpointVersion.version()));
            }
            for (Hop committed : chain) {
                bindings.put(committed.original, Binding.substituted(committed, endpoint));
            }
            if (solve()) {
                return true;
            }
            restore(memento);
            levelRejections.add(new CandidateRejection(
                    rejectedCoordinate, rejectedPriority, REASON_CONSTRAINT_INCOMPATIBLE));
            return false;
        }

        /**
         * 终点版本必须满足全图中所有已选节点（含候选自身）对链上任一原坐标声明的区间。
         */
        private boolean chainRangesSatisfied(List<Hop> chain, ArtifactVersion endpointVersion) {
            Set<String> originals = new java.util.HashSet<>();
            for (Hop h : chain) {
                originals.add(h.original);
            }
            List<ArtifactVersion> allArtifacts = new ArrayList<>(chosen.values());
            allArtifacts.add(endpointVersion);
            for (ArtifactVersion artifact : allArtifacts) {
                for (DependencyRange dep : artifact.dependencies()) {
                    if (originals.contains(dep.name())
                            && (endpointVersion.version() < dep.minimumVersion()
                            || endpointVersion.version() > dep.maximumVersion())) {
                        return false;
                    }
                }
            }
            return true;
        }

        // ------------------------------------------------------------------
        // 环检测、规则与候选版本
        // ------------------------------------------------------------------

        /**
         * 加入边 original → target 后是否形成坐标环：
         * target 命中链上祖先，或沿已落定替代边可以从 target 回到任一祖先。
         */
        private boolean formsCycle(String original, String target, Set<String> ancestors) {
            if (target.equals(original) || ancestors.contains(target)) {
                return true;
            }
            Map<String, String> edges = new HashMap<>();
            for (Binding binding : bindings.values()) {
                if (binding.substituted) {
                    edges.put(binding.hop.original, binding.hop.target);
                }
            }
            String node = target;
            Set<String> seen = new java.util.HashSet<>();
            while (node != null && seen.add(node)) {
                Binding binding = bindings.get(node);
                if (binding == null || !binding.substituted) {
                    String next = edges.get(node);
                    if (next == null) {
                        return false;
                    }
                    node = next;
                } else {
                    node = binding.endpoint;
                }
                if (ancestors.contains(node) || node.equals(original)) {
                    return true;
                }
            }
            return false;
        }

        /** 区间内可直接使用的版本（未撤回、平台可用、与已选节点相容），版本降序。 */
        private List<ArtifactVersion> directCandidates(String coordinate, int lo, int hi) {
            List<ArtifactVersion> result = new ArrayList<>();
            for (ArtifactVersion candidate : snapshot.artifacts().getOrDefault(coordinate, List.of())) {
                if (candidate.version() < lo || candidate.version() > hi) {
                    continue;
                }
                if (candidate.withdrawn() || !availability.available(candidate, platform)) {
                    continue;
                }
                if (!satisfiesChosen(candidate)) {
                    continue;
                }
                result.add(candidate);
            }
            return result;
        }

        /**
         * 替代终点候选：同时满足路径对原坐标与全图对目标坐标的区间交集、
         * 未撤回、平台可用，且其声明依赖与已选节点相容；版本降序。
         */
        private List<ArtifactVersion> endpointCandidates(String target, int lo, int hi) {
            int targetLo = lo;
            int targetHi = hi;
            for (ArtifactVersion artifact : chosen.values()) {
                for (DependencyRange dep : artifact.dependencies()) {
                    if (dep.name().equals(target)) {
                        targetLo = Math.max(targetLo, dep.minimumVersion());
                        targetHi = Math.min(targetHi, dep.maximumVersion());
                    }
                }
            }
            List<ArtifactVersion> result = new ArrayList<>();
            List<ArtifactVersion> versions = snapshot.artifacts().get(target);
            if (versions == null) {
                return result;
            }
            for (ArtifactVersion candidate : versions) {
                if (candidate.version() < targetLo || candidate.version() > targetHi) {
                    continue;
                }
                if (candidate.withdrawn() || !availability.available(candidate, platform)) {
                    continue;
                }
                if (!satisfiesChosen(candidate)) {
                    continue;
                }
                result.add(candidate);
            }
            return result;
        }

        /** 候选坐标无任何可用版本时的稳定拒绝原因分类。 */
        private String classifyUnusable(String coordinate) {
            List<ArtifactVersion> versions = snapshot.artifacts().get(coordinate);
            if (versions == null || versions.isEmpty()) {
                return REASON_NOT_FOUND;
            }
            boolean allWithdrawn = true;
            boolean allUnavailable = true;
            for (ArtifactVersion candidate : versions) {
                if (!candidate.withdrawn()) {
                    allWithdrawn = false;
                }
                if (availability.available(candidate, platform)) {
                    allUnavailable = false;
                }
            }
            if (allWithdrawn) {
                return REASON_WITHDRAWN;
            }
            if (allUnavailable) {
                return REASON_UNAVAILABLE_PLATFORM;
            }
            return REASON_CONSTRAINT_INCOMPATIBLE;
        }

        /** 查找坐标在当前平台、当前时刻命中的唯一生效规则；无命中返回 null。 */
        private SubstitutionRule activeRule(String coordinate) {
            if (policy == null) {
                return null;
            }
            SubstitutionRule matched = null;
            for (SubstitutionRule rule : policy.rules()) {
                if (!rule.platform().equals(platform)) {
                    continue;
                }
                if (rule.effectiveAt().isAfter(now)) {
                    continue;
                }
                if (rule.source().matches(coordinate)) {
                    if (matched != null) {
                        throw new SubstitutionFailureException(
                                "坐标 " + coordinate + " 在策略版本 " + policy.version()
                                        + " 中命中同优先级冲突规则");
                    }
                    matched = rule;
                }
            }
            return matched;
        }

        /**
         * 候选制品对所有已选名称（含自身）声明的区间必须成立；
         * 若依赖的原坐标已被替代，则对替代终点版本校验。
         */
        private boolean satisfiesChosen(ArtifactVersion candidate) {
            for (DependencyRange dep : candidate.dependencies()) {
                Binding binding = bindings.get(dep.name());
                ArtifactVersion selected;
                if (binding == null) {
                    continue;
                }
                if (binding.substituted) {
                    selected = chosen.get(binding.endpoint);
                } else {
                    selected = chosen.get(dep.name());
                }
                if (selected == null) {
                    continue;
                }
                if (selected.version() < dep.minimumVersion()
                        || selected.version() > dep.maximumVersion()) {
                    return false;
                }
            }
            return true;
        }

        /**
         * 由全部已落定替代结论按发生顺序生成冻结步骤。
         */
        private List<SubstitutionStep> buildSteps() {
            List<SubstitutionStep> result = new ArrayList<>();
            long policyVersion = policy == null ? 0L : policy.version();
            int order = 0;
            for (Binding binding : bindings.values()) {
                if (!binding.substituted) {
                    continue;
                }
                Hop hop = binding.hop;
                result.add(new SubstitutionStep(order++, hop.original,
                        hop.rule.source().raw(), hop.rule.platform(),
                        binding.endpoint, policyVersion, List.copyOf(hop.rejections)));
            }
            return result;
        }
    }

    private record SnapshotMemento(
            TreeMap<String, ArtifactVersion> chosen,
            LinkedHashMap<String, Binding> bindings) {
    }

    /**
     * 原坐标在本次图解析中的唯一结论。
     */
    private static final class Binding {
        private final boolean substituted;
        private final int version;
        private final Hop hop;
        private final String endpoint;

        private Binding(boolean substituted, int version, Hop hop, String endpoint) {
            this.substituted = substituted;
            this.version = version;
            this.hop = hop;
            this.endpoint = endpoint;
        }

        private static Binding direct(int version) {
            return new Binding(false, version, null, null);
        }

        private static Binding substituted(Hop hop, String endpoint) {
            return new Binding(true, 0, hop, endpoint);
        }
    }

    /**
     * 替代链上的一跳：原坐标经某规则指向紧邻的下一个坐标，
     * 以及该跳上在成功候选之前被拒绝的候选快照。
     */
    private static final class Hop {
        private final String original;
        private final SubstitutionRule rule;
        private final String target;
        private final List<CandidateRejection> rejections = new ArrayList<>();

        private Hop(String original, SubstitutionRule rule, String target) {
            this.original = original;
            this.rule = rule;
            this.target = target;
        }
    }
}
