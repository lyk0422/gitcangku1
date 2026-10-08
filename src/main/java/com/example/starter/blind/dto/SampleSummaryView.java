package com.example.starter.blind.dto;

/**
 * 闭组结果汇总（REVIEWER 视图）：仅份数与总和，无逐样本关联。
 * 仅统计未退组样本的已录入结果。
 *
 * @param experimentId 实验编号
 * @param status       实验状态：CLOSED
 * @param resultCount  有效结果份数（已排除退组样本）
 * @param resultSum    有效结果总和
 */
public record SampleSummaryView(
        String experimentId,
        String status,
        long resultCount,
        long resultSum
) {
}
