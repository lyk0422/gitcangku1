package com.example.starter.evidence.dto;

import com.example.starter.evidence.LocationStatus;

import java.time.LocalDateTime;

/**
 * 库位视图。
 *
 * @param locationCode 库位编码，全局唯一
 * @param description  库位说明；null 表示未填写
 * @param status       库位状态：ACTIVE 启用 / DISABLED 已停用
 * @param createdAt    建档时间（Asia/Shanghai）
 * @param disabledAt   停用时间（Asia/Shanghai）；null 表示启用中
 */
public record LocationView(
        String locationCode,
        String description,
        LocationStatus status,
        LocalDateTime createdAt,
        LocalDateTime disabledAt) {
}
