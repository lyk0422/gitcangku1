package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 测量的不确定度预算历史响应：只增不改，按版本号稳定升序。
 *
 * @param measurementKey 测量键
 * @param instrumentId   仪器 ID
 * @param measurementVersion 测量当前修订版本号（每次预算修订加 1）
 * @param currentBudgetId    当前生效预算版本 ID；从未登记为 null
 * @param versions       全部预算版本（含分量），按版本号升序
 */
public record BudgetHistoryResponse(
        String measurementKey,
        String instrumentId,
        long measurementVersion,
        Long currentBudgetId,
        List<BudgetResponse> versions) {
}
