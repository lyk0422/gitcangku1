package com.example.starter.evidence.aliquot.dto;

import java.time.LocalDateTime;

/**
 * 联合取样审核历史只读视图，按发生顺序返回。
 *
 * @param seq        审核次序
 * @param action     审核动作：CONFIRM / REJECT / CANCEL
 * @param reviewerId 审核操作人
 * @param note       审核备注；NULL 表示未填写
 * @param createdAt  审核动作时间（Asia/Shanghai）
 */
public record ReviewView(
        int seq,
        String action,
        String reviewerId,
        String note,
        LocalDateTime createdAt) {
}
