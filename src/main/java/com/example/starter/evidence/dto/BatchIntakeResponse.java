package com.example.starter.evidence.dto;

import com.example.starter.evidence.EvidenceStatus;
import com.example.starter.evidence.ReviewStatus;
import com.example.starter.evidence.WeightStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * 批量入库响应：逐件创建结果 + 整体差异统计。
 *
 * @param intakeKey       批次幂等键
 * @param custodianId     保管人
 * @param totalCount      本批证物总数
 * @param matchedCount    重量一致（MATCHED）件数
 * @param discrepantCount 差异超5%（DISCREPANT）件数
 * @param items           逐件创建结果，顺序与请求清单一致
 */
public record BatchIntakeResponse(
        String intakeKey,
        String custodianId,
        int totalCount,
        int matchedCount,
        int discrepantCount,
        List<Item> items) {

    /**
     * 逐件创建结果。
     *
     * @param evidenceKey    证物业务键
     * @param status         证物状态，批量入库后恒为 SEALED
     * @param declaredWeight 申报重量（单位千克）
     * @param measuredWeight 实测重量（单位千克）
     * @param weightStatus   重量核对结果：MATCHED / DISCREPANT
     * @param reviewStatus   复核状态：DISCREPANT 为 PENDING，其余为 NONE
     */
    public record Item(
            String evidenceKey,
            EvidenceStatus status,
            BigDecimal declaredWeight,
            BigDecimal measuredWeight,
            WeightStatus weightStatus,
            ReviewStatus reviewStatus) {
    }
}
