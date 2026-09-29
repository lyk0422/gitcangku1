package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 不确定度分量。隶属于某一不可变预算版本；同一预算内名称唯一，值非负。
 *
 * @param id      分量 ID（自增）
 * @param budgetId 所属预算版本 ID
 * @param name    分量名称，预算内唯一，区分大小写
 * @param value   分量标准不确定度，非负，最多 6 位小数
 * @param ordinal 提交序号，从 0 起；明细与历史按 (ordinal, id) 稳定排序
 * @param createdAt 分量固化时间（UTC，与预算版本一致）
 */
public record UncertaintyComponent(
        long id,
        long budgetId,
        String name,
        BigDecimal value,
        int ordinal,
        Instant createdAt) {
}
