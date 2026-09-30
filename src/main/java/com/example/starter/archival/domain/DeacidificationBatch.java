package com.example.starter.archival.domain;

import java.time.LocalDateTime;

/**
 * 脱酸批次。
 *
 * @param id        主键
 * @param batchNo   批次号，全局唯一
 * @param status    批次状态
 * @param sealedAt  封存时间（Asia/Shanghai）；未封存为 null
 * @param createdAt 建批时间（Asia/Shanghai）
 */
public record DeacidificationBatch(Long id, String batchNo, BatchStatus status,
                                   LocalDateTime sealedAt, LocalDateTime createdAt) {
}
