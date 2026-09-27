package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 不确定度预算响应（当前完整后态）。
 *
 * @param measurementKey      业务测量键
 * @param budgetVersion       当前预算版本号（初始登记为 1，整批替换自增）
 * @param components          全部分量，按名称升序稳定排序
 * @param combinedUncertainty 合成不确定度（RSS，HALF_UP 4 位小数字符串）
 * @param calculationMethod   合成口径标识
 * @param measurementVersion  当前测量版本号
 * @param updatedAt           本预算版本创建时间（UTC）
 */
public record BudgetResponse(
        String measurementKey,
        long budgetVersion,
        List<UncertaintyComponentResponse> components,
        String combinedUncertainty,
        String calculationMethod,
        long measurementVersion,
        Instant updatedAt) {
}
