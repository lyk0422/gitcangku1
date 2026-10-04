package com.example.starter.evidence.dto;

import com.example.starter.evidence.InspectionCopyStatus;

import java.time.LocalDateTime;

/**
 * 阅检副本元数据视图（不含合成文本）。签发重放仅返回该元数据。
 *
 * @param copyId      不可重复副本ID
 * @param evidenceKey 关联证物业务键
 * @param reviewerId  阅检人
 * @param digest      签发时冻结的原件数字摘要
 * @param watermark   签发时冻结的水印，包含副本ID与阅检人
 * @param validFrom   UTC 有效期起点（左闭）
 * @param validTo     UTC 有效期终点（右开）
 * @param status      副本状态：ACTIVE 已签发 / REVOKED 已回收
 * @param issuedAt    签发时刻（UTC）
 * @param revokedAt   回收时刻（UTC）；null 表示未回收
 */
public record CopyView(
        String copyId,
        String evidenceKey,
        String reviewerId,
        String digest,
        String watermark,
        LocalDateTime validFrom,
        LocalDateTime validTo,
        InspectionCopyStatus status,
        LocalDateTime issuedAt,
        LocalDateTime revokedAt) {
}