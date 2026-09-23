package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 测量明细响应。computedValue 为未舍入精确值；displayValue 为 HALF_UP 保留 4 位的显示值；
 * usable 表示“当前可用”（该版本为最新版本、已放行且证书未撤销）。
 *
 * @param id             测量版本记录 ID
 * @param measurementKey 业务测量键
 * @param revision       修订版本号，从 1 开始
 * @param instrumentId   仪器 ID
 * @param measuredAt     测量时刻（UTC）
 * @param reading        原始读数（十进制字符串）
 * @param lowerLimit     合格下限（十进制字符串）
 * @param upperLimit     合格上限（十进制字符串）
 * @param submittedBy    原提交人
 * @param certificateId  匹配到的证书 ID
 * @param computedValue  未舍入计算值（十进制字符串）
 * @param displayValue   显示值，HALF_UP 4 位小数（十进制字符串）
 * @param passed         是否合格（基于未舍入值，含端点）
 * @param status         状态：PENDING / RELEASED
 * @param usable         当前是否可用（最新版本、已放行且证书未撤销）
 * @param latest         该版本是否为当前最新版本
 * @param revisionReason 修订原因；第 1 版为 null
 * @param revisedBy      修订操作人；第 1 版为 null
 * @param revisedAt      修订时间（UTC）；第 1 版为 null
 * @param createdAt      该版本创建时间（UTC）
 * @param releases       该版本的放行历史
 */
public record MeasurementResponse(
        long id,
        String measurementKey,
        int revision,
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
        boolean latest,
        String revisionReason,
        String revisedBy,
        Instant revisedAt,
        Instant createdAt,
        List<ReleaseRecordResponse> releases) {
}
