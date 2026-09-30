package com.example.starter.germination.domain;

import java.time.Instant;

/**
 * 种子批次（合成批次数据）。
 *
 * @param batchNo   批次编号，业务主键
 * @param cropName  作物名称
 * @param createdAt 入库时刻（UTC 时间线，序列化按 Asia/Shanghai 展示）
 */
public record SeedBatch(String batchNo, String cropName, Instant createdAt) {
}
