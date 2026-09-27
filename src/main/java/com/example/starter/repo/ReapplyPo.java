package com.example.starter.repo;

/**
 * 重新申请历史记录（不可变）。
 *
 * @param reapplyId          重新申请记录唯一标识
 * @param sourceOccupationId 被让渡的原占用标识
 * @param newOccupationId    重新申请生成的新占用标识
 * @param reviewId           重新审查的不可变审查记录标识
 * @param createdAt          重新申请时间（epoch 毫秒，UTC）
 */
public record ReapplyPo(String reapplyId, String sourceOccupationId, String newOccupationId,
                        String reviewId, long createdAt) {
}
