package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 不确定度分量。每个分量为非负大小，同一测量同一预算版本内名称唯一。
 *
 * @param id            分量 ID（自增）
 * @param measurementId 所属测量记录 ID
 * @param budgetVersion 预算版本号，同一测量内从 1 起，整批替换自增
 * @param name          分量名称（同一预算版本内唯一）
 * @param magnitude     分量大小，非负，与被测量同单位，最多 6 位小数
 * @param createdAt     该预算版本创建时间（UTC）
 */
public record UncertaintyComponent(
        long id,
        long measurementId,
        long budgetVersion,
        String name,
        BigDecimal magnitude,
        Instant createdAt) {
}
