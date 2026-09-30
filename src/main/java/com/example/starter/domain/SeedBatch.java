package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 种子批次。
 *
 * @param id        数据库主键
 * @param batchNo   批次编号，业务唯一
 * @param cropName  作物名称
 * @param createdAt 创建时刻（Asia/Shanghai）
 */
public record SeedBatch(
        Long id,
        String batchNo,
        String cropName,
        LocalDateTime createdAt
) {
}
