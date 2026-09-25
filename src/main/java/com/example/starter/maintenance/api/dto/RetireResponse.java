package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 设备退役响应。
 *
 * @param equipmentId  设备标识
 * @param version      退役后的设备版本号
 * @param retiredAt    退役时刻（UTC）
 */
public record RetireResponse(
        String equipmentId,
        long version,
        Instant retiredAt) {
}
