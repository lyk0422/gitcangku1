package com.example.starter.service.view;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 电芯视图。
 *
 * @param id 电芯主键
 * @param cellCode 电芯编号
 * @param initialVoltage 入批初始电压，单位伏特(V)
 * @param enrolledAt 入批时刻，UTC
 * @param status 电芯状态：ACTIVE/ISOLATED
 */
public record CellView(long id, String cellCode, BigDecimal initialVoltage, Instant enrolledAt,
                       String status) {
}