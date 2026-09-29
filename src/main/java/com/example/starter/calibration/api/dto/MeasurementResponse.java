package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 测量明细响应（当前最新版本）。computedValue 为未舍入精确值；displayValue 为 HALF_UP 保留 4 位的显示值；
 * usable 表示“当前可用”（最新版本已放行且证书未撤销）。同时携带当前不确定度预算与合成结果。
 *
 * @param id                  测量记录 ID
 * @param measurementKey      业务测量键
 * @param version             测量版本号（当前最新）
 * @param instrumentId        仪器 ID
 * @param measuredAt          测量时刻（UTC）
 * @param reading             原始读数（十进制字符串）
 * @param lowerLimit          合格下限（十进制字符串）
 * @param upperLimit          合格上限（十进制字符串）
 * @param submittedBy         提交人
 * @param certificateId       匹配到的证书 ID
 * @param computedValue       未舍入计算值（十进制字符串）
 * @param displayValue        显示值，HALF_UP 4 位小数（十进制字符串）
 * @param passed              是否合格（偏差判定，基于未舍入值，含端点）
 * @param status              状态：PENDING / RELEASED / SUPERSEDED
 * @param usable              当前是否可用（最新版本已放行且证书未撤销）
 * @param createdAt           提交时间（UTC）
 * @param releases            放行历史
 * @param budgetVersion       当前预算版本号（无分量预算为 0）
 * @param combinedUncertainty 当前预算合成不确定度（RSS，4 位小数字符串；无分量为 "0.0000"）
 * @param formula             计算口径标识 RSS_ROOT_SUM_OF_SQUARES
 * @param components          当前预算分量明细，按名称升序
 */
public record MeasurementResponse(
        long id,
        String measurementKey,
        int version,
        String instrumentId,
        Instant measuredAt,
        String reading,
        String lowerLimit,
        String upperLimit,
        String submittedBy,
        long certificateId,
        String computedValue,
        String displayValue,
        boolean passed,
        String status,
        boolean usable,
        Instant createdAt,
        List<ReleaseRecordResponse> releases,
        int budgetVersion,
        String combinedUncertainty,
        String formula,
        List<ComponentResponse> components) {
}
