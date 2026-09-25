package com.example.starter.evidence;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 重量差异明细实体，对应 weight_discrepancy 表。仅 DISCREPANT 项写入，不可变。
 *
 * @param id             主键
 * @param intakeKey      所属批次键
 * @param evidenceKey    差异证物业务键
 * @param declaredWeight 申报重量，单位千克(kg)
 * @param measuredWeight 实测重量，单位千克(kg)
 * @param deviation      绝对差值 |实测-申报|，单位千克(kg)
 * @param deviationRatio 差异比例 |实测-申报|/申报
 * @param createdAt      记录时间（Asia/Shanghai）
 */
public record WeightDiscrepancy(
        Long id,
        String intakeKey,
        String evidenceKey,
        BigDecimal declaredWeight,
        BigDecimal measuredWeight,
        BigDecimal deviation,
        BigDecimal deviationRatio,
        LocalDateTime createdAt) {
}
