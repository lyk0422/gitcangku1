package com.example.starter.evidence;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 重量差异明细，对应 weight_discrepancy 表。只追加、不可变。
 *
 * @param id             主键
 * @param intakeKey      所属批量入库批次键
 * @param evidenceKey    关联证物业务键
 * @param declaredWeight 申报重量（千克，两位小数）
 * @param measuredWeight 实测重量（千克）
 * @param diffPercent    差异绝对值占申报重量的百分比
 * @param createdAt      记录时间（Asia/Shanghai）
 */
public record WeightDiscrepancy(
        Long id,
        String intakeKey,
        String evidenceKey,
        BigDecimal declaredWeight,
        BigDecimal measuredWeight,
        BigDecimal diffPercent,
        LocalDateTime createdAt) {
}
