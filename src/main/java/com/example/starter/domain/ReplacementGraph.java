package com.example.starter.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 替代关系图校验：弃用窗口构成“被弃用版本 -&gt; 替代版本”的有向图，
 * 每个节点出度至多为 1，不得存在任何环（含自环）。
 *
 * <p>服务层在写入前对“完整后态”（已有 ACTIVE 窗口与本次批量调整合并后的图）
 * 统一校验，任一失败则整批不写入。
 */
public final class ReplacementGraph {

    private ReplacementGraph() {
    }

    /** 一条替代边：deprecatedArtifactId -&gt; replacementArtifactId。 */
    public record Edge(long deprecatedArtifactId, long replacementArtifactId) {
    }

    /**
     * 在给定边集合中查找一个环。
     *
     * @param edges 完整后态的全部替代边
     * @return 环上节点 ID（升序排列，自环返回单元素列表）；无环返回空列表
     */
    public static List<Long> findCycle(List<Edge> edges) {
        Map<Long, Long> next = new LinkedHashMap<>();
        for (Edge edge : edges) {
            Long previous = next.put(edge.deprecatedArtifactId(), edge.replacementArtifactId());
            if (previous != null && previous != edge.replacementArtifactId()) {
                throw new IllegalArgumentException("同一制品版本存在多条替代边: "
                        + edge.deprecatedArtifactId());
            }
        }

        List<Long> deterministicOrder = new ArrayList<>(next.keySet());
        Collections.sort(deterministicOrder);

        // 0=未访问，1=当前 DFS 路径上，2=已完成；从编号最小的节点开始，保证结论稳定。
        Map<Long, Integer> mark = new LinkedHashMap<>();
        for (Long start : deterministicOrder) {
            if (mark.getOrDefault(start, 0) != 0) {
                continue;
            }
            List<Long> path = new ArrayList<>();
            Long node = start;
            while (node != null) {
                int state = mark.getOrDefault(node, 0);
                if (state == 1) {
                    int begin = path.indexOf(node);
                    List<Long> cycle = new ArrayList<>(path.subList(begin, path.size()));
                    Collections.sort(cycle);
                    return cycle;
                }
                if (state == 2) {
                    break;
                }
                mark.put(node, 1);
                path.add(node);
                node = next.get(node);
            }
            for (Long visited : path) {
                mark.put(visited, 2);
            }
        }
        return List.of();
    }
}
