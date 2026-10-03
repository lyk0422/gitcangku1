package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 交接快照：交接时冻结的电芯清单与终测读数。
 *
 * @param id 交接快照主键
 * @param batchId 所属批次主键
 * @param cellId 电芯主键
 * @param cellCode 电芯编号快照
 * @param readingId 交接采用的终测读数编号
 * @param voltage 终测电压快照，单位伏特(V)
 * @param measuredAt 终测测量时刻快照，UTC
 */
public record SnapshotRecord(long id, long batchId, long cellId, String cellCode,
                             long readingId, BigDecimal voltage, Instant measuredAt) {
}