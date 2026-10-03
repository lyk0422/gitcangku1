package com.example.starter.curtailment.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 差额记录实体，对应表 reconciliation_adjustment。迟到更正相对已固化对账刻度产生的电量差额，按时间形成差额链。
 *
 * @param id              主键
 * @param reconciliationId 被更正影响的已固化对账单 id
 * @param instructionId   所属限发指令 id
 * @param tickStart       被更正的刻度起点（UTC）
 * @param oldAvgPowerKw   对账固化的原平均功率，单位 kW
 * @param newAvgPowerKw   更正后的平均功率，单位 kW
 * @param deltaEnergyKwh  差额电量，单位 kWh，可为负
 * @param sampleVersion   产生该差额的采样更正版本号
 * @param createdAt       差额记录时间（UTC）
 */
public record Adjustment(long id, long reconciliationId, long instructionId, Instant tickStart,
                         BigDecimal oldAvgPowerKw, BigDecimal newAvgPowerKw, BigDecimal deltaEnergyKwh,
                         int sampleVersion, Instant createdAt) {
}