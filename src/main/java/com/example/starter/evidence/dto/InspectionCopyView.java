package com.example.starter.evidence.dto;

import com.example.starter.evidence.CopyStatus;

import java.time.LocalDateTime;

/**
 * 阅检副本元数据视图。签发与重放签发、回收均只返回该元数据，不返回合成文本。
 *
 * @param copyKey     副本业务键（不可重复副本 ID）
 * @param evidenceKey 关联证物业务键
 * @param reviewerId  阅检人
 * @param digest      签发时冻结的原件数字摘要
 * @param watermark   签发时冻结的水印，含副本 ID 与阅检人
 * @param validFrom   UTC 生效时刻（含）
 * @param validUntil  UTC 失效时刻（不含）
 * @param status      副本状态
 * @param revokedAt   回收时刻（UTC）；null 表示未回收
 * @param createdAt   签发时间（Asia/Shanghai）
 */
public record InspectionCopyView(
        String copyKey,
        String evidenceKey,
        String reviewerId,
        String digest,
        String watermark,
        LocalDateTime validFrom,
        LocalDateTime validUntil,
        CopyStatus status,
        LocalDateTime revokedAt,
        LocalDateTime createdAt) {
}