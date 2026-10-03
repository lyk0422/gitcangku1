package com.example.starter.reading;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测温点视图。只能追加，不允许修改或删除。
 *
 * @param id           测温点ID，单调递增，放行时以其最大值固化测温点上限
 * @param batchId      所属批次
 * @param measuredAt   测温时刻，UTC
 * @param temperatureC 实测温度，摄氏度
 */
public record TemperatureReading(long id, long batchId, Instant measuredAt, BigDecimal temperatureC) {
}