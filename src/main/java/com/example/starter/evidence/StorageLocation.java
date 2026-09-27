package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 库位实体，对应 storage_location 表。location_code 全局唯一，停用后不可作为迁移目标。
 *
 * @param id           主键
 * @param locationCode 库位编码，全局唯一
 * @param description  库位说明；null 表示未填写
 * @param status       库位状态
 * @param createdAt    建档时间（Asia/Shanghai）
 * @param disabledAt   停用时间（Asia/Shanghai）；null 表示启用中
 */
public record StorageLocation(
        Long id,
        String locationCode,
        String description,
        LocationStatus status,
        LocalDateTime createdAt,
        LocalDateTime disabledAt) {
}
