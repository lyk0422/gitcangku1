package com.example.starter.race.api;

/**
 * 单个中立区间视图。
 *
 * @param legIndex      中立区间序号，从1连续递增
 * @param startPosition 起点检查点顺序；0 表示发枪零点
 * @param endPosition   终点检查点顺序
 */
public record NeutralLegResponse(
        int legIndex,
        int startPosition,
        int endPosition
) {
}
