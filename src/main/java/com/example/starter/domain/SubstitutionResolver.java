package com.example.starter.domain;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 支持依赖替代的锁定解析器。
 *
 * <p>两阶段：
 * <ol>
 *   <li>{@link #expandChain} 对不可用原坐标做确定性的替代链展开——唯一命中规则，
 *       候选按优先级取首个结构可行（存在、未撤回、平台可用、不同于原坐标、链不成环）坐标，
 *       替代坐标自身不可用则多跳继续，直至可用节点或明确无可行替代。结果按原坐标记忆，整图一致。</li>
 *   <li>{@link #solve} 在区间回溯求解中，仅当选中版本撤回或平台不可用时才替代；
 *       替代后重新解析最终坐标的完整传递依赖（外层递归自然完成），
 *       版本约束不相容、最终坐标撞版本等导致无解时整体失败（调用方转 422）。</li>
 * </ol>
 *
 * <p>下列硬性冲突直接抛 {@link SubstitutionConflictException}，整体 422 且不出部分锁：
 * 同一原坐标命中多条规则（规则同优先级冲突）；同一原名称经两个路径得到不同替代结果；
 * 两个原节点的替代最终坐标同名异版；替代链形成坐标环。
 */
public final class SubstitutionResolver {

    private final Map<String, List<ArtifactVersion>> all;
    private final List<SubstitutionRule> rules;
    private final String platform;
    private final Instant now;
    private final Long policyVersion;

    /** 原坐标 -> 确定性替代链展开结果（结构校验后记忆，不随后续回溯改变）。 */
    private final Map<Coordinate, Expansion> expansions = new HashMap<>();
    /**
     * 整次解析中每个原名称曾得到的替代最终坐标（不随回溯撤销）。
     * 同一名称一旦在任一路径上替代为某坐标，其他候选版本再得到不同结果即策略歧义，整体 422。
     */
    private final Map<String, Coordinate> observedFinalByName = new HashMap<>();
    /** 当前求解分支上每个已替代原名称对应的原坐标，随回溯撤销。 */
    private final Map<String, Coordinate> usedOriginal = new HashMap<>();
    /** 替代链展开中正在访问的原坐标，检测坐标环。 */
    private final Deque<Coordinate> activeChain = new ArrayDeque<>();

    private SubstitutionResolver(RepositorySnapshot snapshot, SubstitutionPolicy policy,
                                 String platform, Instant now) {
        this.all = snapshot.artifacts();
        this.rules = policy == null ? List.of() : policy.rules();
        this.platform = platform;
        this.now = now;
        this.policyVersion = policy == null ? null : policy.policyVersion();
    }

    /**
     * 在给定快照与策略上解析锁定。
     *
     * @return 解析结果；无可行组合时返回 null（调用方转 422）
     * @throws SubstitutionConflictException 替代策略冲突，整体 422
     */
    public static ResolutionResult resolve(RepositorySnapshot snapshot,
                                           String rootName, int rootVersion,
                                           SubstitutionPolicy policy,
                                           String platform, Instant now) {
        SubstitutionResolver resolver = new SubstitutionResolver(snapshot, policy, platform, now);
        return resolver.doResolve(rootName, rootVersion);
    }

    private ResolutionResult doResolve(String rootName, int rootVersion) {
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
        // 求解路径状态：原名称 -> 采用的替代展开；最终名称集合。随回溯撤销。
        Map<String, Expansion> usedExpansions = new HashMap<>();

        ArtifactVersion rootToUse = root;
        if (!root.availableOn(platform)) {
            Expansion rootExpansion = expansionFor(new Coordinate(rootName, rootVersion));
            if (!rootExpansion.available()) {
                return null;
            }
            rootToUse = mustFind(rootExpansion.finalCoordinate());
            usedExpansions.put(rootName, rootExpansion);
            usedOriginal.put(rootName, new Coordinate(rootName, rootVersion));
            observedFinalByName.put(rootName, rootExpansion.finalCoordinate());
        }
        chosen.put(rootToUse.name(), rootToUse);
        if (!satisfiesChosen(rootToUse, chosen, usedExpansions)) {
            return null;
        }
        if (!solve(chosen, usedExpansions)) {
            return null;
        }

        TreeMap<String, Integer> solution = new TreeMap<>();
        chosen.forEach((name, artifact) -> solution.put(name, artifact.version()));
        return new ResolutionResult(solution, collectSteps(usedExpansions), policyVersion);
    }

    /**
     * 汇总最终解实际用到的替代步骤：按原坐标（名称、版本）稳定排序。
     * 每个步骤的最终坐标取该原节点替代链末端的可用坐标；链内多跳各自记录命中规则与候选拒绝原因。
     */
    private List<SubstitutionStep> collectSteps(Map<String, Expansion> usedExpansions) {
        Map<Coordinate, SubstitutionStep> stepsByOriginal = new HashMap<>();
        long pv = policyVersion == null ? -1L : policyVersion;
        for (Expansion expansion : usedExpansions.values()) {
            for (Hop hop : expansion.hops()) {
                stepsByOriginal.putIfAbsent(hop.original(), new SubstitutionStep(
                        hop.original(), hop.rule().id(), hop.rule().ruleIndex(),
                        hop.rule().sourcePattern(), expansion.finalCoordinate(),
                        List.copyOf(hop.rejected()), pv));
            }
        }
        List<SubstitutionStep> steps = new ArrayList<>(stepsByOriginal.values());
        steps.sort(Comparator.comparing((SubstitutionStep s) -> s.original().name())
                .thenComparingInt(s -> s.original().version()));
        return List.copyOf(steps);
    }

    /**
     * 递归回溯。对每个未解析依赖名称聚合全部已选制品的区间（取交集），
     * 候选版本从高到低；候选不可用时按策略替代，替代结果随分支记录/回滚。
     */
    private boolean solve(TreeMap<String, ArtifactVersion> chosen,
                          Map<String, Expansion> usedExpansions) {
        TreeMap<String, int[]> pending = new TreeMap<>();
        for (ArtifactVersion artifact : chosen.values()) {
            for (DependencyRange dep : artifact.dependencies()) {
                String originalName = dep.name();

                ArtifactVersion direct = chosen.get(originalName);
                if (direct != null) {
                    // 未替代节点：已选精确版本必须落在声明区间内。
                    if (direct.version() < dep.minimumVersion()
                            || direct.version() > dep.maximumVersion()) {
                        return false;
                    }
                    continue;
                }

                Expansion substituted = usedExpansions.get(originalName);
                if (substituted != null) {
                    // 该原名称已在另一路径上完成替代：本路径要求的原版本区间必须覆盖
                    // 已替代的原版本；否则此分支不相容（回溯尝试更早节点的其他版本，
                    // 全部分支失败时由上层整体 422）。
                    Coordinate replacedOriginal = usedOriginal.get(originalName);
                    if (replacedOriginal.version() < dep.minimumVersion()
                            || replacedOriginal.version() > dep.maximumVersion()) {
                        return false;
                    }
                    // 同一原版本：最终坐标唯一，视为已解析。
                    continue;
                }

                int[] range = pending.computeIfAbsent(originalName,
                        k -> new int[]{1, Integer.MAX_VALUE});
                range[0] = Math.max(range[0], dep.minimumVersion());
                range[1] = Math.min(range[1], dep.maximumVersion());
            }
        }

        if (pending.isEmpty()) {
            return true;
        }

        // 与既有解析器一致：固定选取字典序最小的未解析名称展开。
        Map.Entry<String, int[]> nextEntry = pending.firstEntry();
        String next = nextEntry.getKey();
        int lo = nextEntry.getValue()[0];
        int hi = nextEntry.getValue()[1];
        if (lo > hi) {
            return false;
        }

        List<ArtifactVersion> candidates = all.getOrDefault(next, List.of());
        for (ArtifactVersion candidate : candidates) {
                if (candidate.version() < lo || candidate.version() > hi) {
                    continue;
                }
                if (candidate.availableOn(platform)) {
                    if (!satisfiesChosen(candidate, chosen, usedExpansions)) {
                        continue;
                    }
                    chosen.put(next, candidate);
                    if (solve(chosen, usedExpansions)) {
                        return true;
                    }
                    chosen.remove(next);
                    continue;
                }

                // 选中版本撤回/平台不可用：确定性展开替代链。
                Coordinate original = new Coordinate(next, candidate.version());
                Expansion expansion = expansionFor(original);
                if (!expansion.available()) {
                    // 无可行替代：尝试该名称的更低版本候选。
                    continue;
                }
                Coordinate finalCoordinate = expansion.finalCoordinate();

                // 相同原节点在整张图中只能得到一个替代结果（防御：分支状态与全局观察双重校验）。
                Coordinate observed = observedFinalByName.get(next);
                if (observed != null && !observed.equals(finalCoordinate)) {
                    throw new SubstitutionConflictException(
                            "原节点 " + next + " 经两个路径得到不同替代结果："
                                    + observed.name() + ":" + observed.version()
                                    + " 与 " + finalCoordinate.name() + ":" + finalCoordinate.version());
                }

                ArtifactVersion replaced = mustFind(finalCoordinate);

                boolean newlyChosen = false;
                ArtifactVersion present = chosen.get(replaced.name());
                if (present != null) {
                    // 最终名称已在图中：必须同一精确版本，否则该原候选版本的替代分支不可行，
                    // 尝试下一原版本候选；全部不可行时整体 422。
                    if (present.version() != replaced.version()) {
                        continue;
                    }
                } else if (!satisfiesChosen(replaced, chosen, usedExpansions)) {
                    // 替代坐标声明的依赖与已选版本不相容：尝试下一原版本候选。
                    continue;
                } else {
                    chosen.put(replaced.name(), replaced);
                    newlyChosen = true;
                }

                usedExpansions.put(next, expansion);
                usedOriginal.put(next, original);
                observedFinalByName.put(next, finalCoordinate);
                boolean ok = solve(chosen, usedExpansions);
                if (ok) {
                    return true;
                }
                observedFinalByName.remove(next, finalCoordinate);
                usedOriginal.remove(next, original);
                usedExpansions.remove(next, expansion);
                if (newlyChosen) {
                    chosen.remove(replaced.name());
                }
            }
        return false;
    }

    /** 取原坐标的确定性替代链展开（带记忆）；结果可能不可用。 */
    private Expansion expansionFor(Coordinate original) {
        Expansion cached = expansions.get(original);
        if (cached != null) {
            return cached;
        }
        Expansion expansion = expandChain(original);
        expansions.put(original, expansion);
        return expansion;
    }

    /**
     * 对不可用原坐标展开替代链，直至末端可用坐标；全部候选不可行时返回不可用结果。
     * 多跳时返回的 hops 依次记录每一“跳”的原坐标、命中规则、候选拒绝原因与当跳选中坐标。
     */
    private Expansion expandChain(Coordinate original) {
        if (activeChain.contains(original)) {
            throw new SubstitutionConflictException("替代链形成坐标环：" + describeChain(original));
        }
        activeChain.push(original);
        try {
            List<RuleHit> hits = matchingRules(original);
            if (hits.isEmpty()) {
                return Expansion.unavailable();
            }
            if (hits.size() > 1) {
                throw new SubstitutionConflictException(
                        "原坐标 " + original.name() + ":" + original.version()
                                + " 同时命中 " + hits.size() + " 条同优先级替代规则，规则冲突");
            }
            SubstitutionRule rule = hits.get(0).rule();

            List<RejectedCandidate> rejected = new ArrayList<>();
            for (Coordinate alt : rule.alternatives()) {
                if (alt.name().equals(original.name()) && alt.version() == original.version()) {
                    rejected.add(new RejectedCandidate(alt.name(), alt.version(),
                            "替代坐标与原坐标相同"));
                    continue;
                }
                if (!artifactRowExists(alt.name(), alt.version())) {
                    rejected.add(new RejectedCandidate(alt.name(), alt.version(),
                            "替代制品版本不存在"));
                    continue;
                }
                if (activeChain.contains(alt)) {
                    // 替代链展开形成坐标环：策略结构性错误，硬性 422，不再尝试后续候选。
                    throw new SubstitutionConflictException(
                            "替代链形成坐标环：" + describeChain(alt));
                }
                ArtifactVersion altArtifact = findArtifact(alt.name(), alt.version());
                if (altArtifact != null && altArtifact.availableOn(platform)) {
                    Hop hop = new Hop(original, rule, rejected);
                    return Expansion.of(alt, List.of(hop));
                }
                // 替代坐标已登记但撤回/平台不可用：沿规则继续多跳展开，
                // 链上坐标环由被调方法入口（activeChain）检测并抛硬性冲突。
                Expansion deeper = expansionFor(alt);
                if (!deeper.available()) {
                    String unavailableReason = artifactRowExists(alt.name(), alt.version())
                            ? "替代坐标在目标平台不可用或已撤回，且无后续可行替代"
                            : "替代制品版本不存在";
                    rejected.add(new RejectedCandidate(alt.name(), alt.version(), unavailableReason));
                    continue;
                }
                Hop hop = new Hop(original, rule, rejected);
                List<Hop> hops = new ArrayList<>();
                hops.add(hop);
                hops.addAll(deeper.hops());
                return Expansion.of(deeper.finalCoordinate(), List.copyOf(hops));
            }
            return Expansion.unavailable();
        } finally {
            activeChain.pop();
        }
    }

    /** 名称+版本的制品行是否已登记，含已撤回版本。 */
    private boolean artifactRowExists(String name, int version) {
        List<ArtifactVersion> candidates = all.get(name);
        if (candidates == null) {
            return false;
        }
        return candidates.stream().anyMatch(a -> a.version() == version);
    }

    /** 找出对原坐标生效（平台一致、时刻已到、模式命中）的全部规则。 */
    private List<RuleHit> matchingRules(Coordinate coordinate) {
        List<RuleHit> hits = new ArrayList<>();
        for (SubstitutionRule rule : rules) {
            if (!rule.targetPlatform().equals(platform)) {
                continue;
            }
            if (now.isBefore(rule.effectiveAt())) {
                continue;
            }
            if (CoordinatePattern.parse(rule.sourcePattern()).matches(coordinate)) {
                hits.add(new RuleHit(rule));
            }
        }
        return hits;
    }

    private ArtifactVersion mustFind(Coordinate coordinate) {
        ArtifactVersion artifact = findArtifact(coordinate.name(), coordinate.version());
        if (artifact == null) {
            throw new SubstitutionConflictException(
                    "替代制品版本不存在或已撤回: " + coordinate.name() + ":" + coordinate.version());
        }
        return artifact;
    }

    private ArtifactVersion findArtifact(String name, int version) {
        List<ArtifactVersion> candidates = all.get(name);
        if (candidates == null) {
            return null;
        }
        return candidates.stream()
                .filter(a -> a.version() == version && !a.withdrawn())
                .findFirst().orElse(null);
    }

    private boolean satisfiesChosen(ArtifactVersion candidate,
                                    Map<String, ArtifactVersion> chosen,
                                    Map<String, Expansion> usedExpansions) {
        for (DependencyRange dep : candidate.dependencies()) {
            String depName = dep.name();
            if (chosen.containsKey(depName)) {
                // 依赖名称未替代、且同名节点已选：直接做区间校验。
                ArtifactVersion selected = chosen.get(depName);
                if (selected.version() < dep.minimumVersion() || selected.version() > dep.maximumVersion()) {
                    return false;
                }
                continue;
            }
            Expansion expansion = usedExpansions.get(depName);
            if (expansion == null) {
                continue;
            }
            // 依赖的是已替代原名称：本路径声明的原版本区间必须覆盖已替代的原版本，
            // 否则该分支不相容（穷尽回溯后由上层整体转 422）。
            Coordinate replacedOriginal = usedOriginal.get(depName);
            if (replacedOriginal.version() < dep.minimumVersion()
                    || replacedOriginal.version() > dep.maximumVersion()) {
                return false;
            }
        }
        return true;
    }

    private String describeChain(Coordinate closing) {
        StringBuilder sb = new StringBuilder(closing.name()).append(':').append(closing.version());
        for (Coordinate coordinate : activeChain) {
            sb.append(" <- ").append(coordinate.name()).append(':').append(coordinate.version());
        }
        return sb.toString();
    }

    /** 一跳替代：原坐标、命中规则及本跳候选拒绝原因（最终坐标由所属链展开结果给出）。 */
    private record Hop(Coordinate original, SubstitutionRule rule,
                       List<RejectedCandidate> rejected) {
    }

    /** 替代链展开结果：finalCoordinate 为 null 表示无可行替代。 */
    private record Expansion(Coordinate finalCoordinate, List<Hop> hops) {
        boolean available() {
            return finalCoordinate != null;
        }

        static Expansion of(Coordinate finalCoordinate, List<Hop> hops) {
            return new Expansion(finalCoordinate, hops);
        }

        static Expansion unavailable() {
            return new Expansion(null, List.of());
        }
    }

    private record RuleHit(SubstitutionRule rule) {
    }

    /** 替代策略冲突：整体锁定必须 422，不生成部分锁。 */
    public static class SubstitutionConflictException extends RuntimeException {
        public SubstitutionConflictException(String message) {
            super(message);
        }
    }
}
