package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 重量差异复核记录，对应 weight_review 表。只追加、不可变；每件证物至多一条（复核不可逆）。
 *
 * @param id          主键
 * @param evidenceKey 关联证物业务键
 * @param reviewerId  提交复核的保管人
 * @param note        复核说明
 * @param createdAt   复核提交时间（Asia/Shanghai）
 */
public record WeightReview(
        Long id,
        String evidenceKey,
        String reviewerId,
        String note,
        LocalDateTime createdAt) {
}
