package com.example.starter.evidence.aliquot;

import java.time.LocalDateTime;

/**
 * 联合取样审核历史实体，对应 sampling_review 表，只追加。
 *
 * @param id         主键
 * @param requestId  所属联合取样单业务键
 * @param seq        审核次序：1 第一次确认 / 2 第二次确认；拒绝与取消记为终态动作
 * @param action     审核动作：CONFIRM / REJECT / CANCEL
 * @param reviewerId 审核操作人
 * @param note       审核备注；NULL 表示未填写
 * @param createdAt  审核动作时间（Asia/Shanghai）
 */
public record SamplingReview(
        Long id,
        String requestId,
        int seq,
        ReviewAction action,
        String reviewerId,
        String note,
        LocalDateTime createdAt) {
}
