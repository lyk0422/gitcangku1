package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 不确定度预算版本头。每次整体替换追加一个新版本（分量为空也保留版本痕迹），预算版本只增不改。
 *
 * @param id            预算版本头 ID（自增）
 * @param measurementId 所属测量记录（版本行）ID
 * @param budgetVersion 预算版本号，从 1 开始
 * @param updatedBy     本次预算修改操作者
 * @param createdAt     该预算版本写入时间（UTC）
 */
public record UncertaintyBudget(
        long id,
        long measurementId,
        int budgetVersion,
        String updatedBy,
        Instant createdAt) {
}
