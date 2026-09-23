package com.example.starter.consent;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 委托图计算：在“主体＋用途＋代次”范围内基于当前有效且未到期的边做最短路径计算与写入校验。
 *
 * <p>本类不访问数据库，所有结论基于调用方在事务内锁定的边快照，保证校验结论与提交顺序一致。
 * 最长允许 5 条边（主体 → 处理方最多 5 跳）。
 */
@Component
public class DelegationGraph {

    /** 委托链允许的最大层数（边数）。 */
    static final int MAX_DEPTH = 5;

    /**
     * 邻接表：fromKey 保持插入顺序，避免最短路径在等价路径间不确定。
     */
    private Map<String, List<DelegationRepository.DelegationRow>> adjacency(List<DelegationRepository.DelegationRow> edges,
                                                                             Instant now) {
        Map<String, List<DelegationRepository.DelegationRow>> graph = new LinkedHashMap<>();
        for (DelegationRepository.DelegationRow edge : edges) {
            if (edge.status() == DelegationStatus.ACTIVE && edge.expiresAt().isAfter(now)) {
                graph.computeIfAbsent(edge.fromKey(), k -> new ArrayList<>()).add(edge);
            }
        }
        return graph;
    }

    /**
     * BFS 计算主体到目标处理方的最短有效路径（边数最少）。
     *
     * @return 有序边列表；target 等于 subject 时返回空列表；不可达或超过最大层数返回 null
     */
    List<DelegationRepository.DelegationRow> shortestPath(List<DelegationRepository.DelegationRow> edges,
                                                          String subjectKey, String target, Instant now) {
        if (subjectKey.equals(target)) {
            return List.of();
        }
        Map<String, List<DelegationRepository.DelegationRow>> graph = adjacency(edges, now);

        // predecessor：到达某节点所经过的边，BFS 首次到达即最短路径
        Map<String, DelegationRepository.DelegationRow> predecessor = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(subjectKey);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            for (DelegationRepository.DelegationRow edge : graph.getOrDefault(node, List.of())) {
                if (predecessor.containsKey(edge.toKey()) || edge.toKey().equals(subjectKey)) {
                    continue;
                }
                predecessor.put(edge.toKey(), edge);
                if (edge.toKey().equals(target)) {
                    return reconstruct(predecessor, subjectKey, target);
                }
                queue.add(edge.toKey());
            }
        }
        return null;
    }

    /**
     * 目标节点距主体的最短层数；不可达返回 -1，主体自身返回 0。
     */
    int shortestDistance(List<DelegationRepository.DelegationRow> edges,
                         String subjectKey, String target, Instant now) {
        List<DelegationRepository.DelegationRow> path = shortestPath(edges, subjectKey, target, now);
        return path == null ? -1 : path.size();
    }

    /**
     * 若加入一条 from→to 新边，是否会形成环：新边成环当且仅当当前已存在从 to 回到 from 的有效路径，
     * 或 to 已是主体/链上节点（等价地，从 to 可达 from 或主体）。
     */
    boolean wouldCreateCycle(List<DelegationRepository.DelegationRow> edges,
                             String subjectKey, String fromKey, String toKey, Instant now) {
        if (toKey.equals(subjectKey) || toKey.equals(fromKey)) {
            return true;
        }
        Map<String, List<DelegationRepository.DelegationRow>> graph = adjacency(edges, now);
        java.util.Set<String> visited = new java.util.HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(toKey);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            if (node.equals(fromKey) || node.equals(subjectKey)) {
                return true;
            }
            if (!visited.add(node)) {
                continue;
            }
            for (DelegationRepository.DelegationRow edge : graph.getOrDefault(node, List.of())) {
                queue.add(edge.toKey());
            }
        }
        return false;
    }

    private List<DelegationRepository.DelegationRow> reconstruct(
            Map<String, DelegationRepository.DelegationRow> predecessor, String subjectKey, String target) {
        List<DelegationRepository.DelegationRow> path = new ArrayList<>();
        String node = target;
        while (!node.equals(subjectKey)) {
            DelegationRepository.DelegationRow edge = predecessor.get(node);
            if (edge == null) {
                return null;
            }
            path.add(edge);
            node = edge.fromKey();
        }
        java.util.Collections.reverse(path);
        return path.size() <= MAX_DEPTH ? path : null;
    }
}
