package com.example.starter.evidence.dto;

import com.example.starter.evidence.LocationStatus;

import java.time.LocalDateTime;

/**
 * 库位视图。
 *
 * @param locationCode 库位编码
 * @param status       库位状态
 * @param version      库存版本（入库/迁入/迁出时递增）
 * @param description  库位描述；null 表示未填写
 * @param createdAt    创建时间（Asia/Shanghai）
 * @param updatedAt    最近一次状态或库存变动时间（Asia/Shanghai）
 */
public record LocationView(
        String locationCode,
        LocationStatus status,
        int version,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
