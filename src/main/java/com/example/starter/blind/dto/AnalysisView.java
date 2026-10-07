package com.example.starter.blind.dto;

/**
 * 受限统计分析视图：只含精确 k/n 与匿名人数；
 * 严禁包含个体结果值、参与者清单或处理映射。
 *
 * @param analysisId       分析编号
 * @param experimentId     实验编号
 * @param resultVersion    冻结引用的结果版本号
 * @param participantCount 引用的参与者人数（4~8）
 * @param groupACount      匿名人数：处理 A 组人数
 * @param groupBCount      匿名人数：处理 B 组人数
 * @param favorableCount   精确置换分子 k：统计量大于等于观察值的组合数
 * @param totalCount       精确置换分母 n：保持 A 人数不变的全部组合数
 * @param createdAt        分析时间，Unix 毫秒，UTC
 */
public record AnalysisView(
        String analysisId,
        String experimentId,
        int resultVersion,
        int participantCount,
        int groupACount,
        int groupBCount,
        long favorableCount,
        long totalCount,
        long createdAt
) {
}
