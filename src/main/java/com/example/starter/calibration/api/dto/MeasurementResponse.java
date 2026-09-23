package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 测量明细响应。computedValue 为未舍入精确值；displayValue 为 HALF_UP 保留 4 位的显示值；
 * usable 表示“当前可用”（已放行且证书未撤销）。
 *
 * @param id             测量记录 ID
 * @param measurementKey 业务测量键
 * @param version        版本号：原始提交为 0，每次后继修订 +1
 * @param instrumentId   仪器 ID
 * @param measuredAt     测量时刻（UTC）
 * @param reading        原始读数（十进制字符串）
 * @param lowerLimit     合格下限（十进制字符串）
 * @param upperLimit     合格上限（十进制字符串）
 * @param submittedBy    提交人
 * @param certificateId  匹配到的证书 ID
 * @param computedValue  未舍入计算值（十进制字符串）
 * @param displayValue   显示值，HALF_UP 4 位小数（十进制字符串）
 * @param passed         是否合格（基于未舍入值，含端点）
 * @param status         状态：PENDING / RELEASED / REJECTED
 * @param note           测量说明；未填写为 null
 * @param usable         当前是否可用（已放行、证书未撤销且所在批次生效中）
 * @param createdAt      提交时间（UTC）
 * @param releases       放行历史
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
        String note,
        boolean usable,
        Instant createdAt,
        List<ReleaseRecordResponse> releases) {
}
