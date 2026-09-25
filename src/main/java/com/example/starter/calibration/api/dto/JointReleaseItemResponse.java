package com.example.starter.calibration.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 联合放行明细中的单条测量快照（只读稳定排序，按 measurementKey 字典序）。
 *
 * @param measurementKey 测量业务键
 * @param instrumentId   仪器 ID
 * @param certificateId  本次放行使用的校准证书 ID
 * @param computedValue  放行时固化的未舍入计算值（十进制字符串）
 * @param releasedBy     放行人
 * @param releasedAt     放行时间（UTC）
 */
public record JointReleaseItemResponse(
        String measurementKey,
        String instrumentId,
        long certificateId,
        String computedValue,
        String releasedBy,
        Instant releasedAt) {
}
