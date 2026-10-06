package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 闭合导线成果响应：返回冻结的输入顺序、版本、闭合差与逐边修正结果。
 *
 * @param resultId       成果唯一标识
 * @param requestId      生成该成果的请求标识
 * @param operator       操作者标识
 * @param edgeCount      边数
 * @param closureDx      X 向闭合差（米，十进制原文）
 * @param closureDy      Y 向闭合差（米，十进制原文）
 * @param totalW         边长总和（米）
 * @param generatedAtUtc 成果生成时刻（UTC，ISO-8601）
 * @param edges          逐边冻结结果，按输入顺序排列
 */
public record TraverseResponse(
        String resultId,
        String requestId,
        String operator,
        int edgeCount,
        String closureDx,
        String closureDy,
        String totalW,
        Instant generatedAtUtc,
        List<EdgeView> edges) {

    /**
     * 成果边视图：冻结的引用版本、原始位移与修正位移。
     *
     * @param edgeIndex          边序号，从 1 开始
     * @param observationId      引用的观测记录标识
     * @param observationVersion 生成时核对通过的观测当前版本号
     * @param dx                 原始位移 x 分量（米）
     * @param dy                 原始位移 y 分量（米）
     * @param w                  边长（米，正数）
     * @param correctedDx        修正后位移 x 分量（米，6 位小数）
     * @param correctedDy        修正后位移 y 分量（米，6 位小数）
     */
    public record EdgeView(
            int edgeIndex,
            String observationId,
            int observationVersion,
            String dx,
            String dy,
            String w,
            String correctedDx,
            String correctedDy) {
    }

    /**
     * 由成果记录与边记录构造响应。
     */
    public static TraverseResponse of(TraverseResult result, List<TraverseEdge> edges) {
        List<EdgeView> views = edges.stream()
                .map(edge -> new EdgeView(edge.edgeIndex(), edge.observationId(), edge.observationVersion(),
                        edge.dx(), edge.dy(), edge.w(), edge.correctedDx(), edge.correctedDy()))
                .toList();
        return new TraverseResponse(result.resultId(), result.requestId(), result.operator(),
                result.edgeCount(), result.closureDx(), result.closureDy(), result.totalW(),
                result.generatedAtUtc(), views);
    }
}
