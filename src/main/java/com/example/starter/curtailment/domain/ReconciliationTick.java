package com.example.starter.curtailment.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 对账刻度快照实体，对应表 reconciliation_tick。对账时固化的采样刻度及其版本，后续更正不回写。
 *
 * @param id               主键
 * @param reconciliationId 所属对账单 id
 * @param tickStart        刻度起点（UTC）
 * @param avgPowerKw       对账采用的平均功率，单位 kW
 * @param sampleVersion    对账采用的采样版本号
 * @param energyKwh        该刻度电量，单位 kWh，等于平均功率乘以 0.25 小时
 */
public record ReconciliationTick(long id, long reconciliationId, Instant tickStart, BigDecimal avgPowerKw,
                                 int sampleVersion, BigDecimal energyKwh) {
}