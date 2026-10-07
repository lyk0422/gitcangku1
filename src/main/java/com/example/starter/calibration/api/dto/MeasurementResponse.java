package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 测量明细响应。computedValue 为证书阶段未舍入值；correctedValue 为最终值精确表示
 * （普通模式等于 computedValue；参考漂移模式为约分后的分数或整数）；
 * displayValue 为最终值 HALF_UP 保留 4 位的显示值；
 * usable 表示“当前可用”（已放行且证书与相关档案均未撤销）。
 *
 * @param id             测量记录 ID
 * @param measurementKey 业务测量键
 * @param mode           测量模式：STANDARD / DRIFT_REF
 * @param instrumentId   仪器 ID
 * @param measuredAt     测量时刻（UTC）
 * @param reading        原始读数（十进制字符串）
 * @param lowerLimit     合格下限（十进制字符串）
 * @param upperLimit     合格上限（十进制字符串）
 * @param submittedBy    提交人
 * @param certificateId  匹配到的证书 ID
 * @param computedValue  证书阶段未舍入值（十进制字符串）
 * @param refArchiveId0  参考漂移模式：下端档案 ID；普通模式为 null
 * @param refArchiveId1  参考漂移模式：上端档案 ID；普通模式为 null
 * @param correctedValue 最终值精确表示（分母为 1 输出整数，否则 "分子/分母"）
 * @param displayValue   显示值，HALF_UP 4 位小数（十进制字符串）
 * @param passed         是否合格（基于未舍入最终值，含端点）
 * @param status         状态：PENDING / RELEASED
 * @param usable         当前是否可用（已放行且全部证据未撤销）
 * @param createdAt      提交时间（UTC）
 * @param releases       放行历史
 */
public record MeasurementResponse(
        long id,
        String measurementKey,
        String mode,
        String instrumentId,
        Instant measuredAt,
        String reading,
        String lowerLimit,
        String upperLimit,
        String submittedBy,
        long certificateId,
        String computedValue,
        Long refArchiveId0,
        Long refArchiveId1,
        String correctedValue,
        String displayValue,
        boolean passed,
        String status,
        boolean usable,
        Instant createdAt,
        List<ReleaseRecordResponse> releases) {
}
