package com.example.starter.calibration.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 不确定度预算明细与诊断响应。
 * combinedUncertainty 为实际值，threshold 为要求值，margin 为差额（threshold - combined）。
 *
 * @param measurementKey      业务测量键
 * @param version             当前测量版本号
 * @param formula             合成不确定度计算口径标识
 * @param components          当前分量集合（按名称升序）
 * @param combinedUncertainty 合成不确定度（RSS 平方和开根，HALF_UP 4 位小数）
 * @param threshold           当前仪器阈值；未登记时为 null
 * @param margin              差额 threshold - combined；未登记阈值时为 null
 * @param thresholdSatisfied  合成不确定度是否不超过阈值；未登记阈值时为 false
 * @param blockingReasons     诊断：阻碍放行的不确定度原因（THRESHOLD_MISSING / UNCERTAINTY_EXCEEDED）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BudgetResponse(String measurementKey, int version, String formula,
                             List<ComponentView> components, String combinedUncertainty,
                             ThresholdView threshold, String margin, boolean thresholdSatisfied,
                             List<String> blockingReasons) {
}
