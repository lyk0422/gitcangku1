package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 不确定度预算响应：当前最新预算版本、分量明细与按公开 RSS 口径计算的合成不确定度。
 *
 * @param measurementKey      测量业务键
 * @param measurementVersion  测量版本号
 * @param budgetVersion       预算版本号（从 1 开始；尚未登记预算为 0）
 * @param updatedBy           该预算版本操作者；尚无预算为 null
 * @param combinedUncertainty 合成不确定度 sqrt(Σx²)，HALF_UP 4 位小数字符串
 * @param formula             计算口径标识 RSS_ROOT_SUM_OF_SQUARES
 * @param components          分量明细，按名称升序
 * @param updatedAt           该预算版本写入时间（UTC）；尚无预算为 null
 */
public record BudgetResponse(
        String measurementKey,
        int measurementVersion,
        int budgetVersion,
        String updatedBy,
        String combinedUncertainty,
        String formula,
        List<ComponentResponse> components,
        Instant updatedAt) {
}
