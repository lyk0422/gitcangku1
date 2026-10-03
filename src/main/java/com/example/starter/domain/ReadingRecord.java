package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 电芯放电读数。
 *
 * @param id 读数主键（读数编号）
 * @param cellId 所属电芯主键
 * @param voltage 放电测量电压，单位伏特(V)
 * @param measuredAt 测量时刻，UTC
 * @param status 读数状态：VALID=有效，VOID=已作废
 * @param requestId 测量请求幂等键
 * @param createdAt 记录写入时刻，UTC
 */
public record ReadingRecord(long id, long cellId, BigDecimal voltage, Instant measuredAt,
                            ReadingStatus status, String requestId, Instant createdAt) {
}