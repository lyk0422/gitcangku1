package com.example.starter.evidence.dto;

import com.example.starter.evidence.ReviewStatus;
import com.example.starter.evidence.WeightStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 批次入库清单与差异复核状态视图。
 *
 * @param intakeKey       批次幂等键
 * @param custodianId     保管人
 * @param totalCount      本批证物总数
 * @param matchedCount    重量一致件数
 * @param discrepantCount 差异超5%件数
 * @param pendingReviewCount 仍待复核件数
 * @param items           逐件清单与复核状态
 */
public record BatchView(
        String intakeKey,
        String custodianId,
        int totalCount,
        int matchedCount,
        int discrepantCount,
        int pendingReviewCount,
        List<Item> items) {

    /**
     * 批次清单单项。
     *
     * @param evidenceKey    证物业务键
     * @param description    证物描述
     * @param declaredWeight 申报重量（单位千克）
     * @param measuredWeight 实测重量（单位千克）
     * @param weightStatus   重量核对结果：MATCHED / DISCREPANT
     * @param reviewStatus   复核状态：NONE / PENDING / RESOLVED
     * @param reviewNote     复核说明；未复核或无复核记录时为 NULL
     * @param createdAt      入库时间（Asia/Shanghai）
     */
    public record Item(
            String evidenceKey,
            String description,
            BigDecimal declaredWeight,
            BigDecimal measuredWeight,
            WeightStatus weightStatus,
            ReviewStatus reviewStatus,
            String reviewNote,
            LocalDateTime createdAt) {
    }
}
