package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 入批电芯。
 *
 * @param id 电芯主键
 * @param cellCode 电芯编号，全局唯一，合成编号
 * @param batchId 所属批次主键
 * @param initialVoltage 入批初始电压，单位伏特(V)
 * @param enrolledAt 入批时刻，UTC，终测须晚于该时刻
 * @param status 电芯状态：ACTIVE=在批，ISOLATED=已隔离
 */
public record CellRecord(long id, String cellCode, long batchId, BigDecimal initialVoltage,
                         Instant enrolledAt, CellStatus status) {
}