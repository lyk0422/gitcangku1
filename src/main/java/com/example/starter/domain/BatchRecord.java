package com.example.starter.domain;

import java.time.Instant;

/**
 * 放电交接批次。
 *
 * @param id 批次主键
 * @param batchNo 批次编号，全局唯一，合成编号
 * @param status 批次状态：OPEN=进行中，HANDED_OVER=已交接
 * @param createdAt 批次创建时刻，UTC
 * @param handoverFrom 交接交出方，未交接为 null
 * @param handoverTo 交接接收方，未交接为 null
 * @param handoverAt 交接完成时刻，UTC，未交接为 null
 */
public record BatchRecord(long id, String batchNo, BatchStatus status, Instant createdAt,
                          String handoverFrom, String handoverTo, Instant handoverAt) {
}