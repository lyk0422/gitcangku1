package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 放行请求快照。requestId 全局唯一支撑重放幂等；
 * 同一 requestId 重放时请求指纹（放行人＋各测量环境读数摘要）必须与首次一致，否则判定为冲突。
 *
 * @param id         放行请求 ID（幂等键）
 * @param batchId    首次放行生成的批次 ID（UUID）
 * @param releasedBy 首次放行人（X-Actor-Id）
 * @param fingerprint 请求指纹 SHA-256 十六进制摘要
 * @param releasedAt 首次放行时间（UTC）
 */
public record ReleaseRequestSnapshot(
        String id,
        String batchId,
        String releasedBy,
        String fingerprint,
        Instant releasedAt) {
}
