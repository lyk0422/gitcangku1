package com.example.starter.aging.domain;

import java.time.Instant;

/**
 * 奶酪熟成批次。
 * entryAt 为入库 UTC 时刻；targetDays 为熟成目标天数（本题固定 7）；
 * status：AGING 熟成中、RELEASED 已放行；createdAt 为记录创建 UTC 时刻。
 */
public record AgingBatch(long id, String batchCode, Instant entryAt, int targetDays,
                         BatchStatus status, Instant createdAt) {
}