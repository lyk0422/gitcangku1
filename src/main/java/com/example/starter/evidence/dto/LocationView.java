package com.example.starter.evidence.dto;

import com.example.starter.evidence.LocationStatus;

import java.time.LocalDateTime;

/**
 * 库位视图。
 *
 * @param locationCode 库位业务键
 * @param label        库位名称说明
 * @param status       库位状态
 * @param version      库存版本号（证物放入或迁出时 +1）
 * @param createdAt    创建时间（Asia/Shanghai）
 * @param updatedAt    最近一次状态或库存变更时间（Asia/Shanghai）
 */
public record LocationView(
        String locationCode,
        String label,
        LocationStatus status,
        int version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
