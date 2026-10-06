package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 闭合导线成果响应：冻结的输入顺序、引用版本、闭合差与逐边修正结果。
 *
 * @param traverseId     成果唯一标识
 * @param requestId      生成该成果的请求标识
 * @param operator       操作者标识
 * @param legCount       边数
 * @param closureDx      X 向闭合差（米，十进制字符串）
 * @param closureDy      Y 向闭合差（米，十进制字符串）
 * @param totalWeight    各边权重之和（米，十进制字符串）
 * @param generatedAtUtc 成果生成时刻（UTC，ISO-8601）
 * @param legs           按输入顺序的逐边结果
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TraverseResponse(
        String traverseId,
        String requestId,
        String operator,
        int legCount,
        String closureDx,
        String closureDy,
        String totalWeight,
        Instant generatedAtUtc,
        List<LegView> legs) {

    /**
     * 成果的一条边视图。
     *
     * @param legIndex      边序号，从 1 开始
     * @param observationId 引用的观测记录标识
     * @param version       生成时核对的观测版本号
     * @param dx            原始位移 X 分量（米）
     * @param dy            原始位移 Y 分量（米）
     * @param w             边长权重（米）
     * @param adjustedDx    修正后位移 X 分量（米，六位小数）
     * @param adjustedDy    修正后位移 Y 分量（米，六位小数）
     */
    public record LegView(
            int legIndex,
            String observationId,
            int version,
            String dx,
            String dy,
            String w,
            String adjustedDx,
            String adjustedDy) {
    }

    /**
     * 由成果记录构造响应。
     */
    public static TraverseResponse of(TraverseRecord record) {
        return new TraverseResponse(
                record.traverseId(),
                record.requestId(),
                record.operator(),
                record.legCount(),
                record.closureDx(),
                record.closureDy(),
                record.totalWeight(),
                record.generatedAtUtc(),
                record.legs().stream()
                        .map(leg -> new LegView(leg.legIndex(), leg.observationId(), leg.observationVersion(),
                                leg.dx(), leg.dy(), leg.w(), leg.adjustedDx(), leg.adjustedDy()))
                        .toList());
    }
}
