package com.example.starter.excursion;

/**
 * 越限区间分析落库记录：对应 excursion_analysis 表的一行，生成后不可变。
 *
 * @param analysisId         分析结果唯一标识
 * @param requestId          生成该分析的请求标识
 * @param stationId          被分析观测的共同站号
 * @param thresholdH         阈值 H（十进制字符串原文）
 * @param consecutiveMinutes 连续分钟数 N
 * @param referenceCount     引用观测条数
 * @param referencesJson     引用快照（JSON 数组原文）
 * @param intervalsJson      越限区间（JSON 数组原文）
 * @param operator           提交分析的操作者标识
 * @param createdAtUtc       分析生成时刻（UTC，ISO-8601）
 */
public record AnalysisRecord(
        String analysisId,
        String requestId,
        String stationId,
        String thresholdH,
        int consecutiveMinutes,
        int referenceCount,
        String referencesJson,
        String intervalsJson,
        String operator,
        String createdAtUtc) {
}
