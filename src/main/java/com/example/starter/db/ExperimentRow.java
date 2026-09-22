package com.example.starter.db;

import java.time.Instant;

/**
 * experiment 表行：实验主信息。
 *
 * @param id          实验唯一编号
 * @param blockCount  固定区组数量（2～8）
 * @param status      实验状态 OPEN/CLOSED
 * @param createdAt   创建时间（UTC）
 */
public record ExperimentRow(
        String id,
        int blockCount,
        String status,
        Instant createdAt
) {
}
