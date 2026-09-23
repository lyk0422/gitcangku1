package com.example.starter.consent;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.example.starter.consent.DelegationRepository.DelegationRow;

/**
 * 委托有向图的纯内存计算：基于同一事务快照读出的边集合判断深度、环与最短有效路径。
 *
 * <p>边方向为 委托方 -&gt; 受托处理方；根为授权主体。路径“有效”要求每边 status=ACTIVE
 * 且在评估时刻未到期。链最长 5 层（主体 -&gt; p1 为第 1 层，至 p5）。
 */
@Component
public class DelegationGraph {

    /** 委托链最大层数（主体到最末处理方之间的边数上限）。 */
    public static final int MAX_DEPTH = 5;

    /**
     * 校验在加入候选边 newEdge 后图仍无环，且候选边不会使最深链超过 5 层。
     *
     * @param activeEdges 当前快照中全部 ACTIVE 边（不含候选边；可能已到期，到期只影响有效性不影响结构）
     * @param subjectKey  授权主体（图根）
     * @param newEdge     待加入的新边
     * @return 校验失败原因；空表示通过
     */
    public Optional<String> validateNewEdge(List<DelegationRow> activeEdges, String subjectKey,
                                            DelegationRow newEdge) {
        Map<String, List<String>> outgoing = new HashMap<>();
        for (DelegationRow edge : activeEdges) {
            outgoing.computeIfAbsent(edge.delegatorKey(), k -> new ArrayList<>()).add(edge.processorKey());
        }

        // 自环即环；委托方必须已在图中（主体或已有处理方），否则形成跨 subject 的孤立链
        if (newEdge.delegatorKey().equals(newEdge.processorKey())) {
            return Optional.of("委托不能形成自环");
        }
        if (!newEdge.delegatorKey().equals(subjectKey) && !isReachable(outgoing, subjectKey, newEdge.delegatorKey())) {
            return Optional.of("委托方不在主体授权链内，禁止跨 subject/purpose/epoch 委托");
        }

        // 加入候选边后无环：从 processorKey 沿出边不可再到达 delegatorKey（含）
        outgoing.computeIfAbsent(newEdge.delegatorKey(), k -> new ArrayList<>()).add(newEdge.processorKey());
        if (reaches(outgoing, newEdge.processorKey(), newEdge.delegatorKey())) {
            return Optional.of("委托不能形成环");
        }

        // 深度：从主体到 newEdge.processorKey 的最长简单链不超过 5 层。
        // 此时图无环，按“主体 -> ... -> delegator 最长距离 + 1”计算。
        int delegatorDepth = newEdge.delegatorKey().equals(subjectKey)
                ? 0 : longestDistance(outgoing, subjectKey, newEdge.delegatorKey());
        if (delegatorDepth < 0) {
            return Optional.of("委托方不在主体授权链内，禁止跨 subject/purpose/epoch 委托");
        }
        if (delegatorDepth + 1 > MAX_DEPTH) {
            return Optional.of("委托链最长不得超过 " + MAX_DEPTH + " 层");
        }
        return Optional.empty();
    }

    /**
     * 在评估时刻 now 有效的边（status=ACTIVE 且未到期），按有向邻接表返回。
     */
    public Map<String, List<DelegationRow>> effectiveOutgoing(List<DelegationRow> activeEdges, Instant now) {
        Map<String, List<DelegationRow>> outgoing = new HashMap<>();
        for (DelegationRow edge : activeEdges) {
            if (edge.status() == DelegationStatus.ACTIVE && !edge.expiresAt().isBefore(now)) {
                outgoing.computeIfAbsent(edge.delegatorKey(), k -> new ArrayList<>()).add(edge);
            }
        }
        return outgoing;
    }

    /**
     * 计算从主体到 callerKey 的最短有效路径；不存在返回空。
     *
     * @return 路径上的有序边（主体 -&gt; ... -&gt; callerKey）
     */
    public List<DelegationRow> shortestPath(List<DelegationRow> activeEdges, String subjectKey,
                                            String callerKey, Instant now) {
        Map<String, List<DelegationRow>> outgoing = effectiveOutgoing(activeEdges, now);
        if (callerKey.equals(subjectKey)) {
            return List.of();
        }
        Deque<String> queue = new ArrayDeque<>();
        Map<String, DelegationRow> predecessor = new HashMap<>();
        Set<String> visited = new HashSet<>();
        queue.add(subjectKey);
        visited.add(subjectKey);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            for (DelegationRow edge : outgoing.getOrDefault(node, List.of())) {
                if (visited.add(edge.processorKey())) {
                    predecessor.put(edge.processorKey(), edge);
                    if (edge.processorKey().equals(callerKey)) {
                        return reconstruct(predecessor, callerKey);
                    }
                    queue.add(edge.processorKey());
                }
            }
        }
        return null;
    }

    /**
     * 当前从主体可达的全部有效处理方键（含各条最短链信息见 {@link #shortestPath}）。
     */
    public Set<String> reachableProcessors(List<DelegationRow> activeEdges, String subjectKey, Instant now) {
        Map<String, List<DelegationRow>> outgoing = effectiveOutgoing(activeEdges, now);
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(subjectKey);
        visited.add(subjectKey);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            for (DelegationRow edge : outgoing.getOrDefault(node, List.of())) {
                if (visited.add(edge.processorKey())) {
                    queue.add(edge.processorKey());
                }
            }
        }
        visited.remove(subjectKey);
        return visited;
    }

    private List<DelegationRow> reconstruct(Map<String, DelegationRow> predecessor, String callerKey) {
        List<DelegationRow> path = new ArrayList<>();
        String node = callerKey;
        while (predecessor.containsKey(node)) {
            DelegationRow edge = predecessor.get(node);
            path.add(edge);
            node = edge.delegatorKey();
        }
        java.util.Collections.reverse(path);
        return path;
    }

    private boolean isReachable(Map<String, List<String>> outgoing, String from, String target) {
        return from.equals(target) || reaches(outgoing, from, target);
    }

    private boolean reaches(Map<String, List<String>> outgoing, String from, String target) {
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(from);
        visited.add(from);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            for (String next : outgoing.getOrDefault(node, List.of())) {
                if (next.equals(target)) {
                    return true;
                }
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return false;
    }

    /**
     * 无环图中从根到目标的最长路径边数；目标不可达返回 -1。
     */
    private int longestDistance(Map<String, List<String>> outgoing, String root, String target) {
        Map<String, Integer> memo = new HashMap<>();
        return dfsLongest(outgoing, root, target, memo, new HashSet<>());
    }

    private int dfsLongest(Map<String, List<String>> outgoing, String node, String target,
                           Map<String, Integer> memo, Set<String> onStack) {
        if (node.equals(target)) {
            return 0;
        }
        if (memo.containsKey(node)) {
            return memo.get(node);
        }
        if (!onStack.add(node)) {
            return Integer.MIN_VALUE;
        }
        int best = Integer.MIN_VALUE;
        for (String next : outgoing.getOrDefault(node, List.of())) {
            int sub = dfsLongest(outgoing, next, target, memo, onStack);
            if (sub != Integer.MIN_VALUE) {
                best = Math.max(best, sub + 1);
            }
        }
        onStack.remove(node);
        memo.put(node, best);
        return best;
    }
}
