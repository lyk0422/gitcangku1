package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 测量明细响应。computedValue 为未舍入精确值；displayValue 为 HALF_UP 保留 4 位的显示值；
 * expandedUncertainty 为扩展不确定度（k=2）；usable 表示“当前可用”（已放行且证书未撤销）。
 *
 * @param id                   测量记录 ID
 * @param measurementKey       业务测量键
 * @param instrumentId         仪器/被测对象 ID
 * @param standardId           当前版本引用的标准器 ID
 * @param measuredAt           测量时刻（UTC）
 * @param reading              原始读数（十进制字符串）
 * @param lowerLimit           合格下限（十进制字符串）
 * @param upperLimit           合格上限（十进制字符串）
 * @param submittedBy          提交人
 * @param certificateId        当前版本引用的证书 ID
 * @param certificateVersion   当前版本引用的证书版本
 * @param versionNo            当前生效测量版本号
 * @param computedValue        未舍入计算值（十进制字符串）
 * @param displayValue         显示值，HALF_UP 4 位小数（十进制字符串）
 * @param expandedUncertainty  扩展不确定度 k=2（十进制字符串）
 * @param uncertaintyVersion   不确定度版本
 * @param referenceKey         当前版本 referenceKey 指纹
 * @param passed               是否合格（基于未舍入值，含端点）
 * @param status               状态：PENDING / RELEASED
 * @param usable               当前是否可用（已放行且证书未撤销）
 * @param createdAt            首次提交时间（UTC）
 * @param releases             放行历史（含放行时固化的测量版本号）
 */
public record MeasurementResponse(
        long id,
        String measurementKey,
        String instrumentId,
        String standardId,
        Instant measuredAt,
        String reading,
        String lowerLimit,
        String upperLimit,
        String submittedBy,
        long certificateId,
        String certificateVersion,
        int versionNo,
        String computedValue,
        String displayValue,
        String expandedUncertainty,
        String uncertaintyVersion,
        String referenceKey,
        boolean passed,
        String status,
        boolean usable,
        Instant createdAt,
        List<ReleaseRecordResponse> releases) {
}
