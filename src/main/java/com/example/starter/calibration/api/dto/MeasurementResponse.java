package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 测量明细响应。computedValue 为未舍入精确值（DRIFT 模式可能为 "分子/分母" 分数形式）；
 * displayValue 为 HALF_UP 保留 4 位的显示值；
 * usable 表示“当前可用”（已放行且证书与所引用的漂移档案均未撤销）。
 *
 * @param id             测量记录 ID
 * @param measurementKey 业务测量键
 * @param instrumentId   仪器 ID
 * @param measuredAt     测量时刻（UTC）
 * @param reading        原始读数（十进制字符串）
 * @param lowerLimit     合格下限（十进制字符串）
 * @param upperLimit     合格上限（十进制字符串）
 * @param submittedBy    提交人
 * @param certificateId  匹配到的证书 ID
 * @param mode           测量模式：STANDARD / DRIFT
 * @param profileT0Id    DRIFT 模式：下界漂移档案 ID；STANDARD 为 null
 * @param profileT1Id    DRIFT 模式：上界漂移档案 ID；STANDARD 为 null
 * @param computedValue  未舍入精确计算值（十进制字符串或分数字符串）
 * @param displayValue   显示值，HALF_UP 4 位小数（十进制字符串）
 * @param passed         是否合格（基于未舍入精确值，含端点）
 * @param status         状态：PENDING / RELEASED
 * @param usable         当前是否可用（已放行且全部证据未撤销）
 * @param createdAt      提交时间（UTC）
 * @param releases       放行历史
 */
public record MeasurementResponse(
        long id,
        String measurementKey,
        String instrumentId,
        Instant measuredAt,
        String reading,
        String lowerLimit,
        String upperLimit,
        String submittedBy,
        long certificateId,
        String mode,
        Long profileT0Id,
        Long profileT1Id,
        String computedValue,
        String displayValue,
        boolean passed,
        String status,
        boolean usable,
        Instant createdAt,
        List<ReleaseRecordResponse> releases) {
}
