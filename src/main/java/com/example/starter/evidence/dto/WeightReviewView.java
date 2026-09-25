package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 重量差异复核记录视图。记录不可变，写入后不可修改。
 *
 * @param evidenceKey 证物业务键
 * @param reviewerId  提交复核的保管人
 * @param note        复核说明
 * @param createdAt   复核提交时间（Asia/Shanghai）
 */
public record WeightReviewView(
        String evidenceKey,
        String reviewerId,
        String note,
        LocalDateTime createdAt) {
}
