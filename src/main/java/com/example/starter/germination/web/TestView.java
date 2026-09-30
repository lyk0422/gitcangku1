package com.example.starter.germination.web;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 检测记录视图。
 *
 * @param testNo           检测编号
 * @param batchNo          批次编号
 * @param sampleCount      样本数，单位粒
 * @param germinatedCount  发芽粒数，单位粒
 * @param ratioPercent     发芽率，单位 %
 * @param status           VALID / VOID
 * @param recordedAt       登记时刻
 * @param voidedAt         作废时刻，未作废为 null
 */
public record TestView(String testNo,
                       String batchNo,
                       int sampleCount,
                       int germinatedCount,
                       BigDecimal ratioPercent,
                       String status,
                       Instant recordedAt,
                       Instant voidedAt) {
}
