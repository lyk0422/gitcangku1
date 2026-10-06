package com.example.starter.observation;

import java.time.Instant;

/**
 * 不可变越限分析记录：对应 excursion_analysis 表的一行，生成后永不修改。
 *
 * @param analysisId         分析记录唯一标识（服务端生成）
 * @param requestId          生成该分析的请求标识
 * @param operator           提交分析的操作者标识
 * @param stationId          引用观测的共同站号
 * @param threshold          越限阈值 H（十进制原文）
 * @param consecutiveMinutes 连续越限分钟数 N（2 至 5）
 * @param referencesJson     冻结的引用集合快照（JSON 数组原文，按时刻升序）
 * @param intervalsJson      冻结的越限区间结果（JSON 数组原文，左闭右开）
 * @param createdAtUtc       分析生成时刻（UTC）
 */
public record ExcursionAnalysisRecord(
        String analysisId,
        String requestId,
        String operator,
        String stationId,
        String threshold,
        int consecutiveMinutes,
        String referencesJson,
        String intervalsJson,
        Instant createdAtUtc) {
}
