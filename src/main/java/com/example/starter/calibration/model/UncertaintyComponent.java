package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量不确定度分量（当前集合中的一条）。
 *
 * @param id             分量 ID（自增）
 * @param measurementId  所属测量记录 ID
 * @param componentName  分量名称，同一测量内唯一
 * @param componentValue 分量值，非负十进制，最多 6 位小数
 * @param createdAt      分量写入时间（UTC）
 */
public record UncertaintyComponent(
        long id,
        long measurementId,
        String componentName,
        BigDecimal componentValue,
        Instant createdAt) {
}
