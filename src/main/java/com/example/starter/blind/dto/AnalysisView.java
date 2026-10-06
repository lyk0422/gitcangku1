package com.example.starter.blind.dto;

/**
 * 置换分析响应视图：仅含匿名统计，绝不包含个体结果值、参与者列表或处理映射。
 *
 * @param analysisId       分析记录编号
 * @param experimentId     实验编号
 * @param version          冻结引用的结果版本
 * @param participantCount 匿名人数：引用参与者总数
 * @param groupACount      匿名人数：处理A人数
 * @param groupBCount      匿名人数：处理B人数
 * @param k                统计量大于等于观察值的组合数
 * @param n                同A人数全部分组的总组合数
 * @param p                精确置换概率，"k/n" 原样比值字符串，不做浮点近似
 * @param createdAt        分析时间，Unix 毫秒，UTC
 */
public record AnalysisView(
        String analysisId,
        String experimentId,
        int version,
        int participantCount,
        int groupACount,
        int groupBCount,
        long k,
        long n,
        String p,
        long createdAt
) {
}
