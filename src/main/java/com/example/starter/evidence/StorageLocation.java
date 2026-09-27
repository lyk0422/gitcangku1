package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 库位实体，对应 storage_location 表。locationCode 全局唯一。
 *
 * @param id           主键
 * @param locationCode 库位编码，全局唯一
 * @param locationName 库位名称
 * @param status       库位状态
 * @param createdAt    创建时间（Asia/Shanghai）
 * @param updatedAt    最近一次状态变更时间（Asia/Shanghai）
 */
public record StorageLocation(
        Long id,
        String locationCode,
        String locationName,
        LocationStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
