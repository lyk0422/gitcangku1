package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量批次。可配置目标值与允许偏差；revision 为测量修订版本，
 * 已放行批次新增读数或读数被 REJECT 排除时递增。
 *
 * @param id          批次 ID（自增）
 * @param batchKey    业务批次键，全局唯一（幂等键）
 * @param instrumentId 仪器 ID
 * @param targetValue 目标值，最多 6 位小数
 * @param tolerance   允许偏差（>=0），读数偏差超过该值判定离群
 * @param revision    测量修订版本，初始 1
 * @param status      状态：OPEN 待放行 / RELEASED 已放行
 * @param releasedBy  最近一次放行人；未放行或已重新打开为 null
 * @param releasedAt  最近一次放行时间（UTC）；未放行或已重新打开为 null
 * @param createdAt   创建时间（UTC）
 */
public record Batch(
        long id,
        String batchKey,
        String instrumentId,
        BigDecimal targetValue,
        BigDecimal tolerance,
        int revision,
        BatchStatus status,
        String releasedBy,
        Instant releasedAt,
        Instant createdAt) {
}