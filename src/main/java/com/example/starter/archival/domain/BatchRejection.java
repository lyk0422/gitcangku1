package com.example.starter.archival.domain;

import java.time.LocalDateTime;

/**
 * 质检退回理由。只追加，历史退回理由不被覆盖。
 *
 * @param id        主键
 * @param batchId   批次ID
 * @param reason    退回理由
 * @param createdAt 退回时间（Asia/Shanghai）
 */
public record BatchRejection(Long id, Long batchId, String reason, LocalDateTime createdAt) {
}
