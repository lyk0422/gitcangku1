package com.example.starter.curtailment.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 对账单实体，对应表 reconciliation。固化指令版本、窗口起点与累计电量；同一指令同一窗口唯一，重复对账不重复累计。
 *
 * @param id                 主键
 * @param instructionId      所属限发指令 id
 * @param windowStart        对账窗口起点（UTC），窗口为起点起连续 4 个 15 分钟刻度
 * @param instructionVersion 对账时固化的指令版本号
 * @param totalEnergyKwh     窗口累计电量，单位 kWh
 * @param requestId          产生该对账单的请求标识
 * @param createdAt          对账时间（UTC）
 */
public record Reconciliation(long id, long instructionId, Instant windowStart, int instructionVersion,
                             BigDecimal totalEnergyKwh, String requestId, Instant createdAt) {
}