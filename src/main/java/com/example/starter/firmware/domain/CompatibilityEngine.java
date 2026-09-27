package com.example.starter.firmware.domain;

import com.example.starter.firmware.error.ApiException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模块依赖图纯逻辑：依赖闭包（同一模块取各路径上最高最低版本）与环检测。
 * 边方向：moduleName -&gt; dependsOn。
 */
public final class CompatibilityEngine {

    private CompatibilityEngine() {
    }

    /**
     * 计算完整依赖集合：从固件直接需求出发，沿依赖边传递展开；
     * 同一模块被多条路径要求时，取最高的最低版本。
     *
     * @param roots     固件直接需求（模块名 -&gt; 最低版本）
     * @param adjacency 依赖图邻接表（发起模块 -&gt; (被依赖模块 -&gt; 最低版本)）
     * @return 完整需求，模块名 -&gt; 最低版本（取路径最大值）
     */
    public static Map<String, ModuleVersion> closure(Map<String, ModuleVersion> roots,
                                                     Map<String, Map<String, ModuleVersion>> adjacency) {
        Map<String, ModuleVersion> required = new HashMap<>(roots);
        // 待展开队列用 LinkedHashSet 去重，环图也不会死循环（环在登记阶段已拒绝，这里仍防御）。
        LinkedHashSet<String> queue = new LinkedHashSet<>(roots.keySet());
        while (!queue.isEmpty()) {
            String module = queue.iterator().next();
            queue.remove(module);
            Map<String, ModuleVersion> outgoing = adjacency.get(module);
            if (outgoing == null) {
                continue;
            }
            for (Map.Entry<String, ModuleVersion> edge : outgoing.entrySet()) {
                ModuleVersion previous = required.get(edge.getKey());
                if (previous == null || edge.getValue().compareTo(previous) > 0) {
                    required.put(edge.getKey(), edge.getValue());
                    queue.add(edge.getKey());
                }
            }
        }
        return required;
    }

    /**
     * 校验完整后态依赖图不得成环；成环抛 422。自依赖也视为环。
     */
    public static void assertAcyclic(Map<String, Map<String, ModuleVersion>> adjacency) {
        // 0=未访问，1=在当前 DFS 路径上，2=已完成
        Map<String, Integer> color = new HashMap<>();
        List<String> path = new ArrayList<>();
        for (String node : adjacency.keySet()) {
            if (color.getOrDefault(node, 0) == 0) {
                visit(node, adjacency, color, path);
            }
        }
    }

    private static void visit(String node, Map<String, Map<String, ModuleVersion>> adjacency,
                              Map<String, Integer> color, List<String> path) {
        color.put(node, 1);
        path.add(node);
        Map<String, ModuleVersion> outgoing = adjacency.get(node);
        if (outgoing != null) {
            for (String next : outgoing.keySet()) {
                int nextColor = color.getOrDefault(next, 0);
                if (nextColor == 1) {
                    int begin = path.indexOf(next);
                    List<String> cycle = new ArrayList<>(path.subList(begin, path.size()));
                    cycle.add(next);
                    throw ApiException.unprocessable("MODULE_DEPENDENCY_CYCLE",
                            "模块依赖形成环: " + String.join(" -> ", cycle));
                }
                if (nextColor == 0) {
                    visit(next, adjacency, color, path);
                }
            }
        }
        path.remove(path.size() - 1);
        color.put(node, 2);
    }

    /**
     * 邻接表中加入一条边，同一起点终点保留更高最低版本。
     */
    public static void putEdge(Map<String, Map<String, ModuleVersion>> adjacency,
                               String from, String to, ModuleVersion version) {
        adjacency.computeIfAbsent(from, k -> new HashMap<>())
                .merge(to, version, (old, incoming) -> incoming.compareTo(old) > 0 ? incoming : old);
        adjacency.computeIfAbsent(to, k -> new HashMap<>());
    }
}
