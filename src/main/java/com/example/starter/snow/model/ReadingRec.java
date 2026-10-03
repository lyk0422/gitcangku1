package com.example.starter.snow.model;

import java.time.Instant;

/**
 * 雪况读数行：某一控制区在某个 UTC 整点的合成观测值。
 *
 * @param id 主键
 * @param regionId 控制区标识
 * @param hourUtc 观测整点（UTC）
 * @param depthCm 雪深，厘米
 * @param newSnowCm 新增积雪，厘米；大于 5 视为超限
 * @param requestId 上报幂等键
 * @param createdAt 入库时间（UTC）
 */
public record ReadingRec(long id, String regionId, Instant hourUtc, int depthCm, int newSnowCm,
                         String requestId, Instant createdAt) {
}