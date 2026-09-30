package com.example.starter.billing.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 读数视图。
 *
 * @param readingId        读数行 ID（不可变，更正只新增行）
 * @param kind             读数种类：BASELINE / ACTUAL
 * @param version          读数版本，从 1 起递增
 * @param valueKwh         电量读数，单位 kWh
 * @param periodStart      计量时段起点（UTC，左闭）
 * @param periodEnd        计量时段终点（UTC，右开）
 * @param correctionReason 更正缘由；首次读数为 null
 * @param createdAt        记录创建时间（UTC）
 */
public record ReadingResponse(
        Long readingId,
        String kind,
        int version,
        BigDecimal valueKwh,
        Instant periodStart,
        Instant periodEnd,
        String correctionReason,
        Instant createdAt) {
}
