package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 库位实体，对应 storage_location 表。locationCode 全局唯一；
 * version 随库存变动（入库/迁入/迁出）递增，迁移申请与执行以此做乐观校验。
 *
 * @param id           主键
 * @param locationCode 库位编码，全局唯一
 * @param status       库位状态
 * @param version      库存版本，入库/迁入/迁出时递增
 * @param description  库位描述；null 表示未填写
 * @param createdAt    创建时间（Asia/Shanghai）
 * @param updatedAt    最近一次状态或库存变动时间（Asia/Shanghai）
 */
public record StorageLocation(
        Long id,
        String locationCode,
        LocationStatus status,
        int version,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
