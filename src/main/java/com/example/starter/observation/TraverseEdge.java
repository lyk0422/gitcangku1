package com.example.starter.observation;

/**
 * 闭合导线成果边记录：对应 traverse_result_edge 表的一行，冻结引用版本、原始位移与修正位移。
 *
 * @param resultId           成果唯一标识
 * @param edgeIndex          边序号，从 1 开始，按输入顺序排列
 * @param observationId      该边引用的观测记录标识
 * @param observationVersion 生成时核对通过的观测当前版本号
 * @param dx                 原始位移 x 分量（米，十进制原文）
 * @param dy                 原始位移 y 分量（米，十进制原文）
 * @param w                  边长（米，正数，十进制原文）
 * @param correctedDx        修正后位移 x 分量（米，6 位小数）
 * @param correctedDy        修正后位移 y 分量（米，6 位小数）
 */
public record TraverseEdge(
        String resultId,
        int edgeIndex,
        String observationId,
        int observationVersion,
        String dx,
        String dy,
        String w,
        String correctedDx,
        String correctedDy) {
}
