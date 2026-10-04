package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 阅检副本实体，对应 inspection_copy 表。签发后 copyKey/evidenceKey/reviewerId/
 * digest/watermark/validFrom/validUntil 全部冻结，仅 status 与 revokedAt 随回收变化。
 *
 * @param id          主键
 * @param copyKey     副本业务键（不可重复副本 ID），全局唯一，水印组成部分
 * @param evidenceKey 关联证物业务键
 * @param reviewerId  阅检人，仅本人可在有效期内读取副本
 * @param digest      签发时冻结的原件数字摘要
 * @param watermark   签发时冻结的水印，含副本 ID 与阅检人
 * @param validFrom   UTC 生效时刻（含），等于签发时刻
 * @param validUntil  UTC 失效时刻（不含），距签发不超过一小时
 * @param status      副本状态
 * @param revokedAt   回收时刻（UTC）；null 表示未回收
 * @param createdAt   签发时间（Asia/Shanghai）
 */
public record InspectionCopy(
        Long id,
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