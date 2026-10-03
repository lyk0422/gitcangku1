package com.example.starter.service.view;

import java.time.Instant;
import java.util.List;

/**
 * 批次明细视图：电芯读数链、隔离原因与交接快照。
 *
 * @param batchNo 批次编号
 * @param status 批次状态：OPEN/HANDED_OVER
 * @param createdAt 批次创建时刻，UTC
 * @param handoverFrom 交接交出方，未交接为 null
 * @param handoverTo 交接接收方，未交接为 null
 * @param handoverAt 交接完成时刻，UTC，未交接为 null
 * @param cells 批次内全部电芯（含已隔离）及其读数链
 * @param handoverSnapshot 交接冻结清单，未交接为空表
 */
public record BatchDetailView(String batchNo, String status, Instant createdAt,
                              String handoverFrom, String handoverTo, Instant handoverAt,
                              List<CellDetailView> cells, List<SnapshotView> handoverSnapshot) {
}