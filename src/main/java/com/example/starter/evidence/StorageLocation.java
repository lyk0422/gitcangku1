package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 库位实体，对应 storage_location 表。
 * locationCode/label 创建后不可修改；status 可停用；version 随库存放入/迁出递增。
 *
 * @param id           主键
 * @param locationCode 库位业务键，全局唯一
 * @param label        库位名称说明
 * @param status       库位状态
 * @param version      库存版本号，证物放入或迁出时 +1
 * @param createdAt    创建时间（Asia/Shanghai）
 * @param updatedAt    最近一次状态或库存变更时间（Asia/Shanghai）
 */
public record StorageLocation(
        Long id,
        String locationCode,
        String label,
        LocationStatus status,
        int version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
