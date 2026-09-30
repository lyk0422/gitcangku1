package com.example.starter.archival.web.dto;

import com.example.starter.archival.domain.BatchStatus;

import java.time.LocalDateTime;

/**
 * 批次结果视图。
 *
 * @param batchNo   批次号
 * @param status    批次状态
 * @param sealedAt  封存时间（Asia/Shanghai）；未封存为 null
 * @param createdAt 建批时间（Asia/Shanghai）
 */
public record BatchResponse(String batchNo, BatchStatus status,
                            LocalDateTime sealedAt, LocalDateTime createdAt) {
}
