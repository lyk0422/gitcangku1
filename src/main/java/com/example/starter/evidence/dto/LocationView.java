package com.example.starter.evidence.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 库位视图。
 *
 * @param locationCode 库位编码
 * @param locationName 库位名称
 * @param status       库位状态
 * @param createdAt    创建时间（Asia/Shanghai）
 * @param updatedAt    最近一次状态变更时间（Asia/Shanghai）
 */
public record LocationView(
        String locationCode,
        String locationName,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
