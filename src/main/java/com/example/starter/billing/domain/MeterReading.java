package com.example.starter.billing.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 不可变计量读数记录；更正不覆盖旧行，只插入版本号更大的新行。
 *
 * @param id               读数自增主键
 * @param offerId          邀约 ID
 * @param userId           用户 ID
 * @param kind             读数种类（BASELINE 基线 / ACTUAL 实际）
 * @param version          读数版本，从 1 起递增；更正产生新版本
 * @param valueKwh         电量读数，单位 kWh，不允许为负；null 表示读数缺失占位
 * @param periodStart      读数计量时段起点（UTC，左闭）
 * @param periodEnd        读数计量时段终点（UTC，右开）
 * @param correctionReason 更正缘由；首次读数为 null，校正记录必填
 * @param createdAt        记录创建时间（UTC）
 */
public record MeterReading(
        Long id,
        String offerId,
        String userId,
        ReadingKind kind,
        int version,
        BigDecimal valueKwh,
        Instant periodStart,
        Instant periodEnd,
        String correctionReason,
        Instant createdAt) {

    /**
     * 是否为更正记录（版本大于 1）。
     *
     * @return 更正记录返回 true
     */
    public boolean isCorrection() {
        return version > 1;
    }
}
