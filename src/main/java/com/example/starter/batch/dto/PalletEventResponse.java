package com.example.starter.batch.dto;

import com.example.starter.batch.PalletEventType;

import java.time.Instant;

/**
 * 托盘事件历史条目，按事件提交顺序排列；detail 落定后不随后续操作改写。
 */
public record PalletEventResponse(
        PalletEventType eventType,
        String detail,
        Instant createdAt
) {
}
