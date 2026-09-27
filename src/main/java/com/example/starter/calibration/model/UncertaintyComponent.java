package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量不确定度分量。同一测量内分量名称唯一，数值非负。
 *
 * @param id             分量 ID（自增）
 * @param measurementId  测量记录 ID
 * @param componentName  分量名称
 * @param componentValue 分量数值，非负，最多 6 位小数
 * @param createdAt      登记时间（UTC）
 */
public record UncertaintyComponent(
        long id,
        long measurementId,
        String componentName,
        BigDecimal componentValue,
        Instant createdAt) {
}
