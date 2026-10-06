package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 闭合导线成果记录：对应 traverse_result 主表一行及其全部 traverse_leg 边行，生成后不可变。
 *
 * @param traverseId     成果唯一标识（服务端生成）
 * @param requestId      生成该成果的请求标识
 * @param operator       操作者标识
 * @param legCount       边数（3 至 10）
 * @param closureDx      X 向闭合差（米，十进制原文）
 * @param closureDy      Y 向闭合差（米，十进制原文）
 * @param totalWeight    各边权重之和（米，十进制原文）
 * @param generatedAtUtc 成果生成时刻（UTC）
 * @param legs           按输入顺序冻结的逐边记录
 */
public record TraverseRecord(
        String traverseId,
        String requestId,
        String operator,
        int legCount,
        String closureDx,
        String closureDy,
        String totalWeight,
        Instant generatedAtUtc,
        List<Leg> legs) {

    /**
     * 成果的一条边：冻结引用的观测版本、原始位移与修正后位移。
     *
     * @param legIndex           边序号，从 1 开始，按输入顺序
     * @param observationId      引用的观测记录标识
     * @param observationVersion 生成时核对通过的观测当前版本号
     * @param dx                 原始位移 X 分量（米，十进制原文）
     * @param dy                 原始位移 Y 分量（米，十进制原文）
     * @param w                  边长权重（米，正数十进制原文）
     * @param adjustedDx         修正后位移 X 分量（米，六位小数十进制原文）
     * @param adjustedDy         修正后位移 Y 分量（米，六位小数十进制原文）
     */
    public record Leg(
            int legIndex,
            String observationId,
            int observationVersion,
            String dx,
            String dy,
            String w,
            String adjustedDx,
            String adjustedDy) {
    }
}
