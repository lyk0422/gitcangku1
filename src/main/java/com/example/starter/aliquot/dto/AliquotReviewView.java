package com.example.starter.aliquot.dto;

import java.time.LocalDateTime;

/**
 * 取样审核历史视图（只读，只追加）。
 *
 * @param seq        审核顺序：1 第一次确认 / 2 第二次确认
 * @param reviewerId 审核人
 * @param createdAt  审核提交时间（Asia/Shanghai）
 */
public record AliquotReviewView(
        int seq,
        String reviewerId,
        LocalDateTime createdAt) {
}
