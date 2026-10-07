package com.example.starter.water;

import com.example.starter.water.dto.Dtos.PathSegment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 有向渠网门禁的纯函数工具：路径形状校验、有向环检测。
 * 路径有序，不得规范化成无序集合；环检测作用于所有已批准路径的边并集。
 */
final class CanalSupport {

    static final int MAX_PATH_SEGMENTS = 8;

    private CanalSupport() {
    }

    /**
     * 校验路径形状：1 至 8 条渠段、首尾相接、节点不重复。
     * 非法时抛 400；合法时返回按顺序的节点序列（长度 = 渠段数 + 1）。
     */
    static List<String> validatePath(List<PathSegment> path) {
        if (path == null || path.isEmpty() || path.size() > MAX_PATH_SEGMENTS) {
            throw ApiException.badRequest("PATH_LENGTH_INVALID", "路径须包含 1 至 " + MAX_PATH_SEGMENTS + " 条渠段");
        }
        for (PathSegment segment : path) {
            if (segment == null) {
                throw ApiException.badRequest("PATH_SEGMENT_INVALID", "路径渠段不能为空");
            }
            WaterService.requireKey("path.from", segment.from());
            WaterService.requireKey("path.to", segment.to());
        }
        for (int i = 1; i < path.size(); i++) {
            if (!path.get(i).from().equals(path.get(i - 1).to())) {
                throw ApiException.badRequest("PATH_BROKEN",
                        "路径渠段须首尾相接：第 " + (i + 1) + " 段起点不等于第 " + i + " 段终点");
            }
        }
        List<String> nodes = new ArrayList<>(path.size() + 1);
        nodes.add(path.get(0).from());
        for (PathSegment segment : path) {
            nodes.add(segment.to());
        }
        if (new LinkedHashSet<>(nodes).size() != nodes.size()) {
            throw ApiException.badRequest("PATH_NODE_REPEATED", "路径节点不得重复");
        }
        return nodes;
    }

    /** 有向边集（可含重复边）是否含环，DFS 三色标记。 */
    static boolean hasCycle(List<PathSegment> edges) {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (PathSegment edge : edges) {
            adjacency.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge.to());
        }
        Set<String> visiting = new HashSet<>();
        Set<String> done = new HashSet<>();
        for (String node : adjacency.keySet()) {
            if (!done.contains(node) && dfs(node, adjacency, visiting, done)) {
                return true;
            }
        }
        return false;
    }

    private static boolean dfs(String node, Map<String, List<String>> adjacency,
                               Set<String> visiting, Set<String> done) {
        if (!visiting.add(node)) {
            return true;
        }
        for (String next : adjacency.getOrDefault(node, List.of())) {
            if (!done.contains(next) && dfs(next, adjacency, visiting, done)) {
                return true;
            }
        }
        visiting.remove(node);
        done.add(node);
        return false;
    }
}
