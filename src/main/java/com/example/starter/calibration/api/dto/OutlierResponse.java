package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 离群记录明细响应。封存检测时的目标值、允许偏差、读数值与实际偏差；
 * reviews 按复核时间升序，未裁决时 verdict/resolvedAt 为 null。
 *
 * @param id               离群记录 ID
 * @param batchKey         所属批次键
 * @param readingId        离群读数 ID
 * @param batchRevision    检测时的批次测量修订版本
 * @param certificateId    检测时绑定的证书 ID（证书版本）
 * @param targetValue      封存的目标值（十进制字符串）
 * @param allowedDeviation 封存的允许偏差，含端点（十进制字符串）
 * @param readingValue     封存的读数值（十进制字符串）
 * @param deviation        实际偏差 |读数-目标值|（十进制字符串）
 * @param submittedBy      读数提交人
 * @param status           状态：PENDING / ACCEPTED / REJECTED
 * @param verdict          有效裁决：ACCEPT/REJECT；未裁决为 null
 * @param acceptCount      有效 ACCEPT 票数（实际值）
 * @param rejectCount      有效 REJECT 票数（实际值）
 * @param requiredQuorum   形成裁决所需的相同结论票数（要求值，恒为 2）
 * @param createdAt        检测时间（UTC）
 * @param resolvedAt       裁决时间（UTC）；未裁决为 null
 * @param reviews          复核记录（按复核时间升序）
 */
public record OutlierResponse(
        long id,
        String batchKey,
        long readingId,
        int batchRevision,
        long certificateId,
        String targetValue,
        String allowedDeviation,
        String readingValue,
        String deviation,
        String submittedBy,
        String status,
        String verdict,
        int acceptCount,
        int rejectCount,
        int requiredQuorum,
        Instant createdAt,
        Instant resolvedAt,
        List<ReviewItem> reviews) {

    /**
     * 复核记录项。
     *
     * @param reviewer      复核人
     * @param decision      复核结论：ACCEPT / REJECT
     * @param reason        复核理由；未填为 null
     * @param status        复核状态：EFFECTIVE / STALE
     * @param batchRevision 复核引用的批次测量修订版本
     * @param certificateId 复核引用的证书 ID（证书版本）
     * @param createdAt     复核时间（UTC）
     */
    public record ReviewItem(
            String reviewer,
            String decision,
            String reason,
            String status,
            int batchRevision,
            long certificateId,
            Instant createdAt) {
    }
}
