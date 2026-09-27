package com.example.starter.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 替代版本有向图校验：边为「同制品内 被弃用版本 -> 替代版本」。
 *
 * <p>替代版本必须与被弃用版本属于同一制品，因此不同制品名称之间不存在边，
 * 环检测按制品名称分别进行。批量设置弃用窗口时，必须基于合并后的最终完整
 * 替代图（已存在的有效窗口与本次待写入窗口合并）校验；只要最终图存在任一环
 * （含自环）即整体拒绝。
 */
public final class DeprecationGraph {

    private DeprecationGraph() {
    }

    /**
     * 定位到的环：所属制品名称与环上版本号序列（首尾同点，如 [1,2,1]）。
     */
    public record Cycle(String name, List<Integer> versions) {
    }

    /**
     * 查找最终替代图中的一个环，按制品名称字典序返回首个制品的环。
     *
     * @param edges 制品名称 -> （被弃用版本 -> 替代版本），仅含有有效弃用窗口的版本
     * @return 环（所属名称与版本序列）；无环返回 null
     */
    public static Cycle firstCycle(Map<String, Map<Integer, Integer>> edges) {
        for (Map.Entry<String, Map<Integer, Integer>> byName : new java.util.TreeMap<>(edges).entrySet()) {
            List<Integer> cycle = firstCycleInName(byName.getValue());
            if (cycle != null) {
                return new Cycle(byName.getKey(), cycle);
            }
        }
        return null;
    }

    /**
     * 在单个制品的替代映射中用三色 DFS 查找环；映射是 out-degree 至多 1 的函数图。
     */
    private static List<Integer> firstCycleInName(Map<Integer, Integer> replacementOf) {
        // 0=未访问，1=在当前递归栈，2=已完成
        java.util.Map<Integer, Integer> color = new java.util.TreeMap<>();
        for (int from : new java.util.TreeSet<>(replacementOf.keySet())) {
            if (color.getOrDefault(from, 0) == 0) {
                List<Integer> path = new ArrayList<>();
                if (dfs(from, replacementOf, color, path)) {
                    return path;
                }
            }
        }
        return null;
    }

    private static boolean dfs(int version, Map<Integer, Integer> replacementOf,
                               Map<Integer, Integer> color, List<Integer> path) {
        color.put(version, 1);
        path.add(version);
        Integer next = replacementOf.get(version);
        if (next != null) {
            int nextColor = color.getOrDefault(next, 0);
            if (nextColor == 1) {
                // 回到当前路径上的点，构成环；截断到环起点并补上闭合点。
                int start = path.indexOf(next);
                List<Integer> cycle = new ArrayList<>(path.subList(start, path.size()));
                cycle.add(next);
                path.clear();
                path.addAll(cycle);
                return true;
            }
            if (nextColor == 0 && dfs(next, replacementOf, color, path)) {
                return true;
            }
        }
        color.put(version, 2);
        path.remove(path.size() - 1);
        return false;
    }
}
