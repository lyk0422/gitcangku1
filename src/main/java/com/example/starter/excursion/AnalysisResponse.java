package com.example.starter.excursion;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 越限区间分析响应：生成后冻结；sourceUpdated 在查询时按引用版本与当前版本实时比对得出。
 *
 * @param analysisId         分析结果唯一标识
 * @param stationId          被分析观测的共同站号
 * @param threshold          阈值 H（十进制字符串原文）
 * @param consecutiveMinutes 连续分钟数 N
 * @param references         引用快照（按时刻升序），生成时冻结
 * @param intervals          越限区间列表（左闭右开，按开始时刻升序），可能为空
 * @param sourceUpdated      查询时标记：任一引用观测已有更新版本（含删除）时为 true
 * @param operator           提交分析的操作者标识
 * @param createdAtUtc       分析生成时刻（UTC，ISO-8601）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisResponse(
        String analysisId,
        String stationId,
        String threshold,
        int consecutiveMinutes,
        List<ReferenceSnapshot> references,
        List<ExcursionIntervals.Interval> intervals,
        boolean sourceUpdated,
        String operator,
        String createdAtUtc) {

    /**
     * 分析冻结的单条引用快照。
     *
     * @param observationId 观测记录唯一标识
     * @param version       分析时引用的版本号
     * @param minuteUtc     该版本的 UTC 整分钟时刻
     * @param reading       该版本的读数（十进制字符串原文）
     */
    public record ReferenceSnapshot(String observationId, int version, String minuteUtc, String reading) {
    }
}
