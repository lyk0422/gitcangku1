package com.example.starter.model;

import java.time.Instant;

/**
 * 除冰作业时隙记录。
 *
 * @param id 主键
 * @param requestId 幂等请求号，唯一
 * @param vehicleId 除冰车 ID
 * @param flightId 航班 ID
 * @param startUtc 时隙开始（UTC，含）
 * @param endUtc 时隙结束（UTC，不含），左闭右开
 * @param status 时隙状态
 */
public record DeicingSlot(
        Long id,
        String requestId,
        long vehicleId,
        long flightId,
        Instant startUtc,
        Instant endUtc,
        SlotStatus status) {
}