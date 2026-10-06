package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 越限分析响应：返回冻结的引用集合、阈值、区间结果及源更新标记。
 *
 * @param analysisId         分析记录唯一标识
 * @param stationId          引用观测的共同站号
 * @param threshold          越限阈值 H（十进制原文）
 * @param consecutiveMinutes 连续越限分钟数 N
 * @param references         冻结的引用集合快照（按时刻升序）
 * @param intervals          冻结的越限区间结果（左闭右开，按开始时刻升序）
 * @param sourceUpdated      查询时标记：任一引用源观测的当前版本已偏离引用版本（修订或删除）则为 true
 * @param operator           提交分析的操作者标识
 * @param createdAtUtc       分析生成时刻（UTC，ISO-8601）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExcursionAnalysisResponse(
        String analysisId,
        String stationId,
        String threshold,
        int consecutiveMinutes,
        List<AnalysisReference> references,
        List<ExcursionInterval> intervals,
        boolean sourceUpdated,
        String operator,
        Instant createdAtUtc) {
}
