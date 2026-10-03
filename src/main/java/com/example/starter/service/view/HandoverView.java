package com.example.starter.service.view;

import java.time.Instant;
import java.util.List;

/**
 * 交接结果视图：冻结电芯清单、读数编号、交接双方与 UTC 时刻。
 *
 * @param batchNo 批次编号
 * @param status 批次状态，交接成功为 HANDED_OVER
 * @param handoverFrom 交接交出方
 * @param handoverTo 交接接收方
 * @param handoverAt 交接完成时刻，UTC
 * @param cells 冻结的电芯交接清单
 */
public record HandoverView(String batchNo, String status, String handoverFrom, String handoverTo,
                           Instant handoverAt, List<SnapshotView> cells) {
}