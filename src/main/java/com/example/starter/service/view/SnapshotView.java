package com.example.starter.service.view;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 交接快照中单枚电芯的冻结记录。
 *
 * @param cellCode 电芯编号快照
 * @param readingId 交接采用的终测读数编号
 * @param voltage 终测电压快照，单位伏特(V)
 * @param measuredAt 终测测量时刻快照，UTC
 */
public record SnapshotView(String cellCode, long readingId, BigDecimal voltage, Instant measuredAt) {
}