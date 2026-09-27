package com.example.starter.blind.dto;

import java.util.List;

/**
 * 实验退役/配额诊断视图（只读）：代码级配额与区组级当前名额，均为查询时刻实际值。
 *
 * @param experimentId 实验编号
 * @param codes        各处理代码配额诊断，按处理代码升序
 * @param blocks       各区组当前名额，按区组号升序
 */
public record RetirementDiagnosticsView(
        String experimentId,
        List<CodeQuotaView> codes,
        List<BlockAvailabilityView> blocks
) {
}
