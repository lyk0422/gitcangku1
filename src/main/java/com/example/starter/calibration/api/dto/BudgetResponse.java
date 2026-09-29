package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 不确定度预算版本完整响应（明细/历史通用）。
 * actual=合成不确定度实际值，required=阈值要求值，difference=required−actual（余量，负为超出）。
 *
 * @param id                  预算版本 ID
 * @param measurementKey      测量键
 * @param budgetVersion       测量内预算版本号，从 1 起
 * @param calcMethod          合成口径标识：RSS_V1
 * @param combinedUncertainty 合成不确定度，四位小数字符串（公开返回口径）
 * @param thresholdId         固化的阈值版本行 ID
 * @param thresholdVersion    固化的阈值版本号
 * @param thresholdValue      固化的阈值上限值（十进制字符串）
 * @param withinThreshold     合成不确定度是否不超过固化阈值（含端点）
 * @param actual              实际值：合成不确定度，按公开口径四位小数
 * @param required            要求值：固化阈值上限原值（最多 6 位小数，去尾零）
 * @param difference          差额（余量）= 要求值 − 未舍入实际值（12 位小数，去尾零）；负数表示超出量
 * @param createdBy           预算登记/修订人
 * @param createdAt           预算版本创建时间（UTC，微秒精度）
 * @param components          分量明细，按提交序号稳定升序
 */
public record BudgetResponse(
        long id,
        String measurementKey,
        int budgetVersion,
        String calcMethod,
        String combinedUncertainty,
        long thresholdId,
        int thresholdVersion,
        String thresholdValue,
        boolean withinThreshold,
        String actual,
        String required,
        String difference,
        String createdBy,
        Instant createdAt,
        List<ComponentResponse> components) {
}
