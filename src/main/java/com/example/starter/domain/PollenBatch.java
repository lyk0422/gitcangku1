package com.example.starter.domain;

import java.time.Instant;

/**
 * 花粉批号。
 *
 * @param id        主键
 * @param batchNo   花粉批号，全局唯一
 * @param status    批号状态；DISABLED 时登记授粉操作返回 422
 * @param createdAt 创建时刻（UTC）
 */
public record PollenBatch(long id, String batchNo, BatchStatus status, Instant createdAt) {
}