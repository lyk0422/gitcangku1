package com.example.starter.blind.dto;

import java.util.List;

/**
 * 实验容量诊断视图：区组可用名额与代码配额的实时重算结果，只读。
 *
 * @param experimentId 实验编号
 * @param blockCount   当前区组总数
 * @param blocks       各区组名额统计，按区组号升序
 * @param codes        各处理代码配额统计，按代码升序
 */
public record CapacityView(
        String experimentId,
        int blockCount,
        List<BlockCapacityView> blocks,
        List<CodeCapacityView> codes
) {
}
