package com.example.starter.api.dto;

/**
 * 重新申请结果。
 *
 * @param reapplyId          重新申请记录标识（不可变）
 * @param sourceOccupationId 被让渡的原占用标识
 * @param newOccupationId    重新申请生成的新占用标识
 * @param status             新占用状态：ACTIVE
 * @param reviewId           重新审查的不可变审查记录标识
 */
public record ReapplyResult(String reapplyId, String sourceOccupationId, String newOccupationId,
                            String status, String reviewId) {
}
