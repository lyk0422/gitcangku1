package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 测量明细响应（某一具体版本）。computedValue 为未舍入精确值；displayValue 为 HALF_UP 保留 4 位的显示值；
 * usable 表示“当前可用”（该版本是最新版本、已放行且证书未撤销）。
 *
 * @param id             测量记录 ID
 * @param measurementKey 业务测量键
 * @param revision       修订版本号；首次提交为 1
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
 * @param latestRevision 该键当前最新修订号
 * @param revisionReason 修订原因；第 1 版为 null
 * @param requestId      修订幂等请求 ID；第 1 版为 null
 * @param revisedBy      修订提交人；第 1 版为 null
 * @param revisedAt      修订提交时间（UTC）；第 1 版为 null
 * @param createdAt      本版本创建时间（UTC）
 * @param releases       本版本放行历史
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
        int latestRevision,
        String revisionReason,
        String requestId,
        String revisedBy,
        Instant revisedAt,
        Instant createdAt,
        List<ReleaseRecordResponse> releases) {
}
