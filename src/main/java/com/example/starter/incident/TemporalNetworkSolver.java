package com.example.starter.incident;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 整案排程可行性求解器：把任务时间窗与有向约束统一建模为差分约束系统
 * （Simple Temporal Network），用 Bellman-Ford 判负环，整体判定可行时间赋值
 * 是否存在，不做逐边局部检查。
 * 变量为各任务开始时刻 s_i（相对提案基准零点的分钟偏移），约束形式：
 *   s_i ≥ earliestStartOffset_i；s_i + duration_i ≤ latestFinishOffset_i；
 *   L ≤ s_B + 0 − (s_A + duration_A) ≤ U（B 开始减 A 完成 ∈ [L, U]）。
 */
public final class TemporalNetworkSolver {

    private TemporalNetworkSolver() {
    }

    /** 任务时间窗：持续分钟数为正整数，偏移为相对基准零点的非负分钟。 */
    public record TaskTiming(String taskKey, long durationMinutes,
                             long earliestStartOffset, long latestFinishOffset) {
    }

    /** 有向约束：toTaskKey 开始 − fromTaskKey 完成 ∈ [lowerBound, upperBound]。 */
    public record TemporalConstraint(String fromTaskKey, String toTaskKey,
                                     long lowerBound, long upperBound) {
    }

    private record Edge(int from, int to, long weight) {
    }

    /**
     * 判定是否存在同时满足全部任务时间窗与约束的时间赋值。
     * 建模：x_v − x_u ≤ w 对应边 u→v 权 w；节点 0 为固定零点（x_0 = 0）。
     * 可行当且仅当约束图无负权环（Bellman-Ford，全零初始化等价于超级源点）。
     */
    public static boolean isFeasible(List<TaskTiming> tasks, List<TemporalConstraint> constraints) {
        int n = tasks.size();
        int nodes = n + 1;
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) {
            index.put(tasks.get(i).taskKey(), i);
        }
        List<Edge> edges = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            TaskTiming t = tasks.get(i);
            // s_i ≤ latestFinish − duration
            edges.add(new Edge(0, i + 1, t.latestFinishOffset() - t.durationMinutes()));
            // s_i ≥ earliestStart ⇔ x_0 − x_i ≤ −earliestStart
            edges.add(new Edge(i + 1, 0, -t.earliestStartOffset()));
        }
        for (TemporalConstraint c : constraints) {
            int a = index.get(c.fromTaskKey());
            int b = index.get(c.toTaskKey());
            long durationA = tasks.get(a).durationMinutes();
            // s_B − s_A ≤ U + duration_A
            edges.add(new Edge(a + 1, b + 1, c.upperBound() + durationA));
            // s_B − s_A ≥ L + duration_A ⇔ s_A − s_B ≤ −(L + duration_A)
            edges.add(new Edge(b + 1, a + 1, -(c.lowerBound() + durationA)));
        }
        long[] dist = new long[nodes];
        for (int i = 0; i < nodes; i++) {
            boolean relaxed = false;
            for (Edge e : edges) {
                long candidate = dist[e.from()] + e.weight();
                if (candidate < dist[e.to()]) {
                    dist[e.to()] = candidate;
                    relaxed = true;
                }
            }
            if (!relaxed) {
                return true;
            }
            if (i == nodes - 1) {
                return false;
            }
        }
        return true;
    }
}
