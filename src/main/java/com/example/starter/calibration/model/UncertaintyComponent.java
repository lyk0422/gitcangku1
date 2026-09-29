package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 不确定度分量。一条测量（具体版本行）可登记多个非负分量，名称在同一测量同一预算版本内唯一。
 *
 * @param id            分量 ID（自增）
 * @param measurementId 所属测量记录（版本行）ID
 * @param budgetVersion 预算版本号，从 1 开始；整体替换递增，历史版本只增不改
 * @param name          分量名称，非空；同一测量同一预算版本内唯一
 * @param magnitude     分量大小，非负（含 0），最多 6 位小数
 * @param createdAt     该预算版本写入时间（UTC）
 */
public record UncertaintyComponent(
        long id,
        long measurementId,
        int budgetVersion,
        String name,
        BigDecimal magnitude,
        Instant createdAt) {
}
