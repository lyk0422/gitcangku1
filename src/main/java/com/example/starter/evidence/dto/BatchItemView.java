package com.example.starter.evidence.dto;

import com.example.starter.evidence.ReviewStatus;
import com.example.starter.evidence.WeightCheck;

import java.math.BigDecimal;

/**
 * 批量入库清单项视图（创建结果与批次清单查询共用）。
 *
 * @param evidenceKey    证物业务键
 * @param description    申报描述
 * @param declaredWeight 申报重量，单位千克(kg)
 * @param measuredWeight 实测重量，单位千克(kg)
 * @param weightCheck    重量核对结果：MATCHED / DISCREPANT
 * @param reviewStatus   复核状态：PENDING_REVIEW 待复核 / REVIEWED 已复核；MATCHED 项为 null
 */
public record BatchItemView(
        String evidenceKey,
        String description,
        BigDecimal declaredWeight,
        BigDecimal measuredWeight,
        WeightCheck weightCheck,
        ReviewStatus reviewStatus) {
}
