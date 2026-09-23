package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 放行批次只读详情。
 *
 * @param batchId    批次 ID
 * @param releasedBy 放行人
 * @param status     批次状态：RELEASED / REVIEW_REQUIRED
 * @param createdAt  批次创建时间（UTC）
 * @param reviewedAt 复核驳回时间（UTC）；未复核为 null
 * @param positions  逐位置测量（按位置升序）
 */
public record BatchResponse(
        String batchId,
        String releasedBy,
        String status,
        Instant createdAt,
        Instant reviewedAt,
        List<PositionMeasurement> positions) {

    /**
     * 批次中单个位置的测量快照。
     *
     * @param position       位置（从 1 开始）
     * @param measurementId  测量记录 ID
     * @param measurementKey 测量业务键
     * @param version        版本
     * @param status         测量状态
     * @param passed         是否合格
     * @param computedValue  未舍入计算值（十进制字符串）
     */
    public record PositionMeasurement(
            int position,
            long measurementId,
            String measurementKey,
            int version,
            String status,
            boolean passed,
            String computedValue) {
    }
}
